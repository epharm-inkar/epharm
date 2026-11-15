using System.Text.Json;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class UnresolvedRewardJournalTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "epharm-unresolved-rewards-" + Guid.NewGuid().ToString("N"));
    private readonly DateTimeOffset _at = DateTimeOffset.Parse("2026-11-15T00:00:00Z");
    private readonly RewardSourceIdentity _identity = new() { SourceNamespace = new('a', 64), RawUserId = "17", NativeSessionId = 80, WorkstationId = "9" };
    private static string Fingerprint => new('b', 64);
    private const string Audit = "{\"reason\":\"source_disappeared_unresolved\",\"first\":1,\"second\":2}";
    private static RewardCartLine Line => new() { SourceDocumentId = 100, SourceLineId = 501, Sku = "10", Barcode = "4600000000012", Qty = 1, UnitSemantics = "full_package" };
    private static StandardNReceiptDocument Paid => new(100, 80, null, 1, -100, "register", 100, 1,
        [new StandardNReceiptLine(501, 10, Line.Barcode, "Synthetic", 100, 1, 0) { SourceDocumentId = 100,
            SourceTotal = 100, SignedPrice = 100, SignedAmount = -100, SignedQuantity = -1, UnitSemantics = "full_package" }]) { FinalDetailsRead = true };
    private StandardNReceiptSnapshot Snapshot => new(100, 80, "CASH", null, new("17", "Synthetic", 80), []) { WorkstationId = "9" };

    [Fact]
    public void MoreThan64ArchivedCheckoutsAndRestartPermitFreshPaidEvidenceWithoutRevivingOldIds()
    {
        var journal = new RecommendationRewardJournal(_root);
        for (var i = 0; i < 70; i++)
        {
            var checkout = "cancelled-" + i;
            journal.RecordShown(checkout, "show-" + i, Line.Barcode, "10", _identity, [], _at, true);
            journal.Observe(checkout, _identity, [Line], _at.AddSeconds(30));
            Assert.True(journal.ArchiveUnresolved(checkout, Fingerprint, Audit));
            Assert.Empty(Directory.GetFiles(_root, "*.json"));
            if (i == 35) journal = new RecommendationRewardJournal(_root);
        }
        Assert.Equal(70, Directory.GetFiles(Path.Combine(_root, "unresolved"), "*.json").Length);
        journal = new RecommendationRewardJournal(_root);
        for (var i = 0; i < 70; i++) Assert.False(journal.BuildSale("cancelled-" + i, Paid, _identity, _at.AddMinutes(5)).JournalComplete);
        // Old-ID probes cannot damage or manufacture another checkout's evidence.
        Assert.NotNull(journal.RecordShown("fresh", "show-fresh", Line.Barcode, "10", _identity, [], _at, true));
        journal.Observe("fresh", _identity, [Line], _at.AddSeconds(30));
        Assert.True(journal.BuildSale("fresh", Paid, _identity, _at.AddMinutes(5)).JournalComplete);
        Assert.Single(journal.BuildSale("fresh", Paid, _identity, _at.AddMinutes(5)).Changes);
    }

    [Fact]
    public void ArchivePreservesFullOriginalAndIsImmutableIdempotentAcrossRestart()
    {
        var journal = new RecommendationRewardJournal(_root);
        journal.RecordShown("checkout", "event", Line.Barcode, "10", _identity, [], _at, true);
        journal.Observe("checkout", _identity, [Line], _at.AddSeconds(20));
        var original = File.ReadAllText(Assert.Single(Directory.GetFiles(_root, "*.json")));
        Assert.True(journal.ArchiveUnresolved("checkout", Fingerprint, Audit));
        var path = Assert.Single(Directory.GetFiles(Path.Combine(_root, "unresolved"), "*.json"));
        var bytes = File.ReadAllBytes(path);
        var envelope = JsonSerializer.Deserialize<RecommendationRewardJournal.Envelope>(bytes, EpharmJson.Options)!;
        var archive = JsonSerializer.Deserialize<RecommendationRewardJournal.UnresolvedArchive>(envelope.Payload, EpharmJson.Options)!;
        Assert.Equal(original, archive.JournalJson);
        Assert.Equal("source_disappeared_unresolved", archive.Reason);
        journal = new RecommendationRewardJournal(_root);
        Assert.True(journal.ArchiveUnresolved("checkout", Fingerprint, "{\"later\":true}"));
        Assert.False(journal.ArchiveUnresolved("checkout", new string('c', 64), Audit));
        Assert.Null(journal.RecordShown("checkout", "different-show", Line.Barcode, "10", _identity, [], _at, true));
        journal.Retire("checkout");
        Assert.False(journal.BuildSale("checkout", Paid, _identity, _at.AddMinutes(5)).JournalComplete);
        Assert.Equal(bytes, File.ReadAllBytes(path));
    }

    [Fact]
    public void LostHotFileAfterArchiveStillRecoversOnlyOriginalRecordedNamespace()
    {
        var journal = new RecommendationRewardJournal(_root);
        journal.Observe("checkout", _identity, [Line], _at);
        Assert.True(journal.TryGetRecordedSourceNamespace("checkout", Snapshot, out var ns)); Assert.Equal(_identity.SourceNamespace, ns);
        Assert.True(journal.ArchiveUnresolved("checkout", Fingerprint, Audit));
        journal = new RecommendationRewardJournal(_root);
        Assert.True(journal.TryGetRecordedSourceNamespace("checkout", Snapshot, out ns)); Assert.Equal(_identity.SourceNamespace, ns);
        Assert.False(journal.TryGetRecordedSourceNamespace("checkout", Snapshot with { WorkstationId = "other" }, out _));
        Assert.False(journal.TryGetRecordedSourceNamespace("checkout", Snapshot with { SessionId = 81 }, out _));
        Assert.False(journal.TryGetRecordedSourceNamespace("checkout", Snapshot with { Pharmacist = new("18", "Synthetic", 80) }, out _));
        Assert.False(journal.TryGetRecordedSourceNamespace("missing", Snapshot, out _));
    }

    [Fact]
    public void ArchiveFailureRetainsOriginalAndNeverClearsGlobalOverflowFailure()
    {
        var journal = new RecommendationRewardJournal(_root);
        journal.Observe("keep", _identity, [Line], _at);
        var path = Assert.Single(Directory.GetFiles(_root, "*.json")); var original = File.ReadAllBytes(path);
        Assert.False(journal.ArchiveUnresolved("keep", Fingerprint, "not-json"));
        Assert.Equal(original, File.ReadAllBytes(path));
        for (var i = 0; i < 70; i++) journal.MarkIncomplete("gap-" + i);
        Assert.True(journal.ArchiveUnresolved("keep", Fingerprint, Audit));
        Assert.Null(journal.RecordShown("fresh-after-real-overflow", "show", Line.Barcode, "10", _identity, [], _at, true));
        Assert.False(journal.BuildSale("fresh-after-real-overflow", Paid, _identity, _at.AddMinutes(5)).JournalComplete);
    }

    public void Dispose() { if (Directory.Exists(_root)) Directory.Delete(_root, true); }
}
