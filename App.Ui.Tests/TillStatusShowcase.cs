using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;
using System.Windows;
using CustomerDisplay;
using CustomerDisplay.Services;


/// <summary>
/// «Состояние ePharm» rendered in the real window for the states pharmacists meet, for review before release
/// (dotnet run ... -- --till-status-showcase --output=...). Synthetic data, no network.
/// </summary>
internal static partial class Program
{
    private static readonly DateTimeOffset StatusNow = new(2026, 11, 24, 10, 30, 0, TimeSpan.Zero);

    private static readonly TillNotice MaintenanceNotice = new("2026-11-24-registration-closed",
        "Регистрация в ePharm временно закрыта: мы обновляем систему. Регистрироваться сейчас не нужно. Когда откроем, сообщим здесь и через МАС.",
        "ePharm-да тіркелу уақытша жабық: жүйені жаңартып жатырмыз. Қазір тіркелудің қажеті жоқ. Ашқанда, осы жерде және МАС арқылы хабарлаймыз.");

    private static TillStatusSnapshot StatusBase() => new(
        Now: StatusNow, Version: "1.0.92", PharmacyName: "Аспект-траст, Алматы, Бузурбаева 23",
        PharmacyId: "sloc_01KSAHYDSE7XBSG1YKFJQHX8F1", DeviceId: "KASSA2",
        ServerAcceptedAt: StatusNow.AddSeconds(-35), ServerFailingSince: null, StandardNEnabled: true,
        StandardNReadAt: StatusNow.AddSeconds(-4), StandardNFailingSince: null, SellerName: "Иванова Айгерим",
        StaffLoginOpen: false, StaffAuthenticated: false, StaffName: null, Notice: null, LocalOffset: TimeSpan.FromHours(5));

    internal static readonly (string Name, bool Kazakh, Func<TillStatusSnapshot> State)[] TillStatusScenarios =
    {
        ("status-01-registration-closed-notice", false, () => StatusBase() with { Notice = MaintenanceNotice }),
        ("status-02-registration-closed-notice-kk", true, () => StatusBase() with { Notice = MaintenanceNotice }),
        ("status-03-second-till-no-database", false, () => StatusBase() with
            { StandardNReadAt = StatusNow.AddMinutes(-42), StandardNFailingSince = StatusNow.AddMinutes(-41), SellerName = null }),
        ("status-04-no-server-link", false, () => StatusBase() with
            { ServerAcceptedAt = StatusNow.AddMinutes(-26), ServerFailingSince = StatusNow.AddMinutes(-25), StaffLoginOpen = null }),
        ("status-05-signed-in-all-good", false, () => StatusBase() with { StaffLoginOpen = true, StaffAuthenticated = true }),
        ("status-06-login-open-not-signed-in", false, () => StatusBase() with { StaffLoginOpen = true, StaffAuthenticated = false }),
    };

    internal static async Task<List<string>> RenderTillStatusShowcase()
    {
        var files = new List<string>();
        foreach (var (name, kazakh, state) in TillStatusScenarios)
        {
            var window = new TillStatusWindow(state, kazakh, _screen);
            window.Show();
            await Idle();
            SaveOwnWindow(window, name);
            files.Add(name + ".png");
            window.Close();
        }
        return files;
    }

    private static int RunTillStatusShowcaseOnly(Application application)
    {
        application.Startup += async (_, _) =>
        {
            var code = 0;
            try
            {
                _screen = System.Windows.Forms.Screen.PrimaryScreen ?? throw new InvalidOperationException("No primary display.");
                foreach (var file in await RenderTillStatusShowcase()) Console.WriteLine(Path.Combine(Output, file));
            }
            catch (Exception error)
            {
                Console.Error.WriteLine(error);
                code = 1;
            }
            finally
            {
                application.Shutdown(code);
            }
        };
        return application.Run();
    }
}
