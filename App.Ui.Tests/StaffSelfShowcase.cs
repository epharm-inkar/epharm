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
            Iin = "920214•••••1",
            Pharmacy = "Аптека со склада, Абая 103",
            EarnedMonthKzt = 18650,
            BalanceKzt = 18650,
            PhoneBound = false,
            PasswordSet = false,
            TasksCount = 0,
            CabinetUrl = "https://epharm.inkar.kz/learn/enter#t=onetime-demo-token-onetime-demo-token-0001",
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
            Iin = "880101•••••2",
            Pharmacy = "Аптека со склада, Бузурбаева 23",
            EarnedMonthKzt = 41200,
            BalanceKzt = 12050,
            PhoneBound = true,
            PasswordSet = true,
            TasksCount = 2,
            CabinetUrl = "https://epharm.inkar.kz/learn/enter#t=onetime-demo-token-onetime-demo-token-0002",
            Recent = new List<StaffSelfReward>
            {
                new("substitution", "Лоридамин спрей 0,15%", 40, new DateTimeOffset(2026, 11, 24, 19, 5, 0, TimeSpan.FromHours(5))),
                new("substitution", "Венолекс 450 мг №60", 300, new DateTimeOffset(2026, 11, 24, 17, 22, 0, TimeSpan.FromHours(5))),
            },
        }),
        ("staff-self-4-phone-bound-no-password", new StaffSelfModel
        {
            FullName = "Айгерим Сапарова",
            Iin = "950305•••••4",
            Pharmacy = "Аптека со склада, Толе би 286",
            EarnedMonthKzt = 3200,
            BalanceKzt = 3200,
            PhoneBound = true,
            PasswordSet = false,
            CabinetUrl = "https://epharm.inkar.kz/learn/enter#t=onetime-demo-token-onetime-demo-token-0004",
            Recent = new List<StaffSelfReward>
            {
                new("substitution", "Нурофен Экспресс 200 мг №20", 200, new DateTimeOffset(2026, 11, 24, 16, 15, 0, TimeSpan.FromHours(5))),
            },
        }),
        ("staff-self-8-hq-tasks", new StaffSelfModel
        {
            FullName = "Айгерим Сапарова",
            Iin = "950305•••••4",
            Pharmacy = "Аптека со склада, Толе би 286",
            EarnedMonthKzt = 3200,
            BalanceKzt = 3200,
            PhoneBound = true,
            PasswordSet = true,
            TasksCount = 0,
            HqTasks = new List<StaffSelfTask>
            {
                new("Курс «Петровакс: Гриппол плюс»", "Откройте «Мой кабинет», раздел «Обучение», курс «Петровакс». 6 уроков и тест, около 20 минут.", 500, "new", 0, new DateTimeOffset(2026, 12, 5, 23, 59, 0, TimeSpan.FromHours(5))),
                new("Пожарная безопасность в аптеке", "Курс в разделе «Обучение».", null, "in_progress", 40, new DateTimeOffset(2026, 11, 30, 23, 59, 0, TimeSpan.FromHours(5))),
                new("Стандарт обслуживания покупателя", "", 300, "done", 100, null),
            },
        }),
        ("staff-self-3-not-recognised", new StaffSelfModel
        {
            Notice = "Не нашли вас в справочнике Стандарт-Н: попросите МАС заполнить ИИН в вашей карточке пользователя",
        }),
        // 1.0.101: a Standard-N user without an IIN types it and the full name, the office confirms.
        ("staff-self-9-iin-form", new StaffSelfModel
        {
            Identity = new StaffSelfIdentity("unknown", PersonKey: "18"),
            TasksCount = 0,
        }),
        ("staff-self-10-iin-sent", new StaffSelfModel
        {
            Identity = new StaffSelfIdentity("pending", "900115•••••3", "Бузурбаева Лаура Ермековна", PersonKey: "18"),
            TasksCount = 0,
        }),
        ("staff-self-11-iin-rejected", new StaffSelfModel
        {
            Identity = new StaffSelfIdentity("rejected", Reason: "ИИН не совпадает с документом, проверьте цифры", PersonKey: "18"),
            TasksCount = 0,
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
                // The open window refreshes itself (every 8 minutes, after «Сохранить»): rebuilding must keep working.
                var refreshed = new StaffSelfWindow(StaffSelfScenarios[0].Model, _screen);
                refreshed.Show();
                await Dispatcher.CurrentDispatcher.InvokeAsync(() => { }, DispatcherPriority.ApplicationIdle);
                refreshed.Update(StaffSelfScenarios[2].Model);
                refreshed.Update(StaffSelfScenarios[0].Model);
                refreshed.Update(StaffSelfScenarios[1].Model);
                await Dispatcher.CurrentDispatcher.InvokeAsync(() => { }, DispatcherPriority.ApplicationIdle);
                SaveOwnWindow(refreshed, "staff-self-5-after-refresh");
                Console.WriteLine(Path.Combine(Output, "staff-self-5-after-refresh.png"));
                refreshed.Close();
                // 1.0.99: the «?» panel became the «Что такое ePharm» page, opened from the entry under the header.
                var help = new EpharmPageWindow(
                    CustomerDisplay.Services.EpharmHelpContent.PageTitle, CustomerDisplay.Services.EpharmHelpContent.Lead,
                    CustomerDisplay.Services.EpharmHelpContent.Sections, _screen);
                help.Show();
                await Dispatcher.CurrentDispatcher.InvokeAsync(() => { }, DispatcherPriority.ApplicationIdle);
                SaveOwnWindow(help, "staff-self-6-what-is-epharm");
                Console.WriteLine(Path.Combine(Output, "staff-self-6-what-is-epharm.png"));
                help.Close();
                // 1.0.96: password already set, «Сменить пароль» opens two fields.
                var change = new StaffSelfWindow(StaffSelfScenarios[1].Model, _screen);
                change.Show();
                change.BeginPasswordChange();
                await Dispatcher.CurrentDispatcher.InvokeAsync(() => { }, DispatcherPriority.ApplicationIdle);
                SaveOwnWindow(change, "staff-self-7-change-password");
                Console.WriteLine(Path.Combine(Output, "staff-self-7-change-password.png"));
                change.Close();
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
