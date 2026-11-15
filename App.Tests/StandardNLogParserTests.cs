using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class StandardNLogParserTests
{
    private const string PrintBegin = "0 . . RunScriptByIndex: Перед печатью очереди чеков";
    private const string PrintEnd = "203 . . RunScriptByIndex: После печати очереди чеков";

    [Fact]
    public void ExactCompletedQueueKeepsOnlyItsOwnDistinctPhysicalDocumentIds()
    {
        var batch = new StandardNPrintLogBatch();
        batch.Observe("Destroy Cheque ID=50"); batch.Observe(PrintBegin);
        batch.Observe("0 . . Destroy Cheque ID=23660"); batch.Observe("281 . . Destroy Cheque ID=23660");
        batch.Observe("Destroy Cheque ID=-1");
        Assert.Equal(new long[] { 23660 }, batch.Observe(PrintEnd)!.Order());
        Assert.Null(batch.Observe(PrintEnd)); // Replaying completion alone cannot reuse the old IDs.
    }

    [Fact]
    public void ProductTextCannotPretendToBeAPrintQueueCallbackOrPhysicalDocument()
    {
        var batch = new StandardNPrintLogBatch();
        batch.Observe("Add2Cheque sname=RunScriptByIndex: Перед печатью очереди чеков");
        batch.Observe("Destroy Cheque ID=100"); Assert.Null(batch.Observe(PrintEnd));
        batch.Observe(PrintBegin); batch.Observe("Add2Cheque sname=Destroy Cheque ID=100");
        Assert.Null(batch.Observe(PrintEnd));
    }

    [Theory]
    [InlineData("PrintChequeList stop end")]
    [InlineData("PrintChequeList results: 58875")]
    [InlineData("PrintChequeList error")]
    [InlineData("Destroy Cheque ID=not-a-document")]
    [InlineData("Destroy Cheque ID=-2")]
    [InlineData("Destroy Cheque ID=0")]
    [InlineData("Destroy Cheque ID=9223372036854775808")]
    public void IncompleteOrFailedQueueNeverLeaksItsIdsIntoALaterCompletion(string interrupted)
    {
        var batch = new StandardNPrintLogBatch(); batch.Observe(PrintBegin); batch.Observe("Destroy Cheque ID=100");
        batch.Observe(interrupted); Assert.Null(batch.Observe(PrintEnd));
        batch.Observe(PrintBegin); batch.Observe("Destroy Cheque ID=200");
        Assert.Equal(new long[] { 200 }, batch.Observe(PrintEnd)!.Order());
    }

    [Fact]
    public void QueueRestartRotationAndEvidenceBoundsDiscardIncompleteBatches()
    {
        var batch = new StandardNPrintLogBatch(); batch.Observe(PrintBegin); batch.Observe("Destroy Cheque ID=100");
        batch.Observe(PrintBegin); batch.Observe("Destroy Cheque ID=200");
        Assert.Equal(new long[] { 200 }, batch.Observe(PrintEnd)!.Order());
        batch.Observe(PrintBegin); batch.Observe("Destroy Cheque ID=300"); batch.Reset();
        Assert.Null(batch.Observe(PrintEnd));
        batch.Observe(PrintBegin); batch.Observe("Destroy Cheque ID=400");
        for (var i = 0; i <= StandardNPrintLogBatch.MaximumLines; i++) batch.Observe("bounded vendor trace");
        Assert.Null(batch.Observe(PrintEnd));
        batch.Observe(PrintBegin);
        for (var i = 1; i <= StandardNReceiptProjection.MaxDocuments + 1; i++) batch.Observe("Destroy Cheque ID=" + i);
        Assert.Null(batch.Observe(PrintEnd));
    }

    [Fact]
    public void NativePrintAgeExceptionRequiresExactBoundedDocumentParameters()
    {
        var legacy = StandardNDbLookup.NativeCashPrintSql(); var exact = StandardNDbLookup.NativeCashPrintSql(2);
        Assert.Contains("DATEADD(-5 SECOND TO CURRENT_TIMESTAMP)", legacy);
        Assert.Contains("AUDIT_ID=@session", exact); Assert.Contains("ID in (@doc0,@doc1)", exact);
        Assert.Contains("STATUS", exact); Assert.Contains("DOC_TYPE in (3,9)", exact); Assert.DoesNotContain("DATEADD", exact);
        Assert.Throws<ArgumentOutOfRangeException>(() => StandardNDbLookup.NativeCashPrintSql(501));
    }

    [Fact]
    public void ParsesExistingCompactAdd2ChequeFormat()
    {
        var item = StandardNLogParser.TryParseAdd2Cheque(
            "Add2Cheque iPartID=358546(4601164003164);sname=Жидкий уголь комплекс с пектином саше детс 7г №10;price=4455,00;quant=1");

        Assert.NotNull(item);
        Assert.Equal(358546, item.PartId);
        Assert.Equal("4601164003164", item.Barcode);
        Assert.Equal("Жидкий уголь комплекс с пектином саше детс 7г №10", item.Name);
        Assert.Equal(4455m, item.Price);
        Assert.Equal(1m, item.Qty);
    }

    [Fact]
    public void ParsesWhitespaceColonQuotesAndDifferentFieldOrder()
    {
        var item = StandardNLogParser.TryParseAdd2Cheque(
            "ADD2CHEQUE QUANT : '1,000'; PRICE = \"4 455,00\"; SName : 'Жидкий уголь комплекс с пектином саше детс 7г №10'; iPartID = 358546 ( 4601164003164 )");

        Assert.NotNull(item);
        Assert.Equal(358546, item.PartId);
        Assert.Equal("4601164003164", item.Barcode);
        Assert.Equal("Жидкий уголь комплекс с пектином саше детс 7г №10", item.Name);
        Assert.Equal(4455m, item.Price);
        Assert.Equal(1m, item.Qty);
    }

    [Fact]
    public void ExplicitBarcodeFieldWinsAndMayUseManufacturerColumnName()
    {
        var item = StandardNLogParser.TryParseAdd2Cheque(
            "Add2Cheque iPartID:358546(358546);quant:1;sname:Жидкий уголь;barcode = \"4601164003164\";BCODE_IZG : 4603423001072");

        Assert.NotNull(item);
        Assert.Equal("4601164003164", item.Barcode);
    }

    [Fact]
    public void MissingOptionalNameAndPriceStillProducesScanIdentity()
    {
        var item = StandardNLogParser.TryParseAdd2Cheque(
            "Add2Cheque quant = 1; iPartID = 358546");

        Assert.NotNull(item);
        Assert.Equal(358546, item.PartId);
        Assert.Equal("", item.Name);
        Assert.Equal(0m, item.Price);
        Assert.Null(item.Barcode);
    }

    [Fact]
    public void DuplicatePartIdInParenthesesIsNotTreatedAsBarcode()
    {
        var item = StandardNLogParser.TryParseAdd2Cheque(
            "Add2Cheque iPartID=358546(358546);sname=Жидкий уголь;quant=1");

        Assert.NotNull(item);
        Assert.Null(item.Barcode);
    }

    [Fact]
    public void TwoIdenticalParsedRecordsRemainTwoExplicitScanEvents()
    {
        const string line =
            "Add2Cheque iPartID=358546(4601164003164);sname=Жидкий уголь;price=4455;quant=1";
        var first = StandardNLogParser.TryParseAdd2Cheque(line);
        var second = StandardNLogParser.TryParseAdd2Cheque(line);

        Assert.NotNull(first);
        Assert.NotNull(second);
        Assert.Equal(
            ReceiptRecommendationAction.Refresh,
            ReceiptRecommendationChange.ClassifyExplicitAdd(
                existed: false,
                previousQty: 0,
                previousBarcode: null,
                previousName: null,
                nextQty: first.Qty,
                nextBarcode: first.Barcode,
                nextName: first.Name));
        Assert.Equal(
            ReceiptRecommendationAction.Refresh,
            ReceiptRecommendationChange.ClassifyExplicitAdd(
                existed: true,
                previousQty: first.Qty,
                previousBarcode: first.Barcode,
                previousName: first.Name,
                nextQty: second.Qty,
                nextBarcode: second.Barcode,
                nextName: second.Name));
    }

    [Theory]
    [InlineData("Add2Cheque iPartID = 358546; sname=Жидкий уголь")]
    [InlineData("Add2Cheque quant = 1; sname=Жидкий уголь")]
    [InlineData("Add2Cheque iPartID = 358546; quant = 0")]
    [InlineData("Add2Cheque iPartID = 358546; quant = -1")]
    [InlineData("Add2Cheque iPartID = 358546; quant = 1 (delete)")]
    [InlineData("unrelated iPartID = 358546; quant = 1")]
    public void RejectsLinesWithoutRequiredAddEventFields(string line)
    {
        Assert.Null(StandardNLogParser.TryParseAdd2Cheque(line));
    }

    [Fact]
    public void DeletePathUsesSameTolerantPartIdSyntax()
    {
        Assert.Equal(358546, StandardNLogParser.TryExtractPartId("Add2Cheque iPartID : '358546' (delete)"));
    }
}
