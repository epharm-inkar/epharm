using System;
using System.Collections.Generic;
using System.Linq;
using System.Security.Cryptography;
using System.Text;

namespace CustomerDisplay.Services;

/// <summary>One process of this Windows session as the matcher sees it: its name and its «pid:start ticks» instance.</summary>
public readonly record struct StaffRunningProcess(string Name, string Instance);

/// <summary>Only real OS process instances establish presence; no synthetic Standard-N user/session IDs.</summary>
public sealed record StaffProcessPresence(string Status, string? Identity = null)
{
    public static StaffProcessPresence FromInstances(IEnumerable<string> instances)
    {
        var values = instances.Distinct(StringComparer.Ordinal).OrderBy(value => value, StringComparer.Ordinal).ToArray();
        return values.Length switch {
            0 => new("no_process"),
            1 => new("resolved", values[0]),
            _ => new("ambiguous_process", "set:" + Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(string.Join("|", values))))),
        };
    }

    /// <summary>
    /// The Standard-N cashier program by its process name: «kassir», or any name that starts with «zkassa». A copy kept
    /// under another name, such as «zkassa_BACKUP_2_6_88», is still the cashier: Standard-N records its session as
    /// «zkassa» (1.0.100, 26.11.2026: 68 of 506 tills could not tell who sells). Case does not matter.
    /// </summary>
    public static bool IsCashierProcessName(string? processName)
    {
        var name = (processName ?? "").Trim();
        return name.Equals("kassir", StringComparison.OrdinalIgnoreCase) ||
               name.StartsWith("zkassa", StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>The Standard-N back-office program («ManagerXP2» and its other versions): the manager's computer, not a till.</summary>
    public static bool IsManagerProcessName(string? processName) =>
        (processName ?? "").Trim().StartsWith("ManagerXP", StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// What the processes of this Windows session say about the cashier: one cashier program resolves, several are
    /// ambiguous, none is «manager_only» when the back-office program runs (ePharm was installed on the manager's
    /// computer, not on the till) and «no_process» otherwise. A cashier program always wins over the manager.
    /// </summary>
    public static StaffProcessPresence FromProcesses(IEnumerable<StaffRunningProcess> processes)
    {
        var running = processes.ToArray();
        var presence = FromInstances(running.Where(process => IsCashierProcessName(process.Name)).Select(process => process.Instance));
        return presence.Status == "no_process" && running.Any(process => IsManagerProcessName(process.Name))
            ? new("manager_only")
            : presence;
    }
}
