using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;

namespace CustomerDisplay.Models.Posm
{
    /// <summary>
    /// Presentation model for the pharmacist popup. Keeping grouping and formatting outside WPF
    /// makes the 5+5 contract testable on every CI runner, including Linux and macOS.
    /// </summary>
    public sealed class RecommendationPopupModel
    {
        public RecommendationPopupSection Substitutions { get; init; } = new();
        public RecommendationPopupSection CrossSells { get; init; } = new();
        public int TotalCount => Substitutions.Rows.Count + CrossSells.Rows.Count;

        /// <summary>Largest single-offer bonus in the popup; 0 when no offer pays the pharmacist.</summary>
        public int MaxBonus => Substitutions.Rows.Concat(CrossSells.Rows)
            .Select(row => row.BonusAmount).DefaultIfEmpty(0).Max();

        public int BonusOfferCount => Substitutions.Rows.Concat(CrossSells.Rows).Count(row => row.HasBonus);

        /// <summary>Header line: what the pharmacist earns, so the reward is read first.</summary>
        public string Headline => MaxBonus <= 0
            ? "Предложите покупателю"
            : (BonusOfferCount > 1 ? "Ваш бонус до +" : "Ваш бонус +") +
              RecommendationPopupModelBuilder.Money(MaxBonus);
    }

    public sealed class RecommendationPopupSection
    {
        public string TriggerText { get; init; } = "";
        public bool HasSharedTrigger => !string.IsNullOrWhiteSpace(TriggerText);
        public IReadOnlyList<RecommendationPopupRow> Rows { get; init; } = Array.Empty<RecommendationPopupRow>();
    }

    public sealed class RecommendationPopupRow
    {
        public string Number { get; init; } = "";
        public string Name { get; init; } = "";
        public string Meta { get; init; } = "";
        public string Detail { get; init; } = "";
        public string DetailLabel { get; init; } = "";
        public bool HasDetail => !string.IsNullOrWhiteSpace(Detail);
        public string TriggerContext { get; init; } = "";
        public string Price { get; init; } = "—";
        public string Bonus { get; init; } = "Без вознаграждения";
        /// <summary>Short badge text: «+200 ₸» or «без бонуса».</summary>
        public string BonusBadge { get; init; } = "без бонуса";
        public int BonusAmount { get; init; }
        public bool HasBonus { get; init; }
        /// <summary>Personal goal progress from the backend, e.g. «Цель «3/10 …» · +2 000 ₸ за выполнение».</summary>
        public string Goal { get; init; } = "";
        public bool HasGoal => !string.IsNullOrWhiteSpace(Goal);
        public string PartnerLabel { get; init; } = "";
        public string Stock { get; init; } = "Остаток не подтверждён";
        public bool HasConfirmedStock { get; init; }
    }

    public static class RecommendationPopupModelBuilder
    {
        public const int MaxPerKind = 5;

        public static RecommendationPopupModel Build(IEnumerable<Recommendation>? recommendations)
        {
            var all = recommendations?.Where(r => r != null).ToList() ?? new List<Recommendation>();
            return new RecommendationPopupModel
            {
                Substitutions = BuildSection(all.Where(r => r.IsSubstitution).Take(MaxPerKind), true),
                CrossSells = BuildSection(all.Where(r => !r.IsSubstitution).Take(MaxPerKind), false),
            };
        }

        private static RecommendationPopupSection BuildSection(
            IEnumerable<Recommendation> source,
            bool substitution)
        {
            var recommendations = source.ToList();
            var triggers = recommendations
                .Select(TriggerName)
                .Where(value => !string.IsNullOrWhiteSpace(value))
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToList();
            var sharedTrigger = triggers.Count == 1 ? triggers[0] : "";

            var rows = recommendations.Select((recommendation, index) => new RecommendationPopupRow
            {
                Number = (index + 1).ToString(CultureInfo.InvariantCulture),
                Name = recommendation.RecommendName,
                Meta = JoinDot(recommendation.RecommendVendor, recommendation.RecommendVolume),
                Detail = FirstNonBlank(
                    recommendation.Script,
                    recommendation.Advantages?.FirstOrDefault(value => !string.IsNullOrWhiteSpace(value))),
                DetailLabel = substitution ? "Почему: " : "Скажите: ",
                TriggerContext = string.IsNullOrWhiteSpace(sharedTrigger)
                    ? TriggerPrefix(substitution) + TriggerName(recommendation)
                    : "",
                Price = Money(recommendation.RecommendPrice),
                Bonus = recommendation.Bonus > 0
                    ? $"Вознаграждение +{Money(recommendation.Bonus)}"
                    : "Без вознаграждения",
                BonusBadge = recommendation.Bonus > 0
                    ? "+" + Money(recommendation.Bonus)
                    : "без бонуса",
                BonusAmount = Math.Max(0, recommendation.Bonus),
                HasBonus = recommendation.Bonus > 0,
                Goal = GoalLine(recommendation.GoalText, recommendation.GoalBonus),
                PartnerLabel = recommendation.PartnerLabel?.Trim() ?? "",
                Stock = StockText(recommendation.Balance),
                HasConfirmedStock = recommendation.Balance != null &&
                    recommendation.Balance.Status == "confirmed" && recommendation.Balance.ObservedAt.HasValue &&
                    (recommendation.Balance.PharmacyAvailableQty.HasValue || recommendation.Balance.NetworkAvailableQty.HasValue ||
                     recommendation.Balance.WarehouseAvailableQty.HasValue),
            }).ToList();

            return new RecommendationPopupSection { TriggerText = sharedTrigger, Rows = rows };
        }

        public static string GoalLine(string? goalText, int? goalBonus)
        {
            if (string.IsNullOrWhiteSpace(goalText)) return "";
            var text = goalText.Trim();
            text = char.ToUpperInvariant(text[0]) + text[1..];
            return goalBonus > 0 ? $"{text} · +{Money(goalBonus)} за выполнение" : text;
        }

        private static string TriggerPrefix(bool substitution) =>
            substitution ? "Вместо: " : "К товару: ";

        private static string TriggerName(Recommendation recommendation) =>
            JoinDot(recommendation.TriggerName, recommendation.TriggerVolume);

        private static string FirstNonBlank(params string?[] values) =>
            values.FirstOrDefault(value => !string.IsNullOrWhiteSpace(value))?.Trim() ?? "";

        public static string Money(int? value)
        {
            if (!value.HasValue) return "—";
            return value.Value.ToString("#,0", CultureInfo.InvariantCulture).Replace(",", " ") + " ₸";
        }

        public static string StockText(ProductBalance? balance)
        {
            if (balance == null) return "Остаток не подтверждён";
            var parts = new List<string>();
            if (balance.PharmacyAvailableQty.HasValue)
                parts.Add($"аптека {Qty(balance.PharmacyAvailableQty.Value)}");
            if (balance.NetworkAvailableQty.HasValue)
                parts.Add($"сеть {Qty(balance.NetworkAvailableQty.Value)}");
            if (balance.WarehouseAvailableQty.HasValue)
                parts.Add($"склад {Qty(balance.WarehouseAvailableQty.Value)}");
            if (parts.Count == 0) return "Остаток не подтверждён";
            var prefix = balance.Status switch
            {
                "stale" => "Остаток устарел",
                "estimated" => "Расчётный остаток",
                "confirmed" when balance.ObservedAt.HasValue => "Остаток",
                _ => "Остаток не подтверждён",
            };
            var observed = balance.ObservedAt.HasValue
                ? $" · {balance.ObservedAt.Value.ToLocalTime():dd.MM HH:mm}"
                : "";
            return $"{prefix}: {string.Join(" · ", parts)}{observed}";
        }

        private static string Qty(decimal value) =>
            value.ToString("0.###", CultureInfo.InvariantCulture);

        private static string JoinDot(params string?[] values) =>
            string.Join(" · ", values.Where(value => !string.IsNullOrWhiteSpace(value)).Select(value => value!.Trim()));
    }
}
