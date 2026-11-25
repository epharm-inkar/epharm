using CustomerDisplay.Models.Posm;
using Xunit;

namespace CustomerDisplay.Tests;

public sealed class RecommendationPopupModelTests
{
    [Fact]
    public void Build_PreservesExactCountsAndCapsEachKindAtFive()
    {
        var source = Enumerable.Range(1, 7)
            .Select(i => Recommendation("substitution", $"Replacement {i}", "Trigger A", 1_000 + i))
            .Concat(Enumerable.Range(1, 6)
                .Select(i => Recommendation("crosssell", $"Cross-sell {i}", "Trigger B", 2_000 + i)))
            .ToList();

        var model = RecommendationPopupModelBuilder.Build(source);

        Assert.Equal(5, model.Substitutions.Rows.Count);
        Assert.Equal(5, model.CrossSells.Rows.Count);
        Assert.Equal(10, model.TotalCount);
        Assert.Equal("Replacement 1", model.Substitutions.Rows[0].Name);
        Assert.Equal("Cross-sell 5", model.CrossSells.Rows[4].Name);
    }

    [Fact]
    public void Build_UsesSharedTriggerAndTengeFormatting()
    {
        var model = RecommendationPopupModelBuilder.Build(new[]
        {
            Recommendation("substitution", "Аналог 1", "Исходный", 12_490),
            Recommendation("substitution", "Аналог 2", "Исходный", 8_900),
        });

        Assert.Equal("Исходный · 20 мг", model.Substitutions.TriggerText);
        Assert.All(model.Substitutions.Rows, row => Assert.Equal("", row.TriggerContext));
        Assert.Equal("12 490 ₸", model.Substitutions.Rows[0].Price);
        Assert.Equal("Вознаграждение +350 ₸", model.Substitutions.Rows[0].Bonus);
        Assert.True(model.Substitutions.Rows[0].HasBonus);
        Assert.Equal("Почему: ", model.Substitutions.Rows[0].DetailLabel);
    }

    [Fact]
    public void Build_LabelsEachRowWhenSectionHasDifferentTriggers()
    {
        var model = RecommendationPopupModelBuilder.Build(new[]
        {
            Recommendation("crosssell", "Допродажа 1", "Товар A", 700),
            Recommendation("crosssell", "Допродажа 2", "Товар B", 800),
        });

        Assert.False(model.CrossSells.HasSharedTrigger);
        Assert.Equal("К товару: Товар A · 20 мг", model.CrossSells.Rows[0].TriggerContext);
        Assert.Equal("К товару: Товар B · 20 мг", model.CrossSells.Rows[1].TriggerContext);
        Assert.Equal("Скажите: ", model.CrossSells.Rows[0].DetailLabel);
    }

    [Fact]
    public void Build_OnlyPositiveBonusMakesItsOwnOfferRewarded()
    {
        var withoutBonus = Recommendation("substitution", "Аналог без бонуса", "Товар", 900);
        withoutBonus.Bonus = 0;
        var withBonus = Recommendation("substitution", "Аналог с бонусом", "Товар", 1_200);
        withBonus.Bonus = 400;
        var crossSellWithoutBonus = Recommendation("crosssell", "Допродажа без бонуса", "Товар", 500);
        crossSellWithoutBonus.Bonus = 0;

        var model = RecommendationPopupModelBuilder.Build(new[]
        {
            withoutBonus, withBonus, crossSellWithoutBonus,
        });

        Assert.False(model.Substitutions.Rows[0].HasBonus);
        Assert.Equal("Без вознаграждения", model.Substitutions.Rows[0].Bonus);
        Assert.True(model.Substitutions.Rows[1].HasBonus);
        Assert.Equal("Вознаграждение +400 ₸", model.Substitutions.Rows[1].Bonus);
        Assert.False(model.CrossSells.Rows[0].HasBonus);
        Assert.Equal("Без вознаграждения", model.CrossSells.Rows[0].Bonus);
    }

    [Fact]
    public void Build_DoesNotTurnMissingInventoryIntoZero()
    {
        var recommendation = Recommendation("substitution", "Аналог", "Товар", 900);

        var row = RecommendationPopupModelBuilder.Build(new[] { recommendation })
            .Substitutions.Rows.Single();

        Assert.Equal("Остаток не подтверждён", row.Stock);
        Assert.False(row.HasConfirmedStock);
        Assert.DoesNotContain("0", row.Stock);
    }

    [Fact]
    public void Build_ShowsPharmacyNetworkAndWarehouseBalanceWithTimestamp()
    {
        var recommendation = Recommendation("substitution", "Аналог", "Товар", 900);
        recommendation.Balance = new ProductBalance
        {
            PharmacyAvailableQty = 4,
            NetworkAvailableQty = 18.5m,
            WarehouseAvailableQty = 120,
            ObservedAt = new DateTimeOffset(2026, 10, 19, 10, 30, 0, TimeSpan.Zero),
            Status = "confirmed",
            Source = "warehouse",
        };

        var row = RecommendationPopupModelBuilder.Build(new[] { recommendation })
            .Substitutions.Rows.Single();

        Assert.Contains("аптека 4", row.Stock);
        Assert.Contains("сеть 18.5", row.Stock);
        Assert.Contains("склад 120", row.Stock);
        Assert.True(row.HasConfirmedStock);
    }

    [Theory]
    [InlineData(1)]
    [InlineData(2)]
    [InlineData(3)]
    [InlineData(5)]
    public void Build_KeepsExactOfferCountForEachSection(int count)
    {
        var source = Enumerable.Range(1, count)
            .Select(i => Recommendation("substitution", $"Замена {i}", "Товар", 1_000 + i))
            .Concat(Enumerable.Range(1, count)
                .Select(i => Recommendation("crosssell", $"Допродажа {i}", "Товар", 2_000 + i)));

        var model = RecommendationPopupModelBuilder.Build(source);

        Assert.Equal(count, model.Substitutions.Rows.Count);
        Assert.Equal(count, model.CrossSells.Rows.Count);
        Assert.Equal(count * 2, model.TotalCount);
    }

    [Fact]
    public void Build_LeadsWithTheLargestBonusAndShortBadges()
    {
        var paid = Recommendation("substitution", "С бонусом", "Исходный", 2_890);
        paid.Bonus = 200;
        var small = Recommendation("crosssell", "Малый бонус", "Исходный", 1_650);
        small.Bonus = 40;
        var unpaid = Recommendation("substitution", "Без бонуса", "Исходный", 1_420);
        unpaid.Bonus = 0;

        var model = RecommendationPopupModelBuilder.Build(new[] { paid, unpaid, small });

        Assert.Equal(200, model.MaxBonus);
        Assert.Equal("Ваш бонус до +200 ₸", model.Headline);
        Assert.Equal("+200 ₸", model.Substitutions.Rows[0].BonusBadge);
        Assert.Equal("без бонуса", model.Substitutions.Rows[1].BonusBadge);
        Assert.Equal(0, model.Substitutions.Rows[1].BonusAmount);
        Assert.Equal("+40 ₸", model.CrossSells.Rows[0].BonusBadge);
    }

    [Fact]
    public void Build_HeadlineForOneBonusAndForNoBonus()
    {
        var one = Recommendation("substitution", "Один", "Исходный", 1_000);
        one.Bonus = 1_250;
        Assert.Equal("Ваш бонус +1 250 ₸", RecommendationPopupModelBuilder.Build(new[] { one }).Headline);

        var none = Recommendation("crosssell", "Без", "Исходный", 1_000);
        none.Bonus = 0;
        var noBonus = RecommendationPopupModelBuilder.Build(new[] { none });
        Assert.Equal(0, noBonus.MaxBonus);
        Assert.Equal("Предложите покупателю", noBonus.Headline);
        Assert.Equal("Предложите покупателю", RecommendationPopupModelBuilder.Build(null).Headline);
    }

    [Fact]
    public void Build_ShowsServerGoalWithItsBonusOnlyWhenPresent()
    {
        var withGoal = Recommendation("substitution", "Цель", "Исходный", 1_000);
        withGoal.GoalText = "цель «3/10 Тантум Верде»";
        withGoal.GoalBonus = 2_000;
        var goalOnly = Recommendation("substitution", "Без суммы", "Исходный", 1_000);
        goalOnly.GoalText = "  цель «0/5»  ";
        var noGoal = Recommendation("substitution", "Нет цели", "Исходный", 1_000);
        noGoal.GoalBonus = 500;

        var rows = RecommendationPopupModelBuilder.Build(new[] { withGoal, goalOnly, noGoal }).Substitutions.Rows;

        Assert.Equal("Цель «3/10 Тантум Верде» · +2 000 ₸ за выполнение", rows[0].Goal);
        Assert.True(rows[0].HasGoal);
        Assert.Equal("Цель «0/5»", rows[1].Goal);
        Assert.Equal("", rows[2].Goal);
        Assert.False(rows[2].HasGoal);
    }

    private static Recommendation Recommendation(string kind, string name, string trigger, int price) => new()
    {
        Kind = kind,
        RecommendName = name,
        RecommendVendor = "INCAR",
        RecommendVolume = "30 табл.",
        RecommendPrice = price,
        Bonus = 350,
        TriggerName = trigger,
        TriggerVolume = "20 мг",
        Script = "Короткий аргумент",
    };
}
