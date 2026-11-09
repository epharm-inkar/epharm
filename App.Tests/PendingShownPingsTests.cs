using System.Text.Json;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Tests;

public sealed class PendingShownPingsTests : IDisposable
{
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "shown-pings-test-" + Guid.NewGuid().ToString("N"));

    [Fact]
    public async Task FailedFirstInsertRetriesOriginalTimestampWithoutAnotherPopup()
    {
        var fail = true;
        var received = new List<OutboxShownPayload>();
        using var queue = new PendingShownPings(_directory, (id, kind, payload) =>
        {
            if (fail) throw new IOException("database busy");
            Assert.Equal("shown_a", id);
            Assert.Equal("shown", kind);
            received.Add(JsonSerializer.Deserialize<OutboxShownPayload>(payload, EpharmJson.Options)!);
        }, autoStart: false);
        var first = DateTimeOffset.UtcNow;
        Assert.True(queue.Record("a", first));
        await queue.FlushAsync();
        Assert.Equal(1, queue.PendingCount);
        Assert.Single(Directory.GetFiles(_directory, "*.json"));
        Assert.True(queue.Record("a", first.AddMinutes(1)));
        fail = false;
        await queue.FlushAsync();
        Assert.Equal(first, Assert.Single(received).ShownAt);
        Assert.Equal(0, queue.PendingCount);
        Assert.Empty(Directory.GetFiles(_directory, "*.json"));
    }

    [Fact]
    public async Task JournalRecoversAcrossRestartAndKeepsFirstDisplay()
    {
        var first = DateTimeOffset.UtcNow;
        using (var queue = new PendingShownPings(_directory, (_, _, _) => throw new IOException(), autoStart: false))
        {
            queue.Record("a", first);
            await queue.FlushAsync();
        }
        OutboxShownPayload? received = null;
        using var restarted = new PendingShownPings(_directory, (_, _, json) =>
            received = JsonSerializer.Deserialize<OutboxShownPayload>(json, EpharmJson.Options), autoStart: false);
        restarted.Record("a", first.AddHours(1));
        await restarted.FlushAsync();
        Assert.Equal(first, received!.ShownAt);
    }

    [Fact]
    public async Task RewardBaselineIsFrozenAndRecoversPastLegacy4KiBLimitWithoutResettingFirstShow()
    {
        var first = DateTimeOffset.UtcNow;
        var baseline = RewardBaseline(512);
        var expectedIdentity = baseline.Identity;
        using (var queue = new PendingShownPings(_directory, (_, _, _) => throw new IOException("SQLite busy"), autoStart: false))
        {
            Assert.True(queue.Record("reward-event", first, baseline));
            baseline.Lines.Clear(); // The queued payload must not alias a mutable live cart.
            await queue.FlushAsync();
            Assert.Single(Directory.GetFiles(_directory, "*.json"));
            Assert.True(new FileInfo(Directory.GetFiles(_directory, "*.json").Single()).Length > 4096);
            Assert.Equal(1, queue.PendingCount);
        }
        OutboxShownPayload? restored = null;
        using var restarted = new PendingShownPings(_directory, (_, _, json) =>
            restored = JsonSerializer.Deserialize<OutboxShownPayload>(json, EpharmJson.Options), autoStart: false);
        Assert.True(restarted.Record("reward-event", first.AddHours(1), RewardBaseline(1)));
        await restarted.FlushAsync();
        Assert.Equal(first, restored!.ShownAt);
        Assert.Equal("checkout-original", restored.RewardEvidence!.CheckoutSessionId);
        Assert.Equal(expectedIdentity, restored.RewardEvidence.Identity);
        Assert.Equal(512, restored.RewardEvidence.Lines.Count);
        Assert.Empty(Directory.GetFiles(_directory, "*.json"));
        Assert.Equal(0, restarted.PendingCount);
    }

    [Fact]
    public async Task LargeRewardBaselinesUseAnAggregateMemoryBoundAndOversizedRecordsAreRejected()
    {
        using var queue = new PendingShownPings(_directory, (_, _, _) => { }, autoStart: false,
            shutdownGrace: TimeSpan.FromMilliseconds(1));
        var first = DateTimeOffset.UtcNow;
        var baseline = RewardBaseline(512);
        var accepted = 0;
        while (accepted < 4096 && queue.Record("reward-" + accepted, first, baseline)) accepted++;
        Assert.InRange(accepted, 1, 4095); // Byte budget, not merely the legacy 4096 ID count.
        Assert.Equal(accepted, queue.PendingCount);
        Assert.True(queue.Record("reward-0", first.AddHours(1), baseline)); // A duplicate consumes no additional budget.
        var oversized = baseline with { Lines = baseline.Lines.Select(line => line with { Sku = new string('x', 1024) }).ToList() };
        Assert.False(queue.Record("oversized", first, oversized));
        Assert.Equal(accepted, queue.PendingCount);
        await queue.DrainAsync(); // Finish the owned background writer before removing its test directory.
    }

    private static RewardShownEvidence RewardBaseline(int count) => new() {
        CheckoutSessionId = "checkout-original", ObservationId = "show-original", CartComplete = false,
        Identity = new() { SourceNamespace = new string('a', 64), RawUserId = "native-user-17", NativeSessionId = 80, WorkstationId = "9" },
        Lines = Enumerable.Range(1, count).Select(index => new RewardCartLine {
            SourceDocumentId = 100, SourceLineId = index, Sku = "10", Barcode = "4600000000012", Qty = 1, UnitSemantics = "unknown",
        }).ToList(),
    };

    [Fact]
    public async Task DiskFailureRetainsMemoryAndIsRetriedAfterRecovery()
    {
        File.WriteAllText(_directory, "storage unavailable");
        var writes = 0;
        using var queue = new PendingShownPings(_directory, (_, _, _) => writes++, autoStart: false);
        queue.Record("a", DateTimeOffset.UtcNow);
        await queue.FlushAsync();
        Assert.Equal(1, queue.PendingCount);
        Assert.Equal(0, writes);
        File.Delete(_directory);
        await queue.FlushAsync();
        Assert.Equal(0, queue.PendingCount);
        Assert.Equal(1, writes);
    }

    [Fact]
    public async Task UiRecordNeverWaitsForBlockedDiskWriterAndFlushesDoNotOverlap()
    {
        using var entered = new ManualResetEventSlim();
        using var release = new ManualResetEventSlim();
        var writes = 0;
        using var queue = new PendingShownPings(_directory, (_, _, _) =>
        {
            Interlocked.Increment(ref writes);
            entered.Set();
            Assert.True(release.Wait(TimeSpan.FromSeconds(5)));
        }, autoStart: false);
        queue.Record("a", DateTimeOffset.UtcNow);
        var work = queue.FlushAsync();
        Assert.True(entered.Wait(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken));
        try
        {
            Assert.True(queue.Record("b", DateTimeOffset.UtcNow));
            await queue.FlushAsync();
            Assert.Equal(1, Volatile.Read(ref writes));
        }
        finally { release.Set(); }
        await work;
        await queue.FlushAsync();
        Assert.Equal(2, writes);
    }

    [Fact]
    public async Task PendingMemoryIsBoundedAndIdsCannotBecomePaths()
    {
        using var queue = new PendingShownPings(_directory, (_, _, _) => { }, autoStart: false, shutdownGrace: TimeSpan.FromMilliseconds(1));
        for (var i = 0; i < 4096; i++) Assert.True(queue.Record(i.ToString(), DateTimeOffset.UtcNow));
        Assert.False(queue.Record("overflow", DateTimeOffset.UtcNow));
        Assert.True(queue.Record("0", DateTimeOffset.UtcNow));
        Assert.False(queue.Record(new string('x', 257), DateTimeOffset.UtcNow));
        await queue.DrainAsync();
    }

    [Fact]
    public async Task OrderlyShutdownBeforeFirstTimerJournalsAcceptedDisplay()
    {
        var first = DateTimeOffset.UtcNow;
        using (var queue = new PendingShownPings(_directory, (_, _, _) => throw new Exception("Must not wait for SQLite on shutdown"), autoStart: false))
            Assert.True(queue.Record("before-exit", first));
        OutboxShownPayload? recovered = null;
        using var restarted = new PendingShownPings(_directory, (_, _, json) => recovered = JsonSerializer.Deserialize<OutboxShownPayload>(json, EpharmJson.Options), autoStart: false);
        await restarted.FlushAsync();
        Assert.Equal(first, recovered!.ShownAt);
    }

    [Fact]
    public async Task ConcurrentProducersCannotExceedCapacity()
    {
        using var queue = new PendingShownPings(_directory, (_, _, _) => { }, autoStart: false, shutdownGrace: TimeSpan.FromMilliseconds(1));
        await Task.WhenAll(Enumerable.Range(0, 8).Select(worker => Task.Run(() =>
        {
            for (var index = 0; index < 1000; index++) queue.Record($"{worker}-{index}", DateTimeOffset.UtcNow);
        }, TestContext.Current.CancellationToken)));
        Assert.Equal(4096, queue.PendingCount);
        await queue.DrainAsync();
    }

    [Fact]
    public async Task DiskRecoveryDoesNotOverflowFullLiveBuffer()
    {
        using (var previous = new PendingShownPings(_directory, (_, _, _) => throw new IOException(), autoStart: false))
        {
            previous.Record("old-disk-record", DateTimeOffset.UtcNow);
            await previous.FlushAsync();
        }
        var oldRecovered = false;
        using var queue = new PendingShownPings(_directory, (id, _, _) => { if (id == "shown_old-disk-record") oldRecovered = true; }, autoStart: false, shutdownGrace: TimeSpan.FromMilliseconds(1));
        for (var index = 0; index < 4096; index++) queue.Record("live-" + index, DateTimeOffset.UtcNow);
        for (var attempt = 0; attempt < 3 && !oldRecovered; attempt++) await queue.FlushAsync();
        Assert.True(oldRecovered);
        Assert.InRange(queue.PendingCount, 0, 4096);
        await queue.DrainAsync();
    }

    [Fact]
    public async Task ShutdownDoesNotHangBehindBlockedSqliteWriter()
    {
        using var entered = new ManualResetEventSlim();
        using var release = new ManualResetEventSlim();
        var messages = new List<string>();
        var queue = new PendingShownPings(_directory, (_, _, _) => { entered.Set(); release.Wait(TimeSpan.FromSeconds(5)); }, messages.Add,
            autoStart: false, shutdownGrace: TimeSpan.FromMilliseconds(50));
        queue.Record("a", DateTimeOffset.UtcNow);
        var flush = queue.FlushAsync();
        Assert.True(entered.Wait(TimeSpan.FromSeconds(2), TestContext.Current.CancellationToken));
        queue.Record("b", DateTimeOffset.UtcNow);
        var watch = System.Diagnostics.Stopwatch.StartNew();
        queue.Dispose();
        Assert.True(watch.Elapsed < TimeSpan.FromSeconds(1));
        Assert.Contains(messages, message => message.Contains("deadline"));
        Assert.False(queue.Record("closed", DateTimeOffset.UtcNow));
        release.Set();
        await flush;
        await queue.DrainAsync();
        Assert.True(Directory.GetFiles(_directory, "*.json").Length >= 1);
    }

    public void Dispose()
    {
        if (Directory.Exists(_directory)) Directory.Delete(_directory, true);
        else if (File.Exists(_directory)) File.Delete(_directory);
    }
}
