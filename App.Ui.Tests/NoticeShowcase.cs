using System;
using System.Drawing;
using System.IO;
using System.Windows;
using System.Windows.Threading;
using CustomerDisplay;

/// <summary>
/// «ePharm на технических работах» (1.0.95): the notice from the icon, «ePharm снова работает» in the corner and the
/// new icon by the clock next to the current one, for the owner's before/after review. Run with <c>--notice-showcase</c>.
/// </summary>
internal static partial class Program
{
    private static int RunNoticeShowcaseOnly(Application application)
    {
        application.Startup += async (_, _) =>
        {
            var code = 0;
            try
            {
                _screen = System.Windows.Forms.Screen.PrimaryScreen ?? throw new InvalidOperationException("No primary display.");
                var works = new EpharmMaintenanceWindow(null, null, _screen);
                works.Show();
                await Dispatcher.CurrentDispatcher.InvokeAsync(() => { }, DispatcherPriority.ApplicationIdle);
                SaveOwnWindow(works, "notice-1-maintenance-window");
                Console.WriteLine(Path.Combine(Output, "notice-1-maintenance-window.png"));
                works.Close();

                var toast = new EpharmToastWindow("ePharm снова работает",
                    "Айгерим, нажмите, чтобы открыть свой кабинет ePharm. Подсказки по чеку снова будут появляться здесь.", _screen,
                    TimeSpan.FromMinutes(5));
                toast.Show();
                await System.Threading.Tasks.Task.Delay(400);
                await Dispatcher.CurrentDispatcher.InvokeAsync(() => { }, DispatcherPriority.ApplicationIdle);
                SaveOwnWindow(toast, "notice-2-live-toast");
                Console.WriteLine(Path.Combine(Output, "notice-2-live-toast.png"));
                toast.Close();

                var welcome = new EpharmToastWindow(MainWindow.Greeting(new DateTime(2026, 11, 26, 8, 5, 0)) + ", Нұрсая Әбдіқалық!",
                    "Вход в ePharm выполнен. Подсказки по заменам будут появляться здесь. Нажмите, чтобы открыть свой кабинет. Хорошего дня!",
                    _screen, TimeSpan.FromMinutes(5));
                welcome.Show();
                await System.Threading.Tasks.Task.Delay(400);
                await Dispatcher.CurrentDispatcher.InvokeAsync(() => { }, DispatcherPriority.ApplicationIdle);
                SaveOwnWindow(welcome, "notice-4-welcome");
                Console.WriteLine(Path.Combine(Output, "notice-4-welcome.png"));
                welcome.Close();

                SaveIcons(Path.Combine(Output, "notice-3-icons.png"));
                Console.WriteLine(Path.Combine(Output, "notice-3-icons.png"));
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

    /// <summary>The current «i» (1.0.92) and the new mark in three states, each enlarged and at tray size.</summary>
    private static void SaveIcons(string path)
    {
        var icons = new (string Label, Icon Big, Icon Small)[]
        {
            ("сейчас (1.0.92)", SystemIcons.Information, SystemIcons.Information),
            ("новый значок", EpharmMark.TrayIcon(EpharmMark.Dot.None, 112), EpharmMark.TrayIcon(EpharmMark.Dot.None, 16)),
            ("профиль не заполнен", EpharmMark.TrayIcon(EpharmMark.Dot.Alert, 112), EpharmMark.TrayIcon(EpharmMark.Dot.Alert, 16)),
            ("технические работы", EpharmMark.TrayIcon(EpharmMark.Dot.Paused, 112), EpharmMark.TrayIcon(EpharmMark.Dot.Paused, 16)),
        };
        const int cell = 230, big = 112;
        using var sheet = new Bitmap(cell * icons.Length, 230);
        using var g = Graphics.FromImage(sheet);
        g.Clear(Color.White);
        g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.HighQualityBicubic;
        g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;
        using var font = new Font("Segoe UI", 12);
        using var ink = new SolidBrush(Color.FromArgb(0x11, 0x2B, 0x33));
        using var taskbar = new SolidBrush(Color.FromArgb(0x20, 0x20, 0x20));
        for (var i = 0; i < icons.Length; i++)
        {
            using var bitmap = icons[i].Big.ToBitmap();
            using var small = icons[i].Small.ToBitmap();
            var x = i * cell;
            g.DrawImage(bitmap, x + (cell - big) / 2, 16, big, big);
            g.FillRectangle(taskbar, x + cell / 2 - 30, 140, 60, 40);
            g.DrawImage(small, x + cell / 2 - 8, 152, 16, 16);
            var size = g.MeasureString(icons[i].Label, font);
            g.DrawString(icons[i].Label, font, ink, x + (cell - size.Width) / 2, 194);
        }
        sheet.Save(path, System.Drawing.Imaging.ImageFormat.Png);
    }
}
