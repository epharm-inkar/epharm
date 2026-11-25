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

    private static StaffProcessPresence ObserveStaffProcess()
    {
        try
        {
            using var current = Process.GetCurrentProcess();
            var values = new System.Collections.Generic.List<string>();
            var unreadable = false;
            foreach (var name in new[] { "zkassa", "kassir" })
                foreach (var process in Process.GetProcessesByName(name))
                    using (process)
                    {
                        try {
                            if (process.SessionId != current.SessionId) continue;
                            values.Add(process.Id.ToString(System.Globalization.CultureInfo.InvariantCulture) + ":" +
                                process.StartTime.ToUniversalTime().Ticks.ToString(System.Globalization.CultureInfo.InvariantCulture));
                        }
                        catch { unreadable = true; }
                    }
            var presence = StaffProcessPresence.FromInstances(values);
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
