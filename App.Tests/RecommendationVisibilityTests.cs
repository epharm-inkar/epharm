using System.Linq;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Tests;

public sealed class RecommendationVisibilityTests
{
    private static Recommendation Offer(string id) => new() { EventId = id, Kind = "substitution", RecommendPrice = 100, Bonus = 20 };

    private static Recommendation OfferFor(string id, string trigger, bool substitution = true) => new()
    {
        EventId = id, Kind = substitution ? "substitution" : "crosssell", RecommendPrice = 100, Bonus = 20,
        TriggerBarcode = trigger, TriggerName = "Товар " + trigger,
    };

    [Fact]
    public void EachBasketProductKeepsItsOwnFiveOffersPerKind()
    {
        // Two products in the receipt with six replacements each: the popup gets five per product, not five in total.
        var offers = Enumerable.Range(1, 6).Select(i => OfferFor("a" + i, "4600000000017"))
            .Concat(Enumerable.Range(1, 6).Select(i => OfferFor("b" + i, "4600000000024")))
            .Concat(new[] { OfferFor("c1", "4600000000024", substitution: false) })
            .ToList();
        var result = RecommendationVisibility.Reconcile([], offers, _ => false);
        Assert.Equal(11, result.Count);
        Assert.Equal(5, result.Count(r => r.TriggerBarcode == "4600000000017"));
        Assert.Equal(6, result.Count(r => r.TriggerBarcode == "4600000000024"));
        Assert.Equal(new[] { "a1", "a2", "a3", "a4", "a5" }, result.Take(5).Select(r => r.EventId));
    }

    [Fact]
    public void TriggerKeyPrefersBarcodeThenPartThenName()
    {
        Assert.Equal("4600000000017", RecommendationPopupModelBuilder.TriggerKey(new Recommendation { TriggerBarcode = "4600000000017", TriggerIpartId = "77", TriggerName = "X" }));
        Assert.Equal("77", RecommendationPopupModelBuilder.TriggerKey(new Recommendation { TriggerIpartId = "77", TriggerName = "X" }));
        Assert.Equal("X", RecommendationPopupModelBuilder.TriggerKey(new Recommendation { TriggerName = " X " }));
    }

    [Fact]
    public void EmptyOrFailedRefreshRemovesStaleOpenOffer()
    {
        var visible = new[] { Offer("a") };
        Assert.Empty(RecommendationVisibility.Reconcile(visible, [], _ => true));
        Assert.Empty(RecommendationVisibility.Reconcile(visible, null, _ => true));
    }

    [Fact]
    public void PartialCartRemovalKeepsOtherAlreadyShownOffer()
    {
        var visible = new[] { Offer("a"), Offer("b") };
        var remaining = RecommendationVisibility.ForCart(visible, rec => rec.EventId == "b");
        Assert.Same(visible[1], Assert.Single(remaining));
        Assert.Same(visible[1], Assert.Single(RecommendationVisibility.Reconcile(remaining, [Offer("b")], _ => true)));
    }

    [Fact]
    public void RefreshUpdatesAvailabilityWithoutRewritingFinancialQuote()
    {
        var original = Offer("a");
        var updated = Offer("a");
        updated.RecommendPrice = 999;
        updated.Bonus = 900;
        updated.Balance = new() { Status = "stale", PharmacyAvailableQty = 0 };
        var result = Assert.Single(RecommendationVisibility.Reconcile([original], [updated], _ => true));
        Assert.Equal(100, result.RecommendPrice);
        Assert.Equal(20, result.Bonus);
        Assert.Same(updated.Balance, result.Balance);
    }

    [Fact]
    public void DismissedOffersStayDismissedButNewOnesAppear()
    {
        var result = RecommendationVisibility.Reconcile([], [Offer("dismissed"), Offer("new")], r => r.EventId == "dismissed");
        Assert.Equal("new", Assert.Single(result).EventId);
    }

    [Theory]
    [InlineData("unknown", true, "Остаток не подтверждён", false)]
    [InlineData("estimated", true, "Расчётный остаток", false)]
    [InlineData("stale", true, "Остаток устарел", false)]
    [InlineData("confirmed", false, "Остаток не подтверждён", false)]
    [InlineData("confirmed", true, "Остаток:", true)]
    [InlineData("future-status", true, "Остаток не подтверждён", false)]
    public void StockStatusCannotImplyFalseCertainty(string status, bool timestamp, string text, bool confirmed)
    {
        var rec = Offer("a");
        rec.Balance = new() { Status = status, PharmacyAvailableQty = 5,
            ObservedAt = timestamp ? DateTimeOffset.UtcNow : null };
        var row = Assert.Single(RecommendationPopupModelBuilder.Build([rec]).Substitutions.Rows);
        Assert.StartsWith(text, row.Stock);
        Assert.Equal(confirmed, row.HasConfirmedStock);
        Assert.Contains("аптека 5", row.Stock);
    }
}
