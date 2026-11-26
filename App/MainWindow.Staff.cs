using System;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;
using CustomerDisplay.Services;

namespace CustomerDisplay;

/// <summary>
/// Who works in Standard-N at this till, for diagnostics and the receipt reader only. Since 1.0.97 the till has no
/// pharmacist sign-in window: a pharmacist sets a password in «Мой ePharm» and signs in to the cabinet with phone or IIN,
/// and sales are credited from the Standard-N receipt. The heartbeat still reports every five minutes whether the
/// Standard-N cashier session resolves here (INK-161), and the receipt reader asks when the cashier program started.
/// </summary>
public partial class MainWindow
{
    private StandardNDbLookup? _staffLookup;
    private readonly CancellationTokenSource _staffStop = new();
    private Func<StaffProcessPresence> _staffProcessProbe = ObserveStaffProcess;
    private volatile string _staffSourceStatus = "initializing";

    /// <summary>Fixed since 1.0.97, there is no sign-in at the till; the heartbeat keeps the field for the fleet table.</summary>
    public string StaffAuthenticationStatus => "local_disabled";
    public string StaffSourceStatus => _staffSourceStatus;

    private void StartStaffSourceDiagnostics()
    {
        if (_epharm == null || _posmConfig?.StaffAuthenticationEnabled != true)
        {
            _staffSourceStatus = "local_disabled";
            return;
        }
        try
        {
            _staffLookup = new StandardNDbLookup(_posmConfig, _ => { });
            _ = Task.Run(() => StaffSourceDiagnosticLoop(_staffStop.Token));
        }
        catch { _staffSourceStatus = "unknown"; }
    }

    private async Task StaffSourceDiagnosticLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            ObserveStaffSource();
            try { await Task.Delay(PosmPollIntervals.StaffSourceDiagnostic, ct).ConfigureAwait(false); }
            catch (OperationCanceledException) { return; }
        }
    }

    /// <summary>One reading of the Standard-N cashier session: no window and no session, only a fixed code leaves the till.</summary>
    private void ObserveStaffSource()
    {
        try
        {
            var process = _staffProcessProbe();
            var observation = process.Status == "resolved"
                ? _staffLookup?.ObserveStaffSession(process.Identity!) ?? new StaffSessionObservation("database_unavailable")
                : new StaffSessionObservation(process.Status);
            _staffSourceStatus = StaffAuthDiagnostics.NormalizeSource(observation.Status);
        }
        catch { _staffSourceStatus = "unknown"; }
    }

    private readonly object _cashierProbeLock = new();
    private DateTimeOffset _cashierProbeAt = DateTimeOffset.MinValue;
    private DateTimeOffset? _cashierProbeStart;

    /// <summary>
    /// Start of the single running cashier program for the receipt reader (cached ten seconds: the reader asks
    /// every few seconds). Null when none or several run, then no session is discarded.
    /// </summary>
    private DateTimeOffset? CashierProcessStartedUtc()
    {
        lock (_cashierProbeLock)
        {
            var now = DateTimeOffset.UtcNow;
            if (now - _cashierProbeAt < TimeSpan.FromSeconds(10)) return _cashierProbeStart;
            _cashierProbeAt = now;
            var presence = ObserveStaffProcess();
            _cashierProbeStart = presence.Status == "resolved" ? StaffSessionResolver.ProcessStartUtc(presence.Identity) : null;
            return _cashierProbeStart;
        }
    }

    /// <summary>
    /// The cashier programs and the Standard-N manager program running in this Windows session. Names are matched by
    /// <see cref="StaffProcessPresence.IsCashierProcessName"/>, not by the exact «zkassa»: a copy kept under another name
    /// («zkassa_BACKUP_2_6_88») is the cashier too, and a computer with only «ManagerXP2» reports «manager_only».
    /// </summary>
    private static StaffProcessPresence ObserveStaffProcess()
    {
        try
        {
            using var current = Process.GetCurrentProcess();
            var running = new System.Collections.Generic.List<StaffRunningProcess>();
            var unreadable = false;
            foreach (var process in Process.GetProcesses())
                using (process)
                {
                    string name;
                    try { name = process.ProcessName; }
                    catch { continue; }
                    var cashier = StaffProcessPresence.IsCashierProcessName(name);
                    if (!cashier && !StaffProcessPresence.IsManagerProcessName(name)) continue;
                    try
                    {
                        if (process.SessionId != current.SessionId) continue;
                        var id = process.Id.ToString(System.Globalization.CultureInfo.InvariantCulture);
                        // The manager program only has to run; the cashier program is told apart by its start.
                        running.Add(new(name, cashier
                            ? id + ":" + process.StartTime.ToUniversalTime().Ticks.ToString(System.Globalization.CultureInfo.InvariantCulture)
                            : id));
                    }
                    catch { if (cashier) unreadable = true; }
                }
            var presence = StaffProcessPresence.FromProcesses(running);
            // An unreadable peer prevents source attribution.
            return unreadable ? presence with { Status = "process_unavailable" } : presence;
        }
        catch { return new("process_unavailable"); }
    }

    private void StopStaffSourceDiagnostics()
    {
        _staffStop.Cancel();
        _staffLookup?.Dispose();
    }
}
