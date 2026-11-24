using System.Text.Json;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

/// <summary>The till's personal window reads exactly what the server sends (PosmSelfService, camelCase JSON).</summary>
public sealed class PosmSelfDtosTests
{
    [Fact]
    public void Reads_the_server_view_including_a_missing_cabinet_link()
    {
        const string json = """
            {"fullName":"Synthetic Person","iinMasked":"000101•••••5","pharmacy":"Synthetic pharmacy",
             "earnedMonthKzt":3200,"balanceKzt":1200,
             "recent":[{"kind":"substitution","label":"Synthetic spray","bonusKzt":200,"at":"2026-11-24T13:40:00Z"}],
             "phoneBound":true,"passwordSet":false,"cabinetUrl":null,"cabinetUrlExpiresAt":null}
            """;
        var view = JsonSerializer.Deserialize<PosmSelfDto>(json, EpharmJson.Options)!;
        Assert.Equal("Synthetic Person", view.FullName);
        Assert.Equal(3200, view.EarnedMonthKzt);
        Assert.True(view.PhoneBound);
        Assert.False(view.PasswordSet);
        Assert.Null(view.CabinetUrl);
        Assert.Equal(200, Assert.Single(view.Recent).BonusKzt);
    }

    [Fact]
    public void The_background_check_asks_for_no_link()
    {
        var body = JsonSerializer.Serialize(new PosmSelfRequestDto("pharmacy-1", "34", Link: false), EpharmJson.Options);
        using var parsed = JsonDocument.Parse(body);
        Assert.Equal("pharmacy-1", parsed.RootElement.GetProperty("pharmacyId").GetString());
        Assert.Equal("34", parsed.RootElement.GetProperty("standardNUserId").GetString());
        Assert.False(parsed.RootElement.GetProperty("link").GetBoolean());
    }
}
