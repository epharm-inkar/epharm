using System;
using System.Collections.Generic;
using System.Linq;
using System.Security.Cryptography;
using System.Text;

namespace CustomerDisplay.Services;

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
}
