using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using CustomerDisplay;

/// <summary>
/// Renders the ePharm tray personal window (StaffSelfWindow) in both states — profile not yet filled (red dot)
/// and filled (green) — so the owner can review the look. Sample data only; wiring to the server is a later step.
/// Run alone with <c>--staff-self-showcase</c>.
/// </summary>
internal static partial class Program
{
    private static readonly (string Name, StaffSelfModel Model)[] StaffSelfScenarios =
    {
        ("staff-self-1-profile-incomplete", new StaffSelfModel
        {
            FullName = "Нұрсая Әбдіқалық Серікқызы",
            Iin = "920214400511",
            Pharmacy = "Аптека со склада, Абая 103",
            EarnedMonthKzt = 18650,
            BalanceKzt = 18650,
            PhoneBound = false,
            PasswordSet = false,
            CabinetUrl = "https://epharm.inkar.kz/learn?t=onetime-demo-token",
            Recent = new List<StaffSelfReward>
            {
                new("substitution", "Тантум Верде спрей 0,15%", 200, new DateTimeOffset(2026, 11, 24, 18, 40, 0, TimeSpan.FromHours(5))),
                new("crosssell", "Лактоцил капсулы №20", 150, new DateTimeOffset(2026, 11, 24, 15, 10, 0, TimeSpan.FromHours(5))),
                new("substitution", "Магнетаб таблетки №90", 350, new DateTimeOffset(2026, 11, 23, 12, 2, 0, TimeSpan.FromHours(5))),
            },
        }),
        ("staff-self-2-profile-complete", new StaffSelfModel
        {
            FullName = "Әлихан Тұрсынов",
            Iin = "880101300222",
            Pharmacy = "Аптека со склада, Бузурбаева 23",
            EarnedMonthKzt = 41200,
            BalanceKzt = 12050,
            PhoneBound = true,
            PasswordSet = true,
            CabinetUrl = "https://epharm.inkar.kz/learn?t=onetime-demo-token-2",
            Recent = new List<StaffSelfReward>
            {
                new("substitution", "Лоридамин спрей 0,15%", 40, new DateTimeOffset(2026, 11, 24, 19, 5, 0, TimeSpan.FromHours(5))),
                new("substitution", "Венолекс 450 мг №60", 300, new DateTimeOffset(2026, 11, 24, 17, 22, 0, TimeSpan.FromHours(5))),
            },
        }),
        ("staff-self-3-not-recognised", new StaffSelfModel
        {
            Notice = "Не нашли вас в справочнике Стандарт-Н: попросите МАС заполнить ИИН в вашей карточке пользователя",
        }),
    };

    private static int RunStaffSelfShowcaseOnly(Application application)
    {
        application.Startup += async (_, _) =>
        {
            var code = 0;
            try
            {
                _screen = System.Windows.Forms.Screen.PrimaryScreen ?? throw new InvalidOperationException("No primary display.");
                foreach (var (name, model) in StaffSelfScenarios)
                {
                    var window = new StaffSelfWindow(model, _screen);
                    window.Show();
                    await Dispatcher.CurrentDispatcher.InvokeAsync(() => { }, DispatcherPriority.ApplicationIdle);
                    SaveOwnWindow(window, name);
                    Console.WriteLine(Path.Combine(Output, name + ".png"));
                    window.Close();
                }
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
