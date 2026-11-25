using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;

namespace CustomerDisplay;

/// <summary>
/// Till management without AnyDesk (1.0.96, Grigoriy 25.11): HQ presses «Диагностика» or «Перезапуск» in the admin
/// («Все кассы»); the till asks for a command about once a minute, does one of these two fixed actions and answers.
/// Nothing else can be asked: there is no «run anything». The pharmacist sees nothing.
/// </summary>
public partial class MainWindow
{
    private readonly DispatcherTimer _commandTimer = new();
    private int _commandRunning;

    private void InitRemoteCommands()
    {
        if (_epharm == null || _commandTimer.IsEnabled) return;
        _commandTimer.Interval = TimeSpan.FromSeconds(50 + Random.Shared.Next(0, 25));
        _commandTimer.Tick += async (_, _) => await PollRemoteCommandAsync();
        _commandTimer.Start();
        Closed += (_, _) => _commandTimer.Stop();
    }

    private async Task PollRemoteCommandAsync()
    {
        if (_epharm == null || Interlocked.Exchange(ref _commandRunning, 1) == 1) return;
        try
        {
            var command = await _epharm.NextCommandAsync();
            if (command == null) return;
            Log($"ePharm: команда из админки: {command.Action} ({command.Id})");
            switch (command.Action)
            {
                case "diagnostics":
                    await RunDiagnosticsAsync(command);
                    break;
                case "restart":
                    await RestartForCommandAsync(command);
                    break;
                default:
                    await _epharm.SendCommandResultAsync(command.Id, "failed", "Касса не знает такого действия: " + command.Action, null);
                    break;
            }
        }
        catch (Exception error)
        {
            Log("ePharm: команда из админки не выполнена: " + error.Message);
        }
        finally
        {
            Interlocked.Exchange(ref _commandRunning, 0);
        }
    }

    private async Task RunDiagnosticsAsync(TillCommandDto command)
    {
        var entries = DiagnosticsEntries();
        byte[] zip;
        try
        {
            zip = await Task.Run(() => TillDiagnostics.Build(entries));
        }
        catch (Exception error)
        {
            await _epharm!.SendCommandResultAsync(command.Id, "failed", "Не удалось собрать диагностику: " + error.Message, null);
            return;
        }
        var sent = await _epharm!.SendCommandResultAsync(command.Id, "done", $"Диагностика собрана: {zip.Length / 1024} КБ.", zip);
        Log(sent ? "ePharm: диагностика отправлена в админку" : "ePharm: диагностику отправить не удалось");
    }

    /// <summary>Answers first, then restarts the same way as after a key change: start a new copy and exit.</summary>
    private async Task RestartForCommandAsync(TillCommandDto command)
    {
        var executable = Environment.ProcessPath;
        if (string.IsNullOrWhiteSpace(executable))
        {
            await _epharm!.SendCommandResultAsync(command.Id, "failed", "Не удалось определить путь программы.", null);
            return;
        }
        await _epharm!.SendCommandResultAsync(command.Id, "done", "Программа ePharm перезапускается.", null);
        Log("ePharm: перезапуск по команде из админки");
        await Dispatcher.InvokeAsync(() =>
        {
            try
            {
                Process.Start(new ProcessStartInfo(executable) { UseShellExecute = true, WorkingDirectory = AppContext.BaseDirectory });
                Application.Current.Shutdown();
            }
            catch (Exception error)
            {
                Log("ePharm: перезапуск не удался: " + error.Message);
            }
        });
    }

    private List<TillDiagnosticsEntry> DiagnosticsEntries()
    {
        var state = new StringBuilder();
        state.AppendLine($"Собрано: {DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss zzz}");
        state.AppendLine($"Версия ePharm: {AppUpdater.CurrentVersion()}");
        state.AppendLine($"Windows: {Environment.OSVersion.VersionString}, 64 бит: {Environment.Is64BitOperatingSystem}, компьютер: {Environment.MachineName}");
        state.AppendLine($"Аптека: {_posmConfig?.PharmacyId}, касса: {_posmConfig?.ResolveDeviceId()}");
        state.AppendLine($"Вход (сервер): {StaffAuthenticationStatus}, Стандарт-Н: {StaffSourceStatus}");
        state.AppendLine($"Пользователь Стандарт-Н на кассе: {(_currentPharmacistId ?? "").Trim()}");
        state.AppendLine($"Технические работы: {(_noticeState?.Maintenance?.ToString() ?? "неизвестно")}");
        state.AppendLine($"Свободно на диске C: {FreeDiskMb("C:\\")} МБ, память процесса: {Environment.WorkingSet / (1024 * 1024)} МБ");
        state.AppendLine();
        state.AppendLine("Мониторы:");
        foreach (var screen in System.Windows.Forms.Screen.AllScreens)
            state.AppendLine($"  {screen.DeviceName} {screen.Bounds.Width}x{screen.Bounds.Height}{(screen.Primary ? " (основной)" : "")}");
        state.AppendLine();
        state.AppendLine("Процессы:");
        try
        {
            foreach (var name in Process.GetProcesses().Select(p => p.ProcessName).Distinct(StringComparer.OrdinalIgnoreCase).OrderBy(n => n))
                state.AppendLine("  " + name);
        }
        catch (Exception error) { state.AppendLine("  (не удалось: " + error.Message + ")"); }

        var receipts = _posmConfig?.ReceiptCaptureDir;
        return new List<TillDiagnosticsEntry>
        {
            new("state.txt", Text: state.ToString()),
            new("customerdisplay-log.txt", SourcePath: LogPath, MaxBytes: 3L * 1024 * 1024),
            new("receipts-listing.txt", Text: TillDiagnostics.Listing(receipts)),
            new("observed-groups.json", SourcePath: receipts == null ? null : System.IO.Path.Combine(receipts, "observed-groups", "groups.json"), MaxBytes: 512 * 1024),
            new("standardn-identity-diagnostics.txt", SourcePath: @"C:\Epharm\standardn-identity-diagnostics.txt", MaxBytes: 256 * 1024),
        };
    }

    private static long FreeDiskMb(string root)
    {
        try { return new System.IO.DriveInfo(root).AvailableFreeSpace / (1024 * 1024); }
        catch { return -1; }
    }
}
