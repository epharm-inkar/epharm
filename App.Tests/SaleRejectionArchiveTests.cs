using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using CustomerDisplay.Config;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Microsoft.Data.Sqlite;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class SaleRejectionArchiveTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "epharm-rejected-" + Guid.NewGuid().ToString("N"));
    private string DbPath => Path.Combine(_root, "outbox.db");

    [Fact]
    public async Task ConflictIsRetriedHourlyThenArchivedWithItsPayload()
    {
        var queue = new OfflineOutbox(DbPath);
        var payload = SaleJson("conflicting-sale");
        queue.Enqueue("conflicting-sale", "sale", payload);
        using var server = new StatusEndpoint { SaleStatus = 409 };
        using var api = Api(server);
        using var flusher = new OutboxFlusher(queue, api, 3600, startBackground: false);

        await flusher.FlushNowAsync();
        Assert.Equal(1, Scalar("SELECT rejections FROM outbox WHERE id='conflicting-sale'"));
        var nextRetry = DateTimeOffset.Parse((string)ScalarObject("SELECT next_retry_at FROM outbox WHERE id='conflicting-sale'")!);
        Assert.InRange(nextRetry - DateTimeOffset.UtcNow, TimeSpan.FromMinutes(59), TimeSpan.FromMinutes(61));

        // A network flap resends ordinary queue rows at once, never a refused one.
        await flusher.FlushNowAsync();
        Assert.Single(server.SaleRequests);

        for (var attempt = 2; attempt <= OfflineOutbox.MaxRejections; attempt++)
        {
            MakeDue();
            await flusher.FlushAsync();
        }

        Assert.Equal(OfflineOutbox.MaxRejections, server.SaleRequests.Count);
        Assert.Equal(0, Scalar("SELECT COUNT(*) FROM outbox"));
        Assert.Equal(payload, ScalarObject("SELECT payload FROM outbox_rejected WHERE id='conflicting-sale'"));
        Assert.Equal(OfflineOutbox.MaxRejections, Scalar("SELECT rejections FROM outbox_rejected WHERE id='conflicting-sale'"));

        MakeDue();
        await flusher.FlushNowAsync();
        Assert.Equal(OfflineOutbox.MaxRejections, server.SaleRequests.Count);
    }

    [Fact]
    public async Task UnauthorizedIsPermanentOnlyWhileTheHeartbeatIsAccepted()
    {
        var queue = new OfflineOutbox(DbPath);
        queue.Enqueue("foreign-pharmacy-sale", "sale", SaleJson("foreign-pharmacy-sale"));
        using var server = new StatusEndpoint { SaleStatus = 401 };
        using var api = Api(server);
        using var flusher = new OutboxFlusher(queue, api, 3600, startBackground: false);

        // No accepted heartbeat yet: the device key itself may be the problem, keep the sale.
        await flusher.FlushNowAsync();
        Assert.Equal(0, Scalar("SELECT rejections FROM outbox WHERE id='foreign-pharmacy-sale'"));
        queue.MarkAllDue();
        Assert.Single(queue.DequeueReady());

        Assert.True(await api.HeartbeatAsync("POS-01", "pharmacy-1", 1, "1.0.91", TestContext.Current.CancellationToken));
        await flusher.FlushNowAsync();
        Assert.Equal(1, Scalar("SELECT rejections FROM outbox WHERE id='foreign-pharmacy-sale'"));
    }

    [Fact]
    public async Task TransientFailureKeepsTheOldBackoffAndNeverArchives()
    {
        var queue = new OfflineOutbox(DbPath);
        queue.Enqueue("offline-sale", "sale", SaleJson("offline-sale"));
        using var server = new StatusEndpoint { SaleStatus = 503 };
        using var api = Api(server);
        using var flusher = new OutboxFlusher(queue, api, 3600, startBackground: false);

        for (var attempt = 0; attempt < OfflineOutbox.MaxRejections + 2; attempt++)
        {
            MakeDue();
            await flusher.FlushAsync();
        }

        Assert.Equal(0, Scalar("SELECT rejections FROM outbox WHERE id='offline-sale'"));
        Assert.Equal(OfflineOutbox.MaxRejections + 2, Scalar("SELECT attempts FROM outbox WHERE id='offline-sale'"));
        Assert.Equal(0, Scalar("SELECT COUNT(*) FROM outbox_rejected"));
        server.SaleStatus = 204;
        await flusher.FlushNowAsync();
        Assert.Equal(0, Scalar("SELECT COUNT(*) FROM outbox"));
    }

    [Fact]
    public void QueueFromAnOlderBuildGainsTheCounterAndKeepsItsRows()
    {
        Directory.CreateDirectory(_root);
        using (var conn = new SqliteConnection($"Data Source={DbPath};Pooling=False"))
        {
            conn.Open();
            using var cmd = conn.CreateCommand();
            cmd.CommandText = @"CREATE TABLE outbox (id TEXT PRIMARY KEY, kind TEXT NOT NULL, payload TEXT NOT NULL,
                created_at TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_retry_at TEXT NOT NULL);
                INSERT INTO outbox VALUES ('old-row','sale',$payload,'2026-10-01T00:00:00+00:00',40,'2026-10-01T00:00:00+00:00');";
            cmd.Parameters.AddWithValue("$payload", SaleJson("old-row"));
            cmd.ExecuteNonQuery();
        }

        var queue = new OfflineOutbox(DbPath);
        var row = Assert.Single(queue.DequeueReady());
        Assert.Equal(40, row.Attempts);
        Assert.False(queue.RecordRejection("old-row"));
        Assert.Equal(1, Scalar("SELECT rejections FROM outbox WHERE id='old-row'"));
        _ = new OfflineOutbox(DbPath); // reopening must not try to add the column twice
    }

    [Fact]
    public void RarelyChangingDataIsAskedForAtMostAsOftenAsNeeded()
    {
        Assert.Equal(TimeSpan.FromSeconds(60), PosmPollIntervals.Heartbeat);
        Assert.Equal(TimeSpan.FromHours(1), PosmPollIntervals.NextFulfillmentPoll(queueAvailable: false, configuredPollSec: 10));
        Assert.Equal(TimeSpan.FromSeconds(10), PosmPollIntervals.NextFulfillmentPoll(queueAvailable: true, configuredPollSec: 10));
        Assert.Equal(TimeSpan.FromSeconds(5), PosmPollIntervals.NextFulfillmentPoll(queueAvailable: true, configuredPollSec: 0));
        Assert.Equal(TimeSpan.FromHours(1), PosmPollIntervals.NextStaffCapabilityCheck(new StaffCapabilityCheck(false, "server_disabled")));
        Assert.Equal(TimeSpan.FromMinutes(15), PosmPollIntervals.NextStaffCapabilityCheck(new StaffCapabilityCheck(true, "enabled")));
        Assert.Equal(TimeSpan.FromMinutes(5), PosmPollIntervals.NextStaffCapabilityCheck(new StaffCapabilityCheck(false, "network_unavailable")));
        Assert.Equal(TimeSpan.FromMinutes(5), PosmPollIntervals.NextStaffCapabilityCheck(new StaffCapabilityCheck(false, "endpoint_unavailable")));
    }

    private static EpharmApiClient Api(StatusEndpoint server) =>
        new(new EpharmConfig { BackendBaseUrl = server.BaseUrl, DeviceKey = "synthetic-key" });

    private static string SaleJson(string id) => JsonSerializer.Serialize(
        new SaleReport { SaleId = id, CaptureSource = "standardn-print-log" }, EpharmJson.Options);

    private void MakeDue() => Execute("UPDATE outbox SET next_retry_at='2000-01-01T00:00:00+00:00'");

    private long Scalar(string sql) => Convert.ToInt64(ScalarObject(sql));

    private object? ScalarObject(string sql)
    {
        using var conn = new SqliteConnection($"Data Source={DbPath};Pooling=False");
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = sql;
        return cmd.ExecuteScalar();
    }

    private void Execute(string sql)
    {
        using var conn = new SqliteConnection($"Data Source={DbPath};Pooling=False");
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = sql;
        cmd.ExecuteNonQuery();
    }

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        try { Directory.Delete(_root, recursive: true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }

    private sealed class StatusEndpoint : IDisposable
    {
        private readonly HttpListener _listener = new();
        private readonly Task _worker;
        private int _saleStatus = 204;
        public string BaseUrl { get; }
        public int SaleStatus { get => Volatile.Read(ref _saleStatus); set => Volatile.Write(ref _saleStatus, value); }
        public ConcurrentQueue<string> SaleRequests { get; } = new();

        public StatusEndpoint()
        {
            var socket = new TcpListener(IPAddress.Loopback, 0);
            socket.Start();
            var port = ((IPEndPoint)socket.LocalEndpoint).Port;
            socket.Stop();
            BaseUrl = $"http://127.0.0.1:{port}/";
            _listener.Prefixes.Add(BaseUrl);
            _listener.Start();
            _worker = RunAsync();
        }

        private async Task RunAsync()
        {
            try
            {
                while (_listener.IsListening)
                {
                    var context = await _listener.GetContextAsync();
                    var path = context.Request.Url!.AbsolutePath;
                    if (path == "/api/posm/sales")
                    {
                        SaleRequests.Enqueue(path);
                        context.Response.StatusCode = SaleStatus;
                    }
                    else context.Response.StatusCode = 204;
                    context.Response.Close();
                }
            }
            catch (Exception error) when (error is HttpListenerException or ObjectDisposedException) { }
        }

        public void Dispose()
        {
            _listener.Close();
            _worker.GetAwaiter().GetResult();
        }
    }
}
