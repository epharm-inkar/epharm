using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using CustomerDisplay;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Screen = System.Windows.Forms.Screen;

internal static partial class Program
{
    private static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, PropertyNameCaseInsensitive = true, WriteIndented = true };
    private static readonly List<CaseProof> Results = new();
    private static readonly List<RecommendationWindow> RecommendationWindows = new();
    private static string Output = Path.Combine(AppContext.BaseDirectory, "proof");
    private static bool _allowNoninteractive;
    private static bool _interactiveSession;
    private static Screen _screen = null!;
    // The till sign-in window is gone since 1.0.97, so are its 52 cases: only the recommendation popup is checked here.
    private const int ExpectedCases = 6;

    [STAThread]
    public static int Main(string[] args)
    {
        var showcaseOnly = false;
        var staffSelfShowcaseOnly = false;
        var noticeShowcaseOnly = false;
        foreach (var argument in args)
        {
            if (argument == "--allow-noninteractive") _allowNoninteractive = true;
            else if (argument == "--recommendation-showcase") showcaseOnly = true;
            else if (argument == "--staff-self-showcase") staffSelfShowcaseOnly = true;
            else if (argument == "--notice-showcase") noticeShowcaseOnly = true;
            else if (argument.StartsWith("--output=", StringComparison.Ordinal) && argument.Length > "--output=".Length)
                Output = Path.GetFullPath(argument["--output=".Length..]);
            else return 2;
        }
        Directory.CreateDirectory(Output);
        using var process = Process.GetCurrentProcess();
        _interactiveSession = Environment.UserInteractive && process.SessionId > 0;
        if (!_interactiveSession && !_allowNoninteractive)
        {
            WriteProof(false, "Interactive Windows desktop required; session 0 is refused.");
            return 2;
        }
        var application = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        if (showcaseOnly) return RunShowcaseOnly(application);
        if (staffSelfShowcaseOnly) return RunStaffSelfShowcaseOnly(application);
        if (noticeShowcaseOnly) return RunNoticeShowcaseOnly(application);
        var watchdog = new DispatcherTimer { Interval = TimeSpan.FromSeconds(90) };
        watchdog.Tick += (_, _) => {
            watchdog.Stop();
            Results.Add(new("watchdog", false, new(), "TimeoutException"));
            foreach (var window in RecommendationWindows) if (window.IsVisible) window.Close();
            WriteProof(false, "Ninety-second UI watchdog expired.");
            application.Shutdown(3);
        };
        application.Startup += async (_, _) => {
            watchdog.Start();
            try
            {
                _screen = Screen.PrimaryScreen ?? throw new InvalidOperationException("No primary display.");
                await Run("recommendation-empty-or-failed-refresh-closes-existing-window", RecommendationEmptyRefresh);
                await Run("recommendation-partial-cart-removal-keeps-other-offer-in-same-window", RecommendationPartialRemoval);
                await Run("recommendation-stock-refresh-renders-uncertainty-with-immutable-financial-quote", RecommendationStockRefresh);
                await Run("recommendation-cart-refresh-does-not-extend-ttl-but-fresh-response-does", RecommendationLifetime);
                await Run("recommendation-native-display-event-sees-new-current-rows", RecommendationDisplayUpdate);
                await Run("recommendation-showcase-renders-realistic-cashier-scenarios", RecommendationShowcase);
            }
            catch (Exception error)
            {
                Results.Add(new("harness-fatal", false, new(), error.GetType().Name));
            }
            finally
            {
                watchdog.Stop();
                foreach (var window in RecommendationWindows) if (window.IsVisible) window.Close();
                var success = Results.Count == ExpectedCases && Results.All(result => result.Passed);
                WriteProof(success, null);
                application.Shutdown(success ? 0 : 1);
            }
        };
        return application.Run();
    }

    private static async Task Run(string name, Func<Dictionary<string, bool>, Task> test)
    {
        var assertions = new Dictionary<string, bool>();
        try
        {
            await test(assertions);
            Results.Add(new(name, assertions.Count > 0 && assertions.Values.All(value => value), assertions, null));
        }
        catch (Exception error)
        {
            Results.Add(new(name, false, assertions, error.GetType().Name));
        }
        finally
        {
            foreach (var window in RecommendationWindows) if (window.IsVisible) window.Close();
        }
        WriteProof(false, "In progress");
    }


    private static async Task Idle() => await Dispatcher.CurrentDispatcher.InvokeAsync(() => { }, DispatcherPriority.ApplicationIdle);
    private static async Task Until(Func<bool> condition, int timeoutSeconds = 10)
    {
        var timeout = DateTimeOffset.UtcNow.AddSeconds(timeoutSeconds);
        while (!condition())
        {
            if (DateTimeOffset.UtcNow > timeout) throw new TimeoutException("Own UI test timed out.");
            await Task.Delay(15);
        }
        await Idle();
    }
    private static void Check(Dictionary<string, bool> proof, string name, bool passed)
    {
        proof[name] = passed;
        if (!passed) throw new InvalidOperationException("Assertion failed: " + name);
    }

    private static void SaveOwnWindow(Window window, string name)
    {
        window.UpdateLayout();
        var dpi = VisualTreeHelper.GetDpi(window);
        var bitmap = new RenderTargetBitmap(Math.Max(1, (int)Math.Ceiling(window.ActualWidth * dpi.DpiScaleX)),
            Math.Max(1, (int)Math.Ceiling(window.ActualHeight * dpi.DpiScaleY)), dpi.PixelsPerInchX, dpi.PixelsPerInchY, PixelFormats.Pbgra32);
        bitmap.Render(window);
        var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var file = File.Create(Path.Combine(Output, name + ".png")); encoder.Save(file);
    }


    private static Recommendation Offer(string id, int price = 1234, int bonus = 45) => new() {
        EventId = "DEV-EVENT-" + id, RuleId = "DEV-RULE-" + id, Kind = "substitution",
        TriggerIpartId = id, TriggerName = "DEV synthetic trigger " + id,
        RecommendSku = "DEV-SKU-" + id, RecommendName = "DEV synthetic offer " + id,
        RecommendPrice = price, Bonus = bonus,
        Balance = new ProductBalance { Status = "confirmed", PharmacyAvailableQty = 10, ObservedAt = DateTimeOffset.UtcNow },
    };
    private static RecommendationWindow RecommendationPopup(List<Recommendation> offers, int lifetime = 0, bool show = true)
    {
        var window = new RecommendationWindow(offers, lifetime, _screen);
        RecommendationWindows.Add(window);
        if (show) window.Show();
        return window;
    }
    private static IEnumerable<T> Visuals<T>(DependencyObject root) where T : DependencyObject
    {
        if (root is T match) yield return match;
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(root); index++)
            foreach (var child in Visuals<T>(VisualTreeHelper.GetChild(root, index))) yield return child;
    }
    private static string RowText(RecommendationWindow window, string name) =>
        Visuals<TextBlock>(window).Single(text => text.Name == name && text.IsVisible).Text;
    private static async Task RecommendationEmptyRefresh(Dictionary<string, bool> proof)
    {
        foreach (var failed in new[] { false, true }) {
            var window = RecommendationPopup([Offer("A")]); await Idle();
            Check(proof, (failed ? "failed" : "empty") + "-starts-with-real-visible-popup", window.IsVisible && window.Recommendations.Count == 1);
            var refreshed = RecommendationVisibility.Reconcile(window.Recommendations, failed ? null : Array.Empty<Recommendation>(), _ => true);
            window.UpdateRecommendations(refreshed); await Idle();
            Check(proof, (failed ? "failed" : "empty") + "-authoritative-refresh-closes-old-offer", !window.IsVisible && window.Recommendations.Count == 0);
        }
    }
    private static async Task RecommendationPartialRemoval(Dictionary<string, bool> proof)
    {
        var a = Offer("A"); var b = Offer("B"); var window = RecommendationPopup([a, b]); await Idle();
        var handle = new System.Windows.Interop.WindowInteropHelper(window).Handle;
        var remaining = RecommendationVisibility.ForCart(window.Recommendations, offer => offer.TriggerIpartId == "B");
        window.UpdateRecommendations(remaining, freshResponse: false); await Idle();
        Check(proof, "partial-removal-keeps-same-native-window-and-same-offer", window.IsVisible &&
            new System.Windows.Interop.WindowInteropHelper(window).Handle == handle && window.Recommendations.Count == 1 && ReferenceEquals(window.Current, b));
        var reconciled = RecommendationVisibility.Reconcile(window.Recommendations, [Offer("B")], _ => true);
        window.UpdateRecommendations(reconciled); await Idle();
        Check(proof, "already-shown-but-still-open-offer-is-not-suppressed", window.IsVisible && ReferenceEquals(window.Current, b) &&
            RowText(window, "NameText") == b.RecommendName);
        SaveOwnWindow(window, "12-recommendation-partial-removal");
    }
    private static async Task RecommendationStockRefresh(Dictionary<string, bool> proof)
    {
        var original = Offer("A"); var window = RecommendationPopup([original]); await Idle();
        foreach (var status in new[] { "unknown", "estimated", "stale" }) {
            var updated = Offer("A", price: 9999, bonus: 999);
            updated.Balance = new ProductBalance { Status = status, PharmacyAvailableQty = 5,
                ObservedAt = status == "stale" ? DateTimeOffset.UtcNow.AddDays(-30) : null };
            window.UpdateRecommendations(RecommendationVisibility.Reconcile(window.Recommendations, [updated], _ => true)); await Idle();
            var prefix = status switch { "estimated" => "Расчётный остаток", "stale" => "Остаток устарел", _ => "Остаток не подтверждён" };
            Check(proof, status + "-stock-state-is-rendered-honestly", RowText(window, "StockText").StartsWith(prefix, StringComparison.Ordinal));
            Check(proof, status + "-refresh-keeps-original-financial-quote", window.Current.RecommendPrice == 1234 && window.Current.Bonus == 45 &&
                RowText(window, "PriceText") == "1 234 ₸" && RowText(window, "BonusText").Contains("45 ₸", StringComparison.Ordinal));
        }
        SaveOwnWindow(window, "13-recommendation-stale-stock");
    }
    private static async Task RecommendationLifetime(Dictionary<string, bool> proof)
    {
        var first = RecommendationPopup([Offer("A")], lifetime: 2); var firstElapsed = Stopwatch.StartNew();
        await Task.Delay(1300);
        first.UpdateRecommendations(RecommendationVisibility.ForCart(first.Recommendations, _ => true), freshResponse: false);
        await Until(() => !first.IsVisible);
        Check(proof, "cart-only-update-does-not-restart-original-two-second-ttl", firstElapsed.Elapsed < TimeSpan.FromMilliseconds(3000));
        var second = RecommendationPopup([Offer("B")], lifetime: 2);
        await Task.Delay(1300); second.UpdateRecommendations([Offer("B")], freshResponse: true);
        await Task.Delay(1100);
        Check(proof, "fresh-success-remains-visible-after-original-deadline", second.IsVisible);
        await Until(() => !second.IsVisible);
        Check(proof, "fresh-response-still-eventually-expires", !second.IsVisible);
    }
    private static async Task RecommendationDisplayUpdate(Dictionary<string, bool> proof)
    {
        var window = RecommendationPopup([Offer("A")], show: false);
        var displays = new List<string[]>();
        // Test the real native event and current rows. MainWindow's outbox wiring is not
        // instantiated here: its constructor initializes external runtime dependencies.
        window.Displayed += _ => displays.Add(window.Recommendations.Select(offer => offer.EventId).ToArray());
        window.Show(); await Until(() => displays.Count > 0);
        window.UpdateRecommendations([Offer("A"), Offer("B")]); await Until(() => displays.Count > 1);
        Check(proof, "native-update-reannounces-current-rows-not-initial-captured-list", displays[0].SequenceEqual(["DEV-EVENT-A"]) &&
            displays[1].SequenceEqual(["DEV-EVENT-A", "DEV-EVENT-B"]));
        Check(proof, "popup-does-not-request-keyboard-activation", !window.ShowActivated && !window.Focusable);
    }


    private static void WriteProof(bool passed, string? note)
    {
        using var process = Process.GetCurrentProcess();
        File.WriteAllText(Path.Combine(Output, "qa-proof.json"), JsonSerializer.Serialize(new {
            scope = "DEV synthetic actual RecommendationWindow; in-process UI; real production visibility helpers; no MainWindow startup, backend, Standard-N or device config",
            passed, note, createdAt = DateTimeOffset.UtcNow, interactiveSession = _interactiveSession,
            nonInteractiveOptIn = _allowNoninteractive, sessionId = process.SessionId, processId = process.Id, cases = Results,
        }, Json), Encoding.UTF8);
    }
    private sealed record CaseProof(string Name, bool Passed, Dictionary<string, bool> Assertions, string? FailureType);
}
