using System.Text.Json;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

/// <summary>The till and the server speak the same JSON about an unknown Standard-N user (1.0.101).</summary>
public sealed class IdentityClaimDtosTests
{
    [Fact]
    public void The_claim_goes_out_in_the_servers_camelCase_and_the_state_request_leaves_the_typed_fields_out()
    {
        var claim = JsonSerializer.Serialize(
            new PosmSelfIdentityRequestDto("pharmacy-1", "18", "Бузурбаева Л", "900115300013", "Бузурбаева Лаура Ермековна"), EpharmJson.Options);
        using var parsed = JsonDocument.Parse(claim);
        Assert.Equal("pharmacy-1", parsed.RootElement.GetProperty("pharmacyId").GetString());
        Assert.Equal("18", parsed.RootElement.GetProperty("standardNUserId").GetString());
        Assert.Equal("Бузурбаева Л", parsed.RootElement.GetProperty("standardNUserName").GetString());
        Assert.Equal("900115300013", parsed.RootElement.GetProperty("iin").GetString());
        Assert.Equal("Бузурбаева Лаура Ермековна", parsed.RootElement.GetProperty("fullName").GetString());

        var state = JsonSerializer.Serialize(new PosmSelfIdentityRequestDto("pharmacy-1", "18"), EpharmJson.Options);
        using var stateParsed = JsonDocument.Parse(state);
        Assert.Equal("18", stateParsed.RootElement.GetProperty("standardNUserId").GetString());
        Assert.True(!stateParsed.RootElement.TryGetProperty("iin", out var iin) || iin.ValueKind == JsonValueKind.Null);
    }

    [Theory]
    [InlineData("""{"state":"unknown"}""", "unknown", null, null)]
    [InlineData("""{"state":"pending","iinMasked":"900115•••••3","fullName":"Бузурбаева Лаура Ермековна","reason":null}""", "pending", "900115•••••3", null)]
    [InlineData("""{"state":"rejected","reason":"ИИН не совпадает с документом"}""", "rejected", null, "ИИН не совпадает с документом")]
    [InlineData("""{"state":"known"}""", "known", null, null)]
    public void The_state_of_the_user_is_read_as_the_server_sends_it(string json, string state, string? masked, string? reason)
    {
        var dto = JsonSerializer.Deserialize<PosmSelfIdentityDto>(json, EpharmJson.Options)!;
        Assert.Equal(state, dto.State);
        Assert.Equal(masked, dto.IinMasked);
        Assert.Equal(reason, dto.Reason);
    }

    [Fact]
    public void The_servers_error_body_gives_the_message_and_the_machine_code()
    {
        var (message, code) = EpharmApiClient.ReadSelfError(
            """{"code":"STANDARDN_USER_UNKNOWN","message":"Не нашли вас в справочнике Стандарт-Н","timestamp":"2026-11-26T10:00:00Z"}""");
        Assert.Equal("Не нашли вас в справочнике Стандарт-Н", message);
        Assert.Equal(PosmSelfResult<PosmSelfDto>.StandardNUserUnknown, code);

        // An older server and a proxy page send other bodies: nothing to read, the till shows its own text.
        Assert.Equal((null, null), EpharmApiClient.ReadSelfError(null));
        Assert.Equal((null, null), EpharmApiClient.ReadSelfError(""));
        Assert.Equal((null, null), EpharmApiClient.ReadSelfError("<html>502</html>"));
        Assert.Equal((null, null), EpharmApiClient.ReadSelfError("[1,2]"));
        Assert.Equal((null, null), EpharmApiClient.ReadSelfError("""{"code":5,"message":7}"""));
    }

    [Fact]
    public void Only_the_unknown_user_code_of_a_failed_call_opens_the_form()
    {
        Assert.True(new PosmSelfResult<PosmSelfDto>(null, "text", "STANDARDN_USER_UNKNOWN").IdentityUnknown);
        Assert.False(new PosmSelfResult<PosmSelfDto>(null, "text", "USER_NOT_FOUND").IdentityUnknown);
        Assert.False(new PosmSelfResult<PosmSelfDto>(null, "text").IdentityUnknown);
        Assert.False(new PosmSelfResult<PosmSelfDto>(null, "text", "standardn_user_unknown").IdentityUnknown);
        var ok = new PosmSelfResult<PosmSelfIdentityDto>(new PosmSelfIdentityDto("known"), null, "STANDARDN_USER_UNKNOWN");
        Assert.True(ok.Ok);
        Assert.False(ok.IdentityUnknown);
    }
}
