using System.Text.Json;
using CustomerDisplay.Config;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class RecommendationRewardJournalTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "epharm-reward-" + Guid.NewGuid().ToString("N"));
    private readonly DateTimeOffset _at = DateTimeOffset.Parse("2026-11-09T10:00:00Z");
    private readonly RewardSourceIdentity _identity = new() {
        SourceNamespace = new('a', 64), RawUserId = "17", NativeSessionId = 80, WorkstationId = "9", IdentityStatus = "unknown",
    };
    private RecommendationRewardJournal Journal() => new(_root);
    private static RewardCartLine Line(decimal qty = 1, long doc = 100, long line = 501, string unit = "full_package") => new() {
        SourceDocumentId = doc, SourceLineId = line, Sku = "10", Barcode = "4600000000012", Qty = qty, UnitSemantics = unit,
    };
    private static StandardNReceiptDocument Paid(params RewardCartLine[] lines)
    {
        var physical = lines.Select(line => new StandardNReceiptLine(line.SourceLineId, int.Parse(line.Sku!), line.Barcode, "Synthetic product",
            100, line.Qty, 0) { SourceDocumentId = line.SourceDocumentId, SourceTotal = 100 * line.Qty, SignedPrice = 100,
                SignedAmount = -100 * line.Qty, SignedQuantity = -line.Qty, UnitSemantics = line.UnitSemantics }).ToArray();
        return new(lines[0].SourceDocumentId, 80, null, 1, -physical.Sum(line => line.SourceTotal!.Value), "synthetic-register", 900, 4, physical) { FinalDetailsRead = true };
    }
    private RewardShownEvidence Show(RecommendationRewardJournal journal, params RewardCartLine[] lines) =>
        journal.RecordShown("checkout", "event-1", "4600000000012", "catalogue-product", _identity, lines, _at, true)!;

    [Fact]
    public void PreexistingQuantityIsBaselineAndOnlyObservedIncreaseBecomesADelta()
    {
        var journal = Journal();
        var shown = Show(journal, Line(2));
        journal.Observe("checkout", _identity, [Line(3)], _at.AddSeconds(119));
        var sale = journal.BuildSale("checkout", Paid(Line(3)), _identity, _at.AddMinutes(30));
        Assert.True(shown.CartComplete);
        Assert.Equal(2m, Assert.Single(shown.Lines).Qty);
        var change = Assert.Single(sale.Changes);
        Assert.Equal((2m, 3m, "event-1"), (change.BeforeQty, change.AfterQty, change.EventId));
        Assert.Equal(_at.AddSeconds(119), change.ObservedAt);
        Assert.True(sale.JournalComplete); // Payment is deliberately much later than the addition.
        Assert.Equal("paid", sale.Completion.Status);
        Assert.Equal(-300m, sale.Completion.SignedTotal);
    }

    [Fact]
    public void LateIncrementsAndDecreasesArePreservedWithoutClientAwardingOrRounding()
    {
        var journal = Journal(); Show(journal, Line());
        journal.Observe("checkout", _identity, [Line(2)], _at.AddSeconds(120));
        journal.Observe("checkout", _identity, [Line(3)], _at.AddSeconds(121));
        journal.Observe("checkout", _identity, [Line(1)], _at.AddSeconds(122));
        var evidence = journal.BuildSale("checkout", Paid(Line()), _identity, _at.AddHours(1));
        Assert.Equal(new[] { (1m, 2m), (2m, 3m), (3m, 1m) }, evidence.Changes.Select(change => (change.BeforeQty, change.AfterQty)));
        Assert.Equal(new long[] { 1, 2, 3 }, evidence.Changes.Select(change => change.Sequence));
        Assert.Equal(_at.AddSeconds(121), evidence.Changes[1].ObservedAt); // Server applies the 120s window/FIFO.
    }

    [Fact]
    public void DuplicateReadsDoNotAddAndRepeatShowCannotResetBaselineOrTimer()
    {
        var journal = Journal(); var first = Show(journal, Line());
        journal.Observe("checkout", _identity, [Line()], _at.AddSeconds(5));
        var again = journal.RecordShown("checkout", "event-1", "4600000000012", "catalogue-product", _identity, [Line()], _at.AddSeconds(100), true);
        journal.Observe("checkout", _identity, [Line(2)], _at.AddSeconds(121));
        Assert.Equal(first.ObservationId, again!.ObservationId);
        Assert.Equal(1m, Assert.Single(again.Lines).Qty);
        Assert.Single(journal.BuildSale("checkout", Paid(Line(2)), _identity, _at.AddMinutes(5)).Changes);
    }

    [Fact]
    public void RepeatShowWithUnavailableSnapshotCannotInventCartRemovalsOrPoisonTheFirstValidDisplay()
    {
        var journal = Journal();
        var trigger = Line(line: 500) with { Sku = "20", Barcode = "4600000000029" };
        var first = Show(journal, trigger);
        journal.Observe("checkout", _identity, [trigger, Line()], _at.AddSeconds(40));

        var repeated = journal.RecordShown("checkout", "event-1", "4600000000012", "catalogue-product",
            new RewardSourceIdentity(), [], _at.AddSeconds(70), false);
        var sale = journal.BuildSale("checkout", Paid(trigger, Line()), _identity, _at.AddMinutes(5));

        Assert.Equal(first.ObservationId, repeated!.ObservationId);
        Assert.True(sale.JournalComplete);
        var addition = Assert.Single(sale.Changes);
        Assert.Equal((0m, 1m, "event-1"), (addition.BeforeQty, addition.AfterQty, addition.EventId));
        Assert.Equal(_identity, addition.Identity);
        Assert.Equal(_at.AddSeconds(40), addition.ObservedAt);
    }

    [Fact]
    public void RepeatShowCannotReplaceAnActualObservationOrCreateATimelyAddition()
    {
        var journal = Journal();
        var trigger = Line(line: 500) with { Sku = "20", Barcode = "4600000000029" };
        Show(journal, trigger);

        journal.RecordShown("checkout", "event-1", "4600000000012", "catalogue-product", _identity,
            [trigger, Line()], _at.AddSeconds(40), true);
        var sale = journal.BuildSale("checkout", Paid(trigger, Line()), _identity, _at.AddMinutes(5));

        Assert.Empty(sale.Changes);
        Assert.False(sale.JournalComplete);
    }

    [Theory]
    [InlineData("changed-source")]
    [InlineData("mismatch")]
    public void RepeatShowStillRejectsAKnownChangedOrMismatchedIdentity(string kind)
    {
        var journal = Journal(); Show(journal, Line());
        var identity = kind == "changed-source" ? _identity with { RawUserId = "18" } : _identity with { IdentityStatus = "mismatch" };
        journal.RecordShown("checkout", "event-1", "4600000000012", "catalogue-product", identity,
            [Line()], _at.AddSeconds(40), true);
        Assert.False(journal.BuildSale("checkout", Paid(Line()), _identity, _at.AddMinutes(5)).JournalComplete);
    }

    [Fact]
    public void FirstUnavailableShowStaysIncompleteWithoutFabricatingRemovalObservations()
    {
        var journal = Journal(); journal.Observe("checkout", _identity, [Line()], _at);
        var shown = journal.RecordShown("checkout", "new-event", "4600000000012", "catalogue-product",
            new RewardSourceIdentity(), [], _at.AddSeconds(1), false)!;
        var sale = journal.BuildSale("checkout", Paid(Line()), _identity, _at.AddMinutes(5));
        Assert.False(shown.CartComplete);
        Assert.False(sale.JournalComplete);
        Assert.Empty(sale.Changes);
    }

    [Fact]
    public void DeletionAndReaddAllocateNewGenerationEvenWhenNativeLineIdIsReused()
    {
        var journal = Journal(); Show(journal, Line());
        journal.Observe("checkout", _identity, [], _at.AddSeconds(5));
        journal.Observe("checkout", _identity, [Line()], _at.AddSeconds(6));
        var changes = journal.BuildSale("checkout", Paid(Line()), _identity, _at.AddMinutes(5)).Changes;
        Assert.Equal(2, changes.Count);
        Assert.Equal((1m, 0m), (changes[0].BeforeQty, changes[0].AfterQty));
        Assert.Equal((0m, 1m), (changes[1].BeforeQty, changes[1].AfterQty));
        Assert.NotEqual(changes[0].GenerationId, changes[1].GenerationId);
    }

    [Fact]
    public void UnrelatedChangesHaveNoOfferButRemainInTheOrderedJournal()
    {
        var journal = Journal(); Show(journal, Line());
        var other = Line(line: 502) with { Sku = "20", Barcode = "4600000000029" };
        journal.Observe("checkout", _identity, [Line(), other], _at.AddSeconds(10));
        var change = Assert.Single(journal.BuildSale("checkout", Paid(Line(), other), _identity, _at.AddMinutes(5)).Changes);
        Assert.Empty(change.EventId);
        Assert.Equal("4600000000029", change.Barcode);
    }

    [Fact]
    public void ConfirmedSecondaryBarcodeLinksTheActualNativeAdditionWithoutChangingItsCode()
    {
        var journal = Journal();
        const string secondary = "4600000000029";
        journal.RecordShown("checkout", "event-1", "4600000000012", "remote-catalogue-id", _identity, [], _at, true,
            ["4600000000012", secondary]);
        var added = Line() with { Barcode = secondary };
        journal.Observe("checkout", _identity, [added], _at.AddSeconds(40));
        var sale = journal.BuildSale("checkout", Paid(added), _identity, _at.AddMinutes(5));
        Assert.True(sale.JournalComplete);
        var change = Assert.Single(sale.Changes);
        Assert.Equal("event-1", change.EventId);
        Assert.Equal(secondary, change.Barcode);
        Assert.Equal("10", change.Sku); // The native PART_ID never becomes a remote catalogue ID.
    }

    [Fact]
    public void UndeclaredBarcodeDoesNotMatchEvenWhenSkuMatchesTheOffer()
    {
        var journal = Journal();
        journal.RecordShown("checkout", "event-1", "4600000000012", "10", _identity, [], _at, true,
            ["4600000000029"]);
        var other = Line() with { Barcode = "4600000000036" };
        journal.Observe("checkout", _identity, [other], _at.AddSeconds(10));
        Assert.Empty(Assert.Single(journal.BuildSale("checkout", Paid(other), _identity, _at.AddMinutes(5)).Changes).EventId);
    }

    [Fact]
    public void AliasSetIsCopiedAndRepeatShowCannotChangeItOrRefreshTheTimer()
    {
        var journal = Journal();
        var aliases = new List<string> { "4600000000029" };
        var first = journal.RecordShown("checkout", "event-1", "4600000000012", "product", _identity, [], _at, true, aliases)!;
        aliases[0] = "4600000000036";
        var repeat = journal.RecordShown("checkout", "event-1", "4600000000012", "product", _identity, [], _at.AddSeconds(100), true,
            ["4600000000029", "4600000000012", "4600000000029"]);
        Assert.Equal(first.ObservationId, repeat!.ObservationId);
        var added = Line() with { Barcode = "4600000000029" };
        journal.Observe("checkout", _identity, [added], _at.AddSeconds(121));
        var sale = journal.BuildSale("checkout", Paid(added), _identity, _at.AddMinutes(5));
        Assert.True(sale.JournalComplete);
        Assert.Equal("event-1", Assert.Single(sale.Changes).EventId);
        journal.RecordShown("checkout", "event-1", "4600000000012", "product", _identity, [added], _at.AddSeconds(122), true,
            ["4600000000036"]);
        Assert.False(journal.BuildSale("checkout", Paid(added), _identity, _at.AddMinutes(5)).JournalComplete);
    }

    [Theory]
    [InlineData("overflow")]
    [InlineData("long")]
    [InlineData("control")]
    [InlineData("empty")]
    public void InvalidAliasMetadataFailsClosedWithoutInventingACompleteJournal(string mode)
    {
        var journal = Journal();
        var aliases = mode switch {
            "overflow" => Enumerable.Range(0, RecommendationRewardJournal.MaximumOfferBarcodes + 1).Select(i => i.ToString()).ToArray(),
            "long" => new[] { new string('1', 33) }, "control" => new[] { "123\n456" }, _ => new[] { " " },
        };
        Assert.Null(journal.RecordShown("checkout", "event", "4600000000012", "product", _identity, [], _at, true, aliases));
        journal.Observe("checkout", _identity, [Line()], _at.AddSeconds(10));
        Assert.False(journal.BuildSale("checkout", Paid(Line()), _identity, _at.AddMinutes(5)).JournalComplete);
    }

    [Theory]
    [InlineData("{}")]
    [InlineData("{\"recommendBarcodes\":null}")]
    public void OldAndNullAliasResponsesRetainPrimaryBarcodeMatching(string json)
    {
        var recommendation = JsonSerializer.Deserialize<Recommendation>(json, EpharmJson.Options)!;
        var journal = Journal();
        journal.RecordShown("checkout", "event", "4600000000012", "product", _identity, [], _at, true,
            recommendation.RecommendBarcodes);
        journal.Observe("checkout", _identity, [Line()], _at.AddSeconds(10));
        Assert.Equal("event", Assert.Single(journal.BuildSale("checkout", Paid(Line()), _identity, _at.AddMinutes(5)).Changes).EventId);
    }

    [Theory]
    [InlineData("unknown", 1)]
    [InlineData("full_package", 1.5)]
    public void ExactUnknownUnitsAndFractionalAmountsArePreservedForServerQualification(string unit, double qty)
    {
        var journal = Journal(); var line = Line((decimal)qty, unit: unit); var shown = Show(journal, line);
        var sale = journal.BuildSale("checkout", Paid(line), _identity, _at.AddMinutes(5));
        Assert.True(shown.CartComplete); Assert.True(sale.JournalComplete);
        Assert.Equal((decimal)qty, Assert.Single(sale.Completion.Lines).Qty);
        Assert.Equal(unit, Assert.Single(sale.Completion.Lines).UnitSemantics); // A complete observation does not prove whole packages.
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void UnrelatedFractionDoesNotInvalidateExactEvidenceForAnOfferedWholePackage(bool inBaseline)
    {
        var journal = Journal();
        var unrelated = Line(.3m, line: 502, unit: "unknown") with { Sku = "20", Barcode = "4600000000029" };
        var shown = journal.RecordShown("checkout", "event-1", "4600000000012", "product", _identity,
            inBaseline ? [unrelated] : [], _at, true)!;
        journal.Observe("checkout", _identity, [Line(), unrelated], _at.AddSeconds(40));

        var sale = journal.BuildSale("checkout", Paid(Line(), unrelated), _identity, _at.AddMinutes(5));

        Assert.True(shown.CartComplete);
        Assert.True(sale.JournalComplete);
        Assert.Equal("event-1", sale.Changes.Single(change => change.SourceLineId == 501).EventId);
        Assert.Equal(.3m, sale.Completion.Lines.Single(line => line.SourceLineId == 502).Qty);
        Assert.Equal("unknown", sale.Completion.Lines.Single(line => line.SourceLineId == 502).UnitSemantics);
    }

    [Fact]
    public void QuantityOutsideTheServerEvidenceBoundStillFailsCompleteness()
    {
        var journal = Journal();
        var line = Line(1_000_001m);
        Assert.False(Show(journal, line).CartComplete);
        Assert.False(journal.BuildSale("checkout", Paid(line), _identity, _at.AddMinutes(5)).JournalComplete);
    }

    [Theory]
    [InlineData("user")]
    [InlineData("session")]
    [InlineData("workstation")]
    [InlineData("namespace")]
    [InlineData("mismatch")]
    public void ChangedOrMismatchedSourceCannotAutoQualify(string change)
    {
        var journal = Journal(); Show(journal, Line());
        var identity = change switch {
            "user" => _identity with { RawUserId = "18" }, "session" => _identity with { NativeSessionId = 81 },
            "workstation" => _identity with { WorkstationId = "10" }, "namespace" => _identity with { SourceNamespace = "other" },
            _ => _identity with { IdentityStatus = "mismatch" },
        };
        journal.Observe("checkout", identity, [Line(2)], _at.AddSeconds(10));
        Assert.False(journal.BuildSale("checkout", Paid(Line(2)), identity, _at.AddMinutes(5)).JournalComplete);
    }

    [Fact]
    public void MissingSellerCannotBeReplacedByNameOrAnOwnLogin()
    {
        var journal = Journal(); var identity = _identity with { RawUserId = "" };
        Assert.False(journal.RecordShown("checkout", "event", "4600000000012", "10", identity, [Line()], _at, true)!.CartComplete);
        Assert.False(journal.BuildSale("checkout", Paid(Line()), identity, _at.AddMinutes(5)).JournalComplete);
    }

    [Fact]
    public void FinalPhysicalLineMustMatchObservedAdditionAndCannotMoveBetweenDocuments()
    {
        var journal = Journal(); Show(journal, Line());
        journal.Observe("checkout", _identity, [Line(2)], _at.AddSeconds(10));
        Assert.False(journal.BuildSale("checkout", Paid(Line(2, line: 999)), _identity, _at.AddMinutes(5)).JournalComplete);
        Assert.False(journal.BuildSale("checkout", Paid(Line(2, doc: 101)), _identity, _at.AddMinutes(5)).JournalComplete);
        Assert.False(journal.BuildSale("checkout", Paid(Line(3)), _identity, _at.AddMinutes(5)).JournalComplete);
    }

    [Fact]
    public void OneLinkedCheckoutRetainsSeparatePhysicalSaleProofs()
    {
        var journal = Journal(); var child = Line(doc: 101, line: 502);
        Show(journal, Line(), child);
        journal.Observe("checkout", _identity, [Line(2), child with { Qty = 2 }], _at.AddSeconds(10));
        var first = journal.BuildSale("checkout", Paid(Line(2)), _identity, _at.AddMinutes(5));
        var second = journal.BuildSale("checkout", Paid(child with { Qty = 2 }), _identity, _at.AddMinutes(6));
        Assert.True(first.JournalComplete); Assert.True(second.JournalComplete);
        Assert.Equal(first.CheckoutSessionId, second.CheckoutSessionId);
        Assert.Equal(100, first.Completion.SourceDocumentId); Assert.Equal(101, second.Completion.SourceDocumentId);
        Assert.Equal(first.Changes.Select(c => c.ObservationId), second.Changes.Select(c => c.ObservationId));
    }

    [Fact]
    public void UnpostedOrIncompleteDetailsCannotBecomePaidEvidence()
    {
        var journal = Journal(); Show(journal, Line());
        var sale = journal.BuildSale("checkout", Paid(Line()) with { Status = 0 }, _identity, _at.AddMinutes(5));
        Assert.False(sale.JournalComplete); Assert.Equal("unknown", sale.Completion.Status);
        Assert.False(journal.BuildSale("checkout", Paid(Line()) with { FinalDetailsRead = false }, _identity, _at.AddMinutes(5)).JournalComplete);
    }

    [Fact]
    public void RestartPreservesExactTransitionsButUnobservedIntervalRequiresReview()
    {
        var journal = Journal(); Show(journal, Line());
        journal.Observe("checkout", _identity, [Line(2)], _at.AddSeconds(10));
        var before = journal.BuildSale("checkout", Paid(Line(2)), _identity, _at.AddMinutes(5));
        var after = Journal().BuildSale("checkout", Paid(Line(2)), _identity, _at.AddMinutes(5));
        Assert.True(before.JournalComplete); Assert.False(after.JournalComplete);
        Assert.Equal(before.Changes, after.Changes);
    }

    [Fact]
    public void CorruptOrLostJournalCannotManufactureEvidenceOrEraseExistingFiles()
    {
        var journal = Journal(); Show(journal, Line());
        var path = Assert.Single(Directory.GetFiles(_root, "*.json")); File.WriteAllText(path, "corrupt");
        journal.Observe("checkout", _identity, [Line(2)], _at.AddSeconds(10));
        Assert.Equal("corrupt", File.ReadAllText(path));
        Assert.False(journal.BuildSale("checkout", Paid(Line(2)), _identity, _at.AddMinutes(5)).JournalComplete);
        File.Delete(path);
        Assert.False(Journal().BuildSale("checkout", Paid(Line(2)), _identity, _at.AddMinutes(5)).JournalComplete);
    }

    [Fact]
    public void ObservationFailureIsStickyAndRetirementOnlyRemovesItsOwnCheckout()
    {
        var journal = Journal(); Show(journal, Line());
        journal.MarkIncomplete("checkout"); journal.Observe("checkout", _identity, [Line(2)], _at.AddSeconds(10));
        Assert.False(journal.BuildSale("checkout", Paid(Line(2)), _identity, _at.AddMinutes(5)).JournalComplete);
        journal.Observe("other", _identity, [Line()], _at);
        journal.Retire("checkout");
        Assert.Single(Directory.GetFiles(_root, "*.json"));
        Assert.True(journal.BuildSale("other", Paid(Line()), _identity, _at.AddMinutes(5)).JournalComplete);
    }

    [Fact]
    public void CheckoutAndTransitionBoundsPreservePriorEvidenceAndFailClosed()
    {
        var journal = Journal();
        for (var i = 0; i < RecommendationRewardJournal.MaximumCheckouts; i++) journal.Observe("c" + i, _identity, [Line()], _at);
        var originals = Directory.GetFiles(_root, "*.json").ToDictionary(path => Path.GetFileName(path)!, File.ReadAllText);
        journal.Observe("overflow", _identity, [Line()], _at);
        Assert.Equal(RecommendationRewardJournal.MaximumCheckouts, Directory.GetFiles(_root, "*.json").Length);
        Assert.False(journal.BuildSale("overflow", Paid(Line()), _identity, _at.AddMinutes(5)).JournalComplete);
        Assert.All(Directory.GetFiles(_root, "*.json"), path => Assert.Equal(originals[Path.GetFileName(path)], File.ReadAllText(path)));
    }

    [Fact]
    public void TransitionBoundDoesNotDropOldChangesOrClaimCompleteEvidenceAfterOverflow()
    {
        var journal = Journal();
        RewardCartLine[] Cart(int quantity) => Enumerable.Range(1, 512).Select(id => Line(quantity, line: id)).ToArray();
        Show(journal, Cart(1));
        for (var quantity = 2; quantity <= 6; quantity++) journal.Observe("checkout", _identity, Cart(quantity), _at.AddSeconds(quantity));
        var evidence = journal.BuildSale("checkout", Paid(Cart(6)), _identity, _at.AddMinutes(5));
        Assert.False(evidence.JournalComplete);
        Assert.Equal(RecommendationRewardJournal.MaximumChanges, evidence.Changes.Count);
        Assert.Equal(1, evidence.Changes[0].Sequence);
        Assert.Equal(RecommendationRewardJournal.MaximumChanges, evidence.Changes[^1].Sequence);
    }

    [Fact]
    public void PrivateJournalRejectsLinkedStorageAndCreatesNoWorldReadableEvidence()
    {
        var journal = Journal(); Show(journal, Line());
        if (!OperatingSystem.IsWindows())
        {
            var directoryMode = File.GetUnixFileMode(_root);
            var file = Assert.Single(Directory.GetFiles(_root, "*.json"));
            Assert.Equal(UnixFileMode.None, directoryMode & (UnixFileMode.GroupRead | UnixFileMode.OtherRead | UnixFileMode.GroupWrite | UnixFileMode.OtherWrite));
            Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite, File.GetUnixFileMode(file));
            var preserved = File.ReadAllText(file);
            var target = Path.Combine(_root, "original.txt"); File.Move(file, target); File.CreateSymbolicLink(file, target);
            journal.Observe("checkout", _identity, [Line(2)], _at.AddSeconds(5));
            Assert.Equal(preserved, File.ReadAllText(target));
            Assert.False(journal.BuildSale("checkout", Paid(Line(2)), _identity, _at.AddMinutes(5)).JournalComplete);
        }
    }

    [Fact]
    public void SourceDigestSeparatesLocalDatabasesWithoutLeakingHostOrPath()
    {
        var first = RecommendationRewardJournal.SourceNamespace("localhost", 3050, @"C:\native\example.fdb", "cash-1");
        Assert.Matches("^[a-f0-9]{64}$", first);
        Assert.DoesNotContain("native", first); Assert.DoesNotContain("cash", first);
        Assert.Equal(first, RecommendationRewardJournal.SourceNamespace("127.0.0.1", 3050, "c:/NATIVE/example.fdb", "CASH-1"));
        Assert.NotEqual(first, RecommendationRewardJournal.SourceNamespace("localhost", 3050, @"C:\native\example.fdb", "cash-2"));
        Assert.NotEqual(first, RecommendationRewardJournal.SourceNamespace("localhost", 3050, @"C:\native\other.fdb", "cash-1"));
    }

    [Fact]
    public void LegacyWireOmitsNewFieldsAndOfflineReplayFreezesExactEvidence()
    {
        Assert.Null(new EpharmConfig().RecommendationRewardsEnabled);
        Assert.DoesNotContain("rewardEvidence", JsonSerializer.Serialize(new MarkShownRequest(), EpharmJson.Options));
        Assert.DoesNotContain("rewardEvidence", JsonSerializer.Serialize(new SaleReport(), EpharmJson.Options));
        var journal = Journal(); var shown = Show(journal, Line());
        journal.Observe("checkout", _identity, [Line(2)], _at.AddSeconds(10));
        var sale = new SaleReport { SaleId = "sale-synthetic", RewardEvidence = journal.BuildSale("checkout", Paid(Line(2)), _identity, _at.AddMinutes(5)) };
        var queue = new OfflineOutbox(Path.Combine(_root, "outbox.db"));
        queue.Enqueue("shown_event-1", "shown", JsonSerializer.Serialize(new OutboxShownPayload { EventId = "event-1", ShownAt = _at, RewardEvidence = shown }, EpharmJson.Options));
        queue.Enqueue(sale.SaleId, "sale", JsonSerializer.Serialize(sale, EpharmJson.Options));
        queue.Enqueue(sale.SaleId, "sale", JsonSerializer.Serialize(new SaleReport { SaleId = sale.SaleId }, EpharmJson.Options));
        journal.Retire("checkout");
        var replay = new OfflineOutbox(Path.Combine(_root, "outbox.db")).DequeueReady(10);
        Assert.Equal(2, replay.Count);
        Assert.Equal(shown.ObservationId, JsonSerializer.Deserialize<OutboxShownPayload>(replay.Single(item => item.Kind == "shown").Payload, EpharmJson.Options)!.RewardEvidence!.ObservationId);
        var retained = JsonSerializer.Deserialize<SaleReport>(replay.Single(item => item.Kind == "sale").Payload, EpharmJson.Options)!;
        Assert.True(retained.RewardEvidence!.JournalComplete); Assert.Single(retained.RewardEvidence.Changes);
    }

    public void Dispose()
    {
        Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
        if (Directory.Exists(_root)) Directory.Delete(_root, true);
    }
}
