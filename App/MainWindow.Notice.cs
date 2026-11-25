using System;
using System.IO;
using System.Threading.Tasks;
using System.Windows.Threading;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;

namespace CustomerDisplay;

/// <summary>
/// «ePharm на технических работах» (1.0.95). HQ switches it on the server; the till asks about once a minute. During
/// the works the ePharm icon opens a notice instead of the personal window, the icon carries an amber dot and no
/// recommendation pops up. When the works end the till says «ePharm снова работает» once in the corner of the screen,
/// also after a restart if it was off at that moment. No answer from the server keeps the last known state.
/// </summary>
public partial class MainWindow
{
    private readonly DispatcherTimer _noticeTimer = new();
    private EpharmNoticeState? _noticeState;
    private PosmNoticeDto? _notice;
    private string? _noticeStatePath;
    private EpharmMaintenanceWindow? _maintenanceWindow;
    private EpharmToastWindow? _liveToast;
    private bool _noticeLoggedSkip;

    /// <summary>true while HQ keeps ePharm «на технических работах» for this till (last known answer).</summary>
    internal bool EpharmPaused => _noticeState?.Maintenance == true;

    private void InitEpharmNotice()
    {
        if (_epharm == null || _noticeState != null) return;
        _noticeStatePath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Epharm", "notice-state.json");
        _noticeState = EpharmNoticeState.Load(_noticeStatePath);
        // Spread 500 tills over the minute.
        _noticeTimer.Interval = TimeSpan.FromSeconds(55 + Random.Shared.Next(0, 20));
        _noticeTimer.Tick += async (_, _) => await RefreshEpharmNoticeAsync();
        _noticeTimer.Start();
        Closed += (_, _) => { _noticeTimer.Stop(); _maintenanceWindow?.Close(); _liveToast?.Close(); };
        ApplyNoticeToTray();
        Dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(() => _ = RefreshEpharmNoticeAsync()));
    }

    private async Task RefreshEpharmNoticeAsync()
    {
        if (_epharm == null || _noticeState == null) return;
        PosmNoticeDto? notice;
        try { notice = await _epharm.GetNoticeAsync(); }
        catch { return; }
        if (notice == null) return;
        _notice = notice;
        var wasPaused = EpharmPaused;
        var announce = _noticeState.Apply(notice.Maintenance);
        _noticeState.Save(_noticeStatePath!, Log);
        if (notice.Maintenance)
        {
            _maintenanceWindow?.Update(notice.Title, notice.Message);
            _selfWindow?.Close();
            // Notices from HQ wait for the end of the works (they stay open on the server).
            CloseHqMessageQuietly();
        }
        else
        {
            _maintenanceWindow?.Close();
            _noticeLoggedSkip = false;
        }
        if (wasPaused != EpharmPaused)
        {
            Log(EpharmPaused ? "ePharm: технические работы (по решению HQ)" : "ePharm: технические работы закончились");
            ApplyNoticeToTray();
        }
        if (announce) ShowEpharmLiveToast();
        else FlushPendingWelcome();
        if (!EpharmPaused)
        {
            OnMessagesStamp(notice.MessagesAt);
            if (wasPaused) ShowNextHqMessage();
        }
    }

    /// <summary>The ePharm icon during the works: the notice with the HQ text.</summary>
    private void ShowMaintenanceWindow()
    {
        if (_maintenanceWindow != null && _maintenanceWindow.IsLoaded)
        {
            _maintenanceWindow.Activate();
            return;
        }
        var window = new EpharmMaintenanceWindow(_notice?.Title, _notice?.Message, PharmacistScreen());
        window.Closed += (_, _) => { if (ReferenceEquals(_maintenanceWindow, window)) _maintenanceWindow = null; };
        _maintenanceWindow = window;
        window.Show();
    }

    /// <summary>«ePharm снова работает»: once, in the corner, without taking the focus from the cashier program.</summary>
    private void ShowEpharmLiveToast()
    {
        _liveToast?.Close();
        var name = _selfTrayName.Length > 0 ? _selfTrayName + ", " : "";
        var toast = new EpharmToastWindow(
            "ePharm снова работает",
            name + "нажмите, чтобы открыть свой кабинет ePharm. Подсказки по чеку снова будут появляться здесь.",
            PharmacistScreen());
        toast.Clicked += () => Dispatcher.BeginInvoke(new Action(() => _ = OpenStaffSelfAsync()));
        toast.Closed += (_, _) =>
        {
            if (!ReferenceEquals(_liveToast, toast)) return;
            _liveToast = null;
            // A notice from HQ waited for the corner (1.0.99).
            Dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(ShowNextHqMessage));
        };
        _liveToast = toast;
        toast.Show();
        CountLiveToastAsWelcome();
        Log("ePharm: показано уведомление «ePharm снова работает»");
    }

    /// <summary>Recommendation popups wait during the works (logged once per period).</summary>
    private bool SkipRecommendationsForNotice()
    {
        if (!EpharmPaused) return false;
        if (!_noticeLoggedSkip)
        {
            Log("ePharm на технических работах: подсказки по чеку не показываются");
            _noticeLoggedSkip = true;
        }
        return true;
    }
}
