using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using CustomerDisplay.Config;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class RecommendationSourceNamespaceClientTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task OptionalNamespaceUsesTheExistingRecommendationRouteAndKeepsOldResponsesWorking(bool knownSource)
    {
        using var portReservation = new TcpListener(IPAddress.Loopback, 0);
        portReservation.Start();
        var port = ((IPEndPoint)portReservation.LocalEndpoint).Port;
        portReservation.Stop();
        var origin = $"http://127.0.0.1:{port}/";
        using var listener = new HttpListener(); listener.Prefixes.Add(origin); listener.Start();
        using var api = new EpharmApiClient(new EpharmConfig {
            BackendBaseUrl = origin, BackendFallbackBaseUrls = [], DeviceKey = "synthetic-device-key",
        });
        var request = new RecommendRequest { PharmacyId = "synthetic-pharmacy", PharmacistId = "17", SessionId = "checkout",
            SourceNamespace = knownSource ? new string('a', 64) : null,
            Cart = [new CartItem { Barcode = "4600000000012", Qty = 1 }] };

        var responseTask = api.RecommendAsync(request, TestContext.Current.CancellationToken);
        var context = await listener.GetContextAsync().WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken);
        using var input = new StreamReader(context.Request.InputStream);
        using var body = JsonDocument.Parse(await input.ReadToEndAsync(TestContext.Current.CancellationToken));
        Assert.Equal("/api/posm/recommend", context.Request.Url!.AbsolutePath);
        Assert.Empty(context.Request.Url.Query);
        Assert.Equal("synthetic-device-key", context.Request.Headers["X-Posm-Key"]);
        Assert.Equal(knownSource, body.RootElement.TryGetProperty("sourceNamespace", out var source));
        if (knownSource) Assert.Equal(request.SourceNamespace, source.GetString());
        Assert.Equal("17", body.RootElement.GetProperty("pharmacistId").GetString());
        var bytes = Encoding.UTF8.GetBytes("""{"sessionId":"checkout","recommendations":[{"eventId":"event","recommendSku":"offered","recommendName":"Preserved offer"}]}""");
        context.Response.StatusCode = 200; context.Response.ContentType = "application/json";
        context.Response.ContentLength64 = bytes.Length;
        await context.Response.OutputStream.WriteAsync(bytes, TestContext.Current.CancellationToken);
        context.Response.Close();

        var response = await responseTask;
        Assert.NotNull(response);
        Assert.Equal("Preserved offer", Assert.Single(response.Recommendations).RecommendName);
    }
}
