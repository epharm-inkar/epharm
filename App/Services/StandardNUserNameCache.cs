using System;
using System.Collections.Generic;

namespace CustomerDisplay.Services;

/// <summary>Optional audit labels are source-local observations, never authentication proof.</summary>
internal sealed class StandardNUserNameCache(TimeProvider? timeProvider = null)
{
    private readonly TimeProvider _time = timeProvider ?? TimeProvider.System;
    private readonly Dictionary<(string UserId, long? SessionId), Entry> _entries = new();
    private string? _source;
    private sealed record Entry(string? Name, long ObservedAt);

    public bool TryGet(string? source, string userId, out string? name, long? sessionId = null)
    {
        lock (_entries) {
            name = null;
            var key = (userId, sessionId);
            if (!SelectSource(source) || !_entries.TryGetValue(key, out var entry)) return false;
            var ttl = string.IsNullOrWhiteSpace(entry.Name) ? TimeSpan.FromSeconds(30) : TimeSpan.FromMinutes(5);
            if (_time.GetElapsedTime(entry.ObservedAt) >= ttl) { _entries.Remove(key); return false; }
            name = entry.Name;
            return true;
        }
    }

    public void Store(string? source, string userId, string? name, long? sessionId = null)
    {
        lock (_entries) {
            if (!SelectSource(source)) return;
            // This is a workstation cache, not an unbounded copy of the employee directory.
            var key = (userId, sessionId);
            if (_entries.Count >= 512 && !_entries.ContainsKey(key)) _entries.Clear();
            _entries[key] = new(name, _time.GetTimestamp());
        }
    }

    private bool SelectSource(string? source)
    {
        if (!string.Equals(source, _source, StringComparison.Ordinal)) { _entries.Clear(); _source = source; }
        return !string.IsNullOrWhiteSpace(source);
    }
}
