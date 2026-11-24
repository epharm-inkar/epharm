using System.Text.Json;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

// INK-122: tills stopped confirming every receipt once 32 never-resolving groups filled the hot journal.
public sealed class ObservedGroupCapacityTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "epharm-capacity-" + Guid.NewGuid().ToString("N"));
    private static readonly DateTimeOffset Now = new(2026, 11, 24, 12, 0, 0, TimeSpan.Zero);
    private string Receipts => Path.Combine(_root, "receipts");
    private string StaleArchive => Path.Combine(Receipts, "stale-observed-groups");
    private ReceiptArtifactStore Store() => new(Receipts, new FiscalReceiptInboxSource(Path.Combine(_root, "inbox"), ["ofd-api"]),
        utcNow: () => Now);

    private static StandardNObservedReceiptGroup Group(long id, DateTimeOffset? openedAt = null)
    {
        var group = ObservedReceiptGroupTests.Group(id);
        return group with { Snapshot = group.Snapshot with { OpenedAt = openedAt } };
    }

    [Fact]
    public void FullJournalRejectsANewGroupUntilAStaleOneIsReleased()
    {
        var store = Store();
        for (var id = 1; id <= ObservedGroupCapacity.HotLimit; id++) Assert.True(store.SaveObservedGroup(Group(id)));
        Assert.False(store.SaveObservedGroup(Group(100))); // the November failure mode

        Assert.True(store.ReleaseStaleObservedGroup(Group(1), ObservedGroupCapacity.StaleReason));

        Assert.True(store.SaveObservedGroup(Group(100)));
        Assert.DoesNotContain(store.ReadObservedGroups(), group => group.Snapshot.DocumentId == 1);
        Assert.Equal(ObservedGroupCapacity.HotLimit, Store().ReadObservedGroups().Count);
    }

    [Fact]
    public void ReleasedGroupIsKeptVerbatimInThePrivateArchive()
    {
        var store = Store(); var group = Group(7);
        Assert.True(store.SaveObservedGroup(group));
        Assert.True(store.ReleaseStaleObservedGroup(group, ObservedGroupCapacity.CapacityReason));

        var file = Assert.Single(Directory.GetFiles(StaleArchive, "*.json"));
        using var outer = JsonDocument.Parse(File.ReadAllBytes(file));
        using var payload = JsonDocument.Parse(outer.RootElement.GetProperty("payloadJson").GetString()!);
        Assert.Equal("capacity_pressure", payload.RootElement.GetProperty("reason").GetString());
        Assert.Equal(JsonSerializer.Serialize(group, EpharmJson.Options), payload.RootElement.GetProperty("originalGroupJson").GetString());
        Assert.Empty(store.ReadObservedGroups());
        // The same Standard-N document observed later starts a fresh group (sales dedupe by DOCS.ID).
        Assert.True(store.SaveObservedGroup(group));
    }

    [Fact]
    public void ChangedOrMissingGroupIsNotReleasedAndUnknownReasonIsRejected()
    {
        var store = Store(); var group = Group(8);
        Assert.False(store.ReleaseStaleObservedGroup(group, ObservedGroupCapacity.StaleReason));
        Assert.True(store.SaveObservedGroup(group));
        var changed = group with { Snapshot = group.Snapshot with { OpenedAt = Now } };
        Assert.True(store.SaveObservedGroup(changed));
        Assert.False(store.ReleaseStaleObservedGroup(group, ObservedGroupCapacity.StaleReason));
        Assert.Throws<ArgumentException>(() => store.ReleaseStaleObservedGroup(changed, "anything"));
        Assert.Single(store.ReadObservedGroups());
        Assert.False(Directory.Exists(StaleArchive));
    }

    [Fact]
    public void SelectionReleasesStaleGroupsButNeverTheCurrentOrARecentOne()
    {
        var old = Group(1, Now.AddHours(-7));
        var current = Group(2, Now.AddDays(-2));
        var recent = Group(3, Now.AddMinutes(-2));
        var untouchedLong = Group(4);
        var changed = new Dictionary<long, DateTimeOffset>
        { [1] = Now.AddHours(-1), [2] = Now.AddHours(-7), [3] = Now.AddMinutes(-2), [4] = Now.AddHours(-6) };

        var releases = ObservedGroupCapacity.SelectReleases([old, current, recent, untouchedLong],
            group => group.Snapshot.DocumentId == 2, group => changed[group.Snapshot.DocumentId], Now);

        Assert.Equal([1L, 4L], releases.Select(release => release.Group.Snapshot.DocumentId).Order());
        Assert.All(releases, release => Assert.Equal(ObservedGroupCapacity.StaleReason, release.Reason));
    }

    [Fact]
    public void PressureReleasesOldestDownToTheFreeSlotReserve()
    {
        var groups = Enumerable.Range(1, ObservedGroupCapacity.HotLimit)
            .Select(id => Group(id, Now.AddMinutes(-60 + id))).ToArray(); // 1 is oldest
        var releases = ObservedGroupCapacity.SelectReleases(groups, _ => false, _ => Now.AddMinutes(-30), Now);

        Assert.Equal(ObservedGroupCapacity.FreeSlotsToKeep, releases.Count);
        Assert.Equal([1L, 2L, 3L, 4L], releases.Select(release => release.Group.Snapshot.DocumentId));
        Assert.All(releases, release => Assert.Equal(ObservedGroupCapacity.CapacityReason, release.Reason));
    }

    [Fact]
    public void FutureSourceTimesDoNotMakeAGroupLookFreshAndRecentChangesStayUnderPressure()
    {
        var skewed = Group(1, Now.AddHours(5)); // wrongly converted time zone
        Assert.Null(ObservedGroupCapacity.LastSourceActivity(skewed, Now));
        Assert.Single(ObservedGroupCapacity.SelectReleases([skewed], _ => false, _ => Now.AddHours(-7), Now));

        var busy = Enumerable.Range(1, ObservedGroupCapacity.HotLimit).Select(id => Group(id)).ToArray();
        Assert.Empty(ObservedGroupCapacity.SelectReleases(busy, _ => false, _ => Now.AddMinutes(-1), Now));
    }

    public void Dispose()
    {
        if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true);
    }
}
