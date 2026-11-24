using System;
using System.IO;
using System.Windows.Threading;
using CustomerDisplay.Services;

namespace CustomerDisplay;

/// <summary>
/// «Состояние ePharm»: a permanent ePharm icon next to the clock whose menu opens the status window, and the
/// HQ notice that arrives with the heartbeat answer. A new notice opens the window once (the pharmacist can
/// close it); the same notice never pops up again, also after a restart.
/// </summary>
public partial class MainWindow
{
    private System.Windows.Forms.NotifyIcon? _tillTray;
    private TillStatusWindow? _tillStatusWindow;
    private DispatcherTimer? _tillStatusTimer;
    private bool _tillStatusKazakh;
    private TillStatusLevel? _tillTrayLevel;
    private string? _tillNoticeShownId;
    private DateTimeOffset? _standardNReadAt;
    private DateTimeOffset? _standardNFailingSince;
    private bool _tillStatusStopped;

    private static string TillNoticeStatePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Epharm", "till-notice-shown.txt");

    private void StartTillStatus()
    {
        try
        {
            _tillNoticeShownId = ReadShownNoticeId();
            _tillTray = new System.Windows.Forms.NotifyIcon
            {
                Icon = System.Drawing.SystemIcons.Information,
                Text = "ePharm: состояние",
                Visible = true,
            };
            var menu = new System.Windows.Forms.ContextMenuStrip();
            menu.Items.Add("Состояние ePharm / ePharm күйі", null, (_, _) => Dispatcher.Invoke(() => OpenTillStatus(activate: true)));
            _tillTray.ContextMenuStrip = menu;
            _tillTray.DoubleClick += (_, _) => Dispatcher.Invoke(() => OpenTillStatus(activate: true));
            _tillTray.BalloonTipClicked += (_, _) => Dispatcher.Invoke(() => OpenTillStatus(activate: true));

            _tillStatusTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(10) };
            _tillStatusTimer.Tick += (_, _) => TickTillStatus();
            _tillStatusTimer.Start();
        }
        catch (Exception ex)
        {
            Log($"Состояние ePharm: значок не создан ({ex.Message}).");
        }
    }

    private void StopTillStatus()
    {
        if (_tillStatusStopped) return;
        _tillStatusStopped = true;
        _tillStatusTimer?.Stop();
        _tillStatusWindow?.Close();
        _tillTray?.Dispose();
    }

    /// <summary>Called by the Standard-N reader after every read attempt.</summary>
    private void NoteStandardNRead(bool ok)
    {
        var now = DateTimeOffset.UtcNow;
        if (ok) { _standardNReadAt = now; _standardNFailingSince = null; }
        else _standardNFailingSince ??= now;
    }

    internal TillStatusSnapshot BuildTillSnapshot()
    {
        var now = DateTimeOffset.UtcNow;
        var config = _posmConfig;
        bool? loginOpen = _staffAuthenticationStatus is "initializing" or "network_unavailable" or "endpoint_unavailable"
            ? null : _staffEnabled;
        return new TillStatusSnapshot(
            Now: now,
            Version: AppUpdater.CurrentVersion().ToString(),
            PharmacyName: _epharm?.LastPharmacyName,
            PharmacyId: config?.PharmacyId,
            DeviceId: config?.ResolveDeviceId(),
            ServerAcceptedAt: _epharm?.LastHeartbeatAcceptedAt,
            ServerFailingSince: _epharm?.HeartbeatFailingSince,
            StandardNEnabled: config?.StandardNDbEnabled == true,
            StandardNReadAt: _standardNReadAt,
            StandardNFailingSince: _standardNFailingSince,
            SellerName: string.IsNullOrWhiteSpace(_currentPharmacistName) ? null : _currentPharmacistName,
            StaffLoginOpen: loginOpen,
            StaffAuthenticated: _staffEnabled && _staffSession.IsAuthenticated(_staffClock()),
            StaffName: null,
            Notice: _epharm?.LastTillNotice,
            LocalOffset: TimeZoneInfo.Local.GetUtcOffset(now));
    }

    private void OpenTillStatus(bool activate)
    {
        if (_tillStatusStopped) return;
        if (_tillStatusWindow == null)
        {
            var window = new TillStatusWindow(BuildTillSnapshot, _tillStatusKazakh, PharmacistScreen());
            window.LanguageChanged += kazakh => _tillStatusKazakh = kazakh;
            window.Closed += (_, _) => { if (ReferenceEquals(_tillStatusWindow, window)) _tillStatusWindow = null; };
            _tillStatusWindow = window;
            window.Show();
        }
        else _tillStatusWindow.Refresh();
        if (activate) _tillStatusWindow.Activate();
    }

    private void TickTillStatus()
    {
        if (_tillStatusStopped) return;
        var snapshot = BuildTillSnapshot();
        var level = TillStatusText.Overall(TillStatusText.Rows(snapshot));
        UpdateTillTray(level, snapshot);
        _tillStatusWindow?.Refresh();

        if (snapshot.Notice is { IsValid: true } notice && notice.Id != _tillNoticeShownId)
        {
            _tillNoticeShownId = notice.Id;
            WriteShownNoticeId(notice.Id);
            Log($"Состояние ePharm: новое сообщение HQ {notice.Id}, окно открыто один раз.");
            OpenTillStatus(activate: false);
        }
    }

    private void UpdateTillTray(TillStatusLevel level, TillStatusSnapshot snapshot)
    {
        if (_tillTray == null) return;
        var text = level switch
        {
            TillStatusLevel.Problem => "ePharm: есть проблема, нажмите",
            TillStatusLevel.Warning => "ePharm: нужно проверить, нажмите",
            _ => "ePharm: работает",
        };
        if (_tillTray.Text != text) _tillTray.Text = text;
        if (_tillTrayLevel == level) return;
        var previous = _tillTrayLevel;
        _tillTrayLevel = level;
        _tillTray.Icon = level switch
        {
            TillStatusLevel.Problem => System.Drawing.SystemIcons.Error,
            TillStatusLevel.Warning => System.Drawing.SystemIcons.Warning,
            _ => System.Drawing.SystemIcons.Information,
        };
        // One short hint when things get worse; the window itself never opens for a problem on its own.
        if (previous != null && level > previous && level >= TillStatusLevel.Warning)
        {
            try
            {
                _tillTray.ShowBalloonTip(6000, "ePharm",
                    level == TillStatusLevel.Problem
                        ? "Есть проблема. Нажмите, чтобы узнать, что делать."
                        : "Нужно кое-что проверить. Нажмите, чтобы узнать, что делать.",
                    System.Windows.Forms.ToolTipIcon.Warning);
            }
            catch (Exception) { }
        }
    }

    private static string? ReadShownNoticeId()
    {
        try { return File.Exists(TillNoticeStatePath) ? File.ReadAllText(TillNoticeStatePath).Trim() : null; }
        catch (Exception) { return null; }
    }

    private static void WriteShownNoticeId(string id)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(TillNoticeStatePath)!);
            File.WriteAllText(TillNoticeStatePath, id);
        }
        catch (Exception) { }
    }
}
