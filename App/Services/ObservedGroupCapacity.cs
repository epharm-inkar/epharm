using System;
using System.Collections.Generic;
using System.Linq;

namespace CustomerDisplay.Services;

/// <summary>
/// Keeps old observed groups from blocking the capture of new receipts (INK-122, November 2026).
/// <para>
/// A print cue from the Standard-N log does not name its document, so it used to become a candidate
/// of every open group. One old group whose document had been posted without its own cue (a print
/// while POSM was restarting, a missed log line) or whose source could no longer be read then made
/// every later print ambiguous or unreadable, and the till stopped confirming sales. Groups that
/// can never resolve also kept their hot slot forever until <see cref="HotLimit"/> filled up.
/// </para>
/// Now only the current receipt and groups seen within <see cref="LiveWindow"/> take part in a new
/// print cue, and stale groups are moved to a private local archive: nothing is reported, credited
/// or deleted by that move, and the backend deduplicates sales by DOCS.ID if one is observed again.
/// </summary>
public static class ObservedGroupCapacity
{
    public const int HotLimit = 32;
    /// <summary>Slots kept free so that the next new receipt always has room.</summary>
    public const int FreeSlotsToKeep = 4;
    /// <summary>A group seen this recently can still be the receipt that is being printed.</summary>
    public static readonly TimeSpan LiveWindow = TimeSpan.FromMinutes(10);
    /// <summary>A group not seen this long (by the till's clock or by Standard-N times) is stale.</summary>
    public static readonly TimeSpan StaleAfter = TimeSpan.FromHours(6);

    public const string StaleReason = "stale_unresolved";
    public const string CapacityReason = "capacity_pressure";

    public static bool IsLive(DateTimeOffset? lastSeenAt, DateTimeOffset now) =>
        lastSeenAt is { } seen && now - seen <= LiveWindow && seen <= now + LiveWindow;

    /// <summary>Latest Standard-N open/post time or anchored print time; implausible future times are ignored.</summary>
    public static DateTimeOffset? LastSourceActivity(StandardNObservedReceiptGroup group, DateTimeOffset now)
    {
        var times = new List<DateTimeOffset>();
        if (group.Snapshot.OpenedAt is { } opened) times.Add(opened);
        times.AddRange(group.PrintDocumentTimes.Values);
        foreach (var document in group.Snapshot.Documents ?? Array.Empty<StandardNReceiptDocument>())
            if (document.PostedAt is { } posted) times.Add(posted);
        // A wrongly converted time zone must not make an old group look fresh.
        var plausible = times.Where(time => time <= now + LiveWindow).ToArray();
        return plausible.Length == 0 ? null : plausible.Max();
    }

    /// <summary>
    /// Chooses groups to move out of the hot journal. <paramref name="lastSeenAt"/> is the till's own
    /// clock of the last time the group was the current receipt or changed (for groups loaded from
    /// disk: the program start). The current receipt and live groups are never chosen.
    /// </summary>
    public static IReadOnlyList<(StandardNObservedReceiptGroup Group, string Reason)> SelectReleases(
        IReadOnlyCollection<StandardNObservedReceiptGroup> groups,
        Func<StandardNObservedReceiptGroup, bool> isCurrent,
        Func<StandardNObservedReceiptGroup, DateTimeOffset> lastSeenAt,
        DateTimeOffset now)
    {
        var movable = groups
            .Where(group => !isCurrent(group) && !IsLive(lastSeenAt(group), now))
            .Select(group => (Group: group, Source: LastSourceActivity(group, now), Seen: lastSeenAt(group)))
            .Where(entry => entry.Source is not { } source || now - source > LiveWindow)
            .ToArray();
        var releases = new List<(StandardNObservedReceiptGroup Group, string Reason)>();
        foreach (var entry in movable)
            if (now - entry.Seen >= StaleAfter || entry.Source is { } source && now - source >= StaleAfter)
                releases.Add((entry.Group, StaleReason));

        var remaining = groups.Count - releases.Count;
        var allowed = HotLimit - FreeSlotsToKeep;
        if (remaining <= allowed) return releases;
        var chosen = releases.Select(release => release.Group).ToHashSet();
        // Oldest first: unknown source time counts as oldest, then the till's own clock.
        foreach (var entry in movable.Where(entry => !chosen.Contains(entry.Group))
                     .OrderBy(entry => entry.Source ?? DateTimeOffset.MinValue).ThenBy(entry => entry.Seen))
        {
            if (remaining <= allowed) break;
            releases.Add((entry.Group, CapacityReason));
            remaining--;
        }
        return releases;
    }
}
