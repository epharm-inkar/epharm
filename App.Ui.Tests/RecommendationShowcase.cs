using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using CustomerDisplay;
using CustomerDisplay.Models.Posm;

/// <summary>
/// Renders the real pharmacist RecommendationWindow with realistic cashier scenarios so every
/// visual change has comparable before/after pictures (owner's rule, INK-141). Product names and
/// bonuses follow the November promo matrix; prices and scripts are illustrative.
/// Run alone with <c>--recommendation-showcase</c>; the regression run renders them too.
/// </summary>
internal static partial class Program
{
    private static readonly DateTimeOffset ShowcaseObserved = new(2026, 11, 24, 6, 40, 0, TimeSpan.Zero);

    internal static readonly (string Name, Func<List<Recommendation>> Offers)[] ShowcaseScenarios =
    {
        ("showcase-1-substitutions-and-cross-sell", () => new()
        {
            ShowcaseOffer("1", "substitution", "Герветин спрей 0,15%", "30 мл",
                "Тантум Верде спрей 0,15%", "Angelini", "30 мл", 2890, 200,
                "Тот же бензидамин 0,15 %, оригинальный препарат. Снимает боль в горле на 1,5-3 часа.",
                pharmacyQty: 6, goal: "цель «3/10 Тантум Верде»", goalBonus: 2000),
            ShowcaseOffer("2", "substitution", "Герветин спрей 0,15%", "30 мл",
                "Лоридамин спрей 0,15%", "Lekhim", "30 мл", 1650, 40,
                "Тот же бензидамин и та же дозировка, дешевле на треть.", pharmacyQty: 11),
            ShowcaseOffer("3", "substitution", "Герветин спрей 0,15%", "30 мл",
                "Ингасепт спрей 0,15%", "Карагандинский ФК", "30 мл", 1420, 0,
                "Отечественный аналог с тем же действующим веществом."),
            ShowcaseOffer("4", "crosssell", "Герветин спрей 0,15%", "30 мл",
                "Септаназал спрей назальный", "KRKA", "10 мл", 2350, 120,
                "При боли в горле часто заложен нос: спрей снимает отёк и увлажняет.", pharmacyQty: 4),
        }),
        ("showcase-2-cross-sell-only", () => new()
        {
            ShowcaseOffer("5", "crosssell", "Амоксиклав 625 мг", "№14",
                "Лактоцил капсулы", "Фармстандарт", "№20", 3150, 150,
                "С антибиотиком стоит принимать пробиотик: меньше риск расстройства кишечника.",
                pharmacyQty: 9),
            ShowcaseOffer("6", "crosssell", "Амоксиклав 625 мг", "№14",
                "Максилак капсулы", "Genexo", "№10", 4280, 0,
                "Пробиотик на курс антибиотика, одна капсула в день."),
        }),
        ("showcase-3-five-and-five-without-bonus", () => Enumerable.Range(1, 5)
            .Select(i => ShowcaseOffer("S" + i, "substitution", "Ибупрофен 400 мг", "№50",
                $"Ибуфен 400 мг, вариант {i}", "Polfa", "№" + (10 * i), 900 + 140 * i, i == 1 ? 90 : 0,
                "Тот же ибупрофен 400 мг."))
            .Concat(Enumerable.Range(1, 5).Select(i => ShowcaseOffer("C" + i, "crosssell", "Ибупрофен 400 мг", "№50",
                $"Мильгамма композитум, вариант {i}", "Wörwag", "№30", 4200 + 100 * i, i == 2 ? 180 : 0,
                "При боли в спине витамины группы B ускоряют восстановление.")))
            .ToList()),
        // Two products in one receipt: one tab each, the last scanned (Нурофен) is open.
        ("showcase-4-two-products-tabs", () => new()
        {
            ShowcaseOffer("T1", "substitution", "Терафлю от гриппа лимон", "№10",
                "Боксгрипал порошок", "Вива Фарм", "№8", 2290, 250,
                "Тот же парацетамол с фенилэфрином, дешевле на 600 ₸.", pharmacyQty: 7),
            ShowcaseOffer("T2", "substitution", "Терафлю от гриппа лимон", "№10",
                "Колдакт Флю Плюс", "Ranbaxy", "№10", 2540, 150,
                "Те же компоненты от простуды, капсулы на 12 часов."),
            ShowcaseOffer("N1", "substitution", "Нурофен Экспресс 200 мг", "№20",
                "Ибуфен 200 мг", "Polfa", "№50", 1190, 200,
                "Тот же ибупрофен 200 мг, упаковка в 2,5 раза больше.", pharmacyQty: 12),
            ShowcaseOffer("N2", "crosssell", "Нурофен Экспресс 200 мг", "№20",
                "Магнетаб таблетки", "Рубикон", "№90", 3990, 350,
                "При головной боли от стресса магний помогает снять напряжение.", pharmacyQty: 3),
        }),
    };

    private static Recommendation ShowcaseOffer(
        string id, string kind, string trigger, string triggerVolume,
        string name, string vendor, string volume, int price, int bonus, string script,
        decimal? pharmacyQty = null, string? goal = null, int? goalBonus = null) => new()
    {
        EventId = "SHOWCASE-EVENT-" + id, RuleId = "SHOWCASE-RULE-" + id, Kind = kind,
        TriggerIpartId = "SHOWCASE-" + trigger, TriggerName = trigger, TriggerVolume = triggerVolume,
        RecommendSku = "SHOWCASE-SKU-" + id, RecommendName = name, RecommendVendor = vendor,
        RecommendVolume = volume, RecommendPrice = price, Bonus = bonus, Script = script,
        GoalText = goal, GoalBonus = goalBonus,
        Balance = pharmacyQty.HasValue
            ? new ProductBalance { Status = "confirmed", PharmacyAvailableQty = pharmacyQty, ObservedAt = ShowcaseObserved }
            : null,
    };

    /// <summary>Shows each scenario in a real window, saves its PNG and returns the file names.</summary>
    internal static async Task<List<string>> RenderShowcase(int autoCloseSec = 30)
    {
        var files = new List<string>();
        foreach (var (name, offers) in ShowcaseScenarios)
        {
            var window = new RecommendationWindow(offers(), autoCloseSec, _screen, "Нұрсая Әбдіқалық");
            if (name.Contains("two-products")) window.PreferTrigger(null, "SHOWCASE-Нурофен Экспресс 200 мг", null);
            RecommendationWindows.Add(window);
            window.Show();
            await Idle();
            SaveOwnWindow(window, name);
            files.Add(name + ".png");
            window.Close();
        }
        return files;
    }

    private static async Task RecommendationShowcase(Dictionary<string, bool> proof)
    {
        var files = await RenderShowcase();
        foreach (var file in files)
            Check(proof, file + "-rendered", new FileInfo(Path.Combine(Output, file)).Length > 1000);
    }

    private static int RunShowcaseOnly(Application application)
    {
        application.Startup += async (_, _) =>
        {
            var code = 0;
            try
            {
                _screen = System.Windows.Forms.Screen.PrimaryScreen ?? throw new InvalidOperationException("No primary display.");
                foreach (var file in await RenderShowcase()) Console.WriteLine(Path.Combine(Output, file));
            }
            catch (Exception error)
            {
                Console.Error.WriteLine(error);
                code = 1;
            }
            finally
            {
                foreach (var window in RecommendationWindows) if (window.IsVisible) window.Close();
                application.Shutdown(code);
            }
        };
        return application.Run();
    }
}
