using System.Data;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class StandardNUnitAndFinalLineTests
{
    [Theory]
    [InlineData(-1, "1", "full_package")]
    [InlineData(-2, "2", "full_package")]
    [InlineData(1, "1", "full_package")]
    [InlineData(-1, " 1 ", "full_package")]
    [InlineData(-0.3, "3/10", "unknown")]
    [InlineData(-0.3333333333333333, "10/30", "unknown")]
    [InlineData(-1, "21/21", "unknown")]
    [InlineData(-1, "1.0", "unknown")]
    [InlineData(-1, "1,0", "unknown")]
    [InlineData(-1, "-1", "unknown")]
    [InlineData(-1, "2", "unknown")]
    [InlineData(-1, null, "unknown")]
    [InlineData(-1, "", "unknown")]
    [InlineData(-1, "одна упаковка", "unknown")]
    [InlineData(-1, "١", "unknown")]
    [InlineData(0, "0", "unknown")]
    public void UnitRequiresNativeWholeHumanQuantityAgreeingWithUnchangedRawQuantity(double quantity, string? human, string expected) =>
        Assert.Equal(expected, StandardNQuantityUnits.Classify((decimal)quantity, human));

    [Fact]
    public void RealReaderProjectsNativeHumanQuantityWithoutChangingRawSignsAmountsOrFractions()
    {
        var table = new DataTable();
        foreach (var column in new[] { "ID", "DOC_ID", "PART_ID" }) table.Columns.Add(column, typeof(long));
        foreach (var column in new[] { "PRICE", "QUANT", "SUMMA" }) table.Columns.Add(column, typeof(decimal));
        table.Columns.Add("HUMAN_QUANT", typeof(string));
        table.Columns.Add("KOEF", typeof(int));
        table.Rows.Add(61043, 23651, 91631, 3195m, -1m, -3195m, "1", 1);
        table.Rows.Add(61044, 23651, 91631, 3195m, -0.3m, -958.5m, "3/10", 1);
        using var reader = table.CreateDataReader();
        Assert.True(reader.Read()); var whole = StandardNReceiptProjection.Line(reader);
        Assert.Equal("full_package", whole.UnitSemantics); Assert.Equal(1m, whole.Qty);
        Assert.Equal(-1m, whole.SignedQuantity); Assert.Equal(-3195m, whole.SignedAmount);
        Assert.True(reader.Read()); var fraction = StandardNReceiptProjection.Line(reader);
        Assert.Equal("unknown", fraction.UnitSemantics); Assert.Equal(0.3m, fraction.Qty);
        Assert.Equal("3/10", fraction.HumanQuantity); Assert.Equal(958.5m, fraction.SourceTotal);
    }

    [Fact]
    public void OptionalPostedUnitColumnPreservesOlderSchemaAndNeverSelectsPatientData()
    {
        var legacy = StandardNReceiptProjection.PostedLinesSql(1);
        Assert.DoesNotContain("HUMAN_QUANT", legacy);
        var current = StandardNReceiptProjection.PostedLinesSql(1, true);
        Assert.Contains("l.HUMAN_QUANT", current);
        Assert.DoesNotContain("FIO", current); Assert.DoesNotContain("RECEPT", current);
    }

    private static StandardNReceiptLine Line(long id, long doc = 100, int part = 10, decimal quantity = 1, decimal price = 100) =>
        new(id, part, "4600000000012", "Synthetic product", price, quantity, 0) {
            SourceDocumentId = doc, SourceTotal = price * quantity, SignedQuantity = -quantity,
            SignedPrice = price, SignedAmount = -price * quantity, HumanQuantity = quantity.ToString(System.Globalization.CultureInfo.InvariantCulture),
            UnitSemantics = "full_package",
        };

    [Fact]
    public void LinkerKeepsBothRealIdsAndAllowsOverlappingNativeIdDomains()
    {
        var before = new[] { Line(10), Line(20, part: 11, price: 200) };
        var posted = new[] { Line(20), Line(30, part: 11, price: 200) };
        var linked = StandardNFinalLineLinker.Link(100, posted, before);
        Assert.Equal(new long[] { 20, 30 }, linked.Select(line => line.LineId));
        Assert.Equal(new long?[] { 10, 20 }, linked.Select(line => line.ObservedSourceLineId));
        Assert.All(linked, line => Assert.Equal(StandardNFinalLineLinker.Source, line.LineLinkSource));
    }

    [Fact]
    public void PersistedPostedSnapshotKeepsOriginalActiveAliasOnRepeatedFinalRead()
    {
        var raw = new[] { Line(42375) };
        var first = StandardNFinalLineLinker.Link(100, raw, [Line(61043)]);
        var persisted = System.Text.Json.JsonSerializer.Deserialize<StandardNReceiptLine[]>(
            System.Text.Json.JsonSerializer.Serialize(first))!;
        var retried = StandardNFinalLineLinker.Link(100, raw, persisted);
        Assert.Equal(42375, Assert.Single(retried).LineId);
        Assert.Equal(61043, retried[0].ObservedSourceLineId);
        Assert.Equal(StandardNFinalLineLinker.Source, retried[0].LineLinkSource);
        Assert.Equal(first, retried);
    }

    [Fact]
    public void FinalReaderRejectsRawPostedRowsAsActiveObservationsInsteadOfSelfLinking()
    {
        var posted = new StandardNReceiptDocument(100, 80, 100, 1, -300, "register", 1, 1,
            [Line(42392), Line(42393, part: 11, price: 200)]) { FinalDetailsRead = true };
        var observations = StandardNDbLookup.FinalLineObservations(posted);
        Assert.Empty(observations);
        Assert.All(StandardNFinalLineLinker.Link(100, posted.Lines, observations), line => {
            Assert.Null(line.ObservedSourceLineId); Assert.Null(line.LineLinkSource);
        });
    }

    [Fact]
    public void FinalReaderPreservesOriginalActiveIdsAndBarcodeAcrossPostedRetry()
    {
        var active = new StandardNReceiptDocument(100, 80, 100, 0, -300, null, null, null,
            [Line(61059), Line(61060, part: 11, price: 200)]);
        var rawPosted = new[] { Line(42392) with { Barcode = "1234567890128" },
            Line(42393, part: 11, price: 200) with { Barcode = "1234567890135" } };
        var observations = StandardNDbLookup.FinalLineObservations(active);
        var first = StandardNFinalLineLinker.Link(100, rawPosted, observations);
        var finalized = active with { Status = 1, FinalDetailsRead = true, Lines = first };
        var retried = StandardNFinalLineLinker.Link(100, rawPosted, StandardNDbLookup.FinalLineObservations(finalized));
        Assert.Equal(new long?[] { 61059, 61060 }, retried.Select(line => line.ObservedSourceLineId));
        Assert.Equal(new long[] { 42392, 42393 }, retried.Select(line => line.LineId));
        Assert.Equal(first, retried);
        Assert.Equal("4600000000012", active.Lines[0].Barcode);
    }

    [Theory]
    [InlineData("other-document")]
    [InlineData("other-product")]
    [InlineData("changed-quantity")]
    [InlineData("changed-price")]
    [InlineData("changed-total")]
    [InlineData("changed-discount")]
    [InlineData("changed-human")]
    [InlineData("missing-human")]
    [InlineData("duplicate-observed")]
    [InlineData("duplicate-posted")]
    public void LinkerRejectsAmbiguousOrChangedPhysicalRows(string reason)
    {
        var before = new[] { Line(61043) };
        var posted = new[] { Line(42367) };
        posted[0] = reason switch {
            "other-document" => posted[0] with { SourceDocumentId = 101 },
            "other-product" => posted[0] with { PartId = 11 },
            "changed-quantity" => posted[0] with { Qty = 2 },
            "changed-price" => posted[0] with { Price = 101 },
            "changed-total" => posted[0] with { SourceTotal = 99 },
            "changed-discount" => posted[0] with { DiscountPercent = 1 },
            "changed-human" => posted[0] with { HumanQuantity = "21/21" },
            "missing-human" => posted[0] with { HumanQuantity = null },
            _ => posted[0],
        };
        if (reason == "duplicate-observed") before = [before[0], before[0] with { LineId = 61044 }];
        if (reason == "duplicate-posted") posted = [posted[0], posted[0] with { LineId = 42368 }];
        Assert.All(StandardNFinalLineLinker.Link(100, posted, before), line => {
            Assert.Null(line.ObservedSourceLineId); Assert.Null(line.LineLinkSource);
        });
    }

    [Fact]
    public void JournalPaidEvidenceUsesProvenAliasWithoutReplacingPostedId()
    {
        var root = Path.Combine(Path.GetTempPath(), "native-final-link-" + Guid.NewGuid().ToString("N"));
        try
        {
            var journal = new RecommendationRewardJournal(root); var now = DateTimeOffset.UtcNow;
            var identity = new RewardSourceIdentity { SourceNamespace = new string('a', 64), RawUserId = "17", NativeSessionId = 80, WorkstationId = "9" };
            journal.RecordShown("checkout", "event", "4600000000012", "catalogue", identity, [], now, true);
            journal.Observe("checkout", identity, [new RewardCartLine { SourceDocumentId = 100, SourceLineId = 61043,
                Sku = "10", Barcode = "4600000000012", Qty = 1, UnitSemantics = "full_package" }], now.AddSeconds(10));
            var posted = StandardNFinalLineLinker.Link(100, [Line(42367)], [Line(61043)]);
            var document = new StandardNReceiptDocument(100, 80, 100, 1, -100, "register", 1, 1, posted) {
                FinalDetailsRead = true, FinalLineLinkRequired = true,
            };
            var evidence = journal.BuildSale("checkout", document, identity, now.AddMinutes(5));
            Assert.True(evidence.JournalComplete);
            var paid = Assert.Single(evidence.Completion.Lines);
            Assert.Equal(42367, paid.SourceLineId); Assert.Equal(61043, paid.ObservedSourceLineId);
            Assert.Equal(StandardNFinalLineLinker.Source, paid.LineLinkSource);
            var ambiguous = document with { Lines = [Line(61043)] };
            Assert.False(journal.BuildSale("checkout", ambiguous, identity, now.AddMinutes(5)).JournalComplete);
        }
        finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }
}
