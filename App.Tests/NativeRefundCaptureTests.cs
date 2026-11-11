using CustomerDisplay.Config;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class NativeRefundCaptureTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "native-refunds-" + Guid.NewGuid().ToString("N"));
    private readonly DateTimeOffset _at = DateTimeOffset.UtcNow.AddMinutes(-5);
    private NativeRefundCapture Capture() => new(_root, "pharmacy", "cash-1");
    private NativeRefundObservation Refund(long id, bool posted = true, bool original = true)
    {
        var line = new StandardNReceiptLine(70000 + id, 10, "4600000000012", "Synthetic product", 100, 1, 0) {
            SourceDocumentId = id, SourceTotal = 100, SignedQuantity = 1, SignedAmount = 100, SignedPrice = 100,
            HumanQuantity = "1", UnitSemantics = "full_package",
        };
        var document = new StandardNReceiptDocument(id, 80, id, posted ? 1 : 0, 100, "refund-register", id, 7, [line]) {
            DocumentType = 9, ParentDocumentId = 90, FinalDetailsRead = posted,
            PostedAt = posted ? _at : null, CommittedAt = posted ? _at.AddMilliseconds(-700) : null,
            OriginalReceipt = original ? new(90, 3, 1, "original-register", 500, 6) : null,
        };
        return new(id, true, document, new RewardSourceIdentity { SourceNamespace = new string('a', 64),
            RawUserId = "17", NativeSessionId = 80, WorkstationId = "9" });
    }

    [Fact]
    public void DefaultsOffAndFirstActivationNeverBackfillsHistoricalReturns()
    {
        Assert.False(new EpharmConfig().NativeRefundCaptureEnabled);
        var source = new Source(); source.Rows[90] = Refund(90);
        var reports = new List<SaleReport>(); var capture = Capture();
        capture.Poll(source, reports.Add, TestContext.Current.CancellationToken);
        Assert.Empty(reports); Assert.Single(Directory.GetFiles(_root, "*.json"));
        source.Maximum = 101; source.Rows[101] = Refund(101);
        capture.Poll(source, reports.Add, TestContext.Current.CancellationToken);
        Assert.Equal(101, Assert.Single(reports).SourceDocumentId);
    }

    [Fact]
    public void LatePostedPendingHoleSurvivesNewerRefundAndProcessRestart()
    {
        var source = new Source(); var reports = new List<SaleReport>(); var capture = Capture();
        capture.Poll(source, reports.Add, TestContext.Current.CancellationToken);
        source.Maximum = 101; source.Rows[101] = Refund(101, posted: false); capture.Poll(source, reports.Add, TestContext.Current.CancellationToken);
        Assert.Empty(reports);
        source.Maximum = 102; source.Rows[102] = Refund(102); capture.Poll(source, reports.Add, TestContext.Current.CancellationToken);
        Assert.Equal(102, Assert.Single(reports).SourceDocumentId);
        source.Rows[101] = Refund(101);
        Capture().Poll(source, reports.Add, TestContext.Current.CancellationToken);
        Assert.Equal(new long?[] { 102, 101 }, reports.Select(report => report.SourceDocumentId));
        Capture().Poll(source, reports.Add, TestContext.Current.CancellationToken);
        Assert.Equal(2, reports.Count);
    }

    [Fact]
    public void AlreadyPostedNewReturnIsCapturedWithoutAnyActiveSnapshotOrGenericPrintCue()
    {
        var source = new Source(); var reports = new List<SaleReport>(); var capture = Capture();
        capture.Poll(source, reports.Add, TestContext.Current.CancellationToken); source.Maximum = 101; source.Rows[101] = Refund(101);
        capture.Poll(source, reports.Add, TestContext.Current.CancellationToken);
        var report = Assert.Single(reports); var completion = report.RewardEvidence!.Completion;
        Assert.Equal(NativeRefundCapture.CaptureSource, report.CaptureSource);
        Assert.Equal(NativeRefundCapture.EvidenceSource, completion.Source);
        Assert.Equal("refund", completion.Status); Assert.Equal(9, completion.DocumentType);
        Assert.Equal(_at, report.PrintedAt); Assert.Equal(_at, completion.CompletedAt);
        Assert.Equal("refund-register", completion.DeviceNumber);
        Assert.Equal("original-register", completion.OriginalDeviceNumber);
        Assert.Equal(90, completion.OriginalSourceDocumentId); Assert.Equal(500, completion.OriginalFiscalNumber);
        Assert.False(report.RewardEvidence.JournalComplete); Assert.Empty(report.RewardEvidence.Changes);
    }

    [Fact]
    public void UnknownOriginalStillUploadsForHqWithoutInventingOriginOrRegister()
    {
        var source = new Source(); var reports = new List<SaleReport>(); var capture = Capture();
        capture.Poll(source, reports.Add, TestContext.Current.CancellationToken); source.Maximum = 101; source.Rows[101] = Refund(101, original: false);
        capture.Poll(source, reports.Add, TestContext.Current.CancellationToken);
        var completion = Assert.Single(reports).RewardEvidence!.Completion;
        Assert.Equal("refund", completion.Status); Assert.Null(completion.OriginalSourceDocumentId);
        Assert.Null(completion.OriginalFiscalNumber); Assert.Null(completion.OriginalDeviceNumber);
    }

    [Fact]
    public void ProvenPhysicalOriginalDoesNotLeakAnIncompleteFiscalTuple()
    {
        var source = new Source(); var reports = new List<SaleReport>(); var capture = Capture();
        capture.Poll(source, reports.Add, TestContext.Current.CancellationToken);
        var row = Refund(101); source.Maximum = 101;
        source.Rows[101] = row with { Document = row.Document! with {
            OriginalReceipt = new(90, 3, 1, null, 500, 6),
        } };
        capture.Poll(source, reports.Add, TestContext.Current.CancellationToken);
        var completion = Assert.Single(reports).RewardEvidence!.Completion;
        Assert.Equal(90, completion.OriginalSourceDocumentId);
        Assert.Null(completion.OriginalDeviceNumber); Assert.Null(completion.OriginalFiscalNumber); Assert.Null(completion.OriginalFiscalShift);
    }

    [Fact]
    public void EnqueueFailureCannotAdvanceCursorAndReplayUsesTheSamePhysicalSaleId()
    {
        var source = new Source(); var capture = Capture(); capture.Poll(source, _ => { }, TestContext.Current.CancellationToken);
        source.Maximum = 101; source.Rows[101] = Refund(101);
        SaleReport? attempted = null;
        Assert.Throws<IOException>(() => capture.Poll(source, report => { attempted = report; throw new IOException("queue full"); }, TestContext.Current.CancellationToken));
        var reports = new List<SaleReport>(); Capture().Poll(source, reports.Add, TestContext.Current.CancellationToken);
        Assert.Equal(attempted!.SaleId, Assert.Single(reports).SaleId);
        Assert.Equal(attempted.PrintedAt, reports[0].PrintedAt);
        Capture().Poll(source, reports.Add, TestContext.Current.CancellationToken); Assert.Single(reports);
    }

    [Fact]
    public void CancellationAfterEnqueueReplaysIdempotentlyRatherThanLosingTheReturn()
    {
        var source = new Source(); var capture = Capture(); capture.Poll(source, _ => { }, TestContext.Current.CancellationToken);
        source.Maximum = 101; source.Rows[101] = Refund(101);
        using var cancellation = new CancellationTokenSource(); var reports = new List<SaleReport>();
        Assert.Throws<OperationCanceledException>(() => capture.Poll(source, report => { reports.Add(report); cancellation.Cancel(); }, cancellation.Token));
        Capture().Poll(source, reports.Add, TestContext.Current.CancellationToken);
        Assert.Equal(2, reports.Count); Assert.Equal(reports[0].SaleId, reports[1].SaleId);
    }

    [Fact]
    public void FailedBaselineWriteMustBeRetriedBeforeAnyScan()
    {
        var source = new Source(); var capture = Capture();
        Directory.Delete(_root); File.WriteAllText(_root, "blocked evidence directory");
        Assert.ThrowsAny<IOException>(() => capture.Poll(source, _ => throw new Exception("must not enqueue"), TestContext.Current.CancellationToken));
        Assert.Equal(0, source.Reads);
        File.Delete(_root); Directory.CreateDirectory(_root);
        source.Maximum = 200; source.Rows[150] = Refund(150);
        var reports = new List<SaleReport>(); capture.Poll(source, reports.Add, TestContext.Current.CancellationToken);
        Assert.Equal(2, source.Baselines); Assert.Empty(reports);
    }

    [Fact]
    public void CorruptCursorIsNeverResetToZeroOrOverwritten()
    {
        var source = new Source(); Capture().Poll(source, _ => { }, TestContext.Current.CancellationToken);
        var path = Assert.Single(Directory.GetFiles(_root, "*.json")); File.WriteAllText(path, "damaged");
        source.Maximum = 101; source.Rows[101] = Refund(101);
        var reports = new List<SaleReport>();
        Assert.ThrowsAny<Exception>(() => Capture().Poll(source, reports.Add, TestContext.Current.CancellationToken));
        Assert.Equal("damaged", File.ReadAllText(path)); Assert.Empty(reports);
    }

    [Fact]
    public void SourceRestoreOrSourceChangeNeverReplaysOldHistory()
    {
        var source = new Source(); var capture = Capture(); var reports = new List<SaleReport>(); capture.Poll(source, reports.Add, TestContext.Current.CancellationToken);
        source.Maximum = 99;
        Assert.Throws<InvalidDataException>(() => capture.Poll(source, reports.Add, TestContext.Current.CancellationToken)); Assert.Empty(reports);
        source.Maximum = 200; source.Namespace = new string('b', 64); source.Rows[101] = Refund(101);
        capture.Poll(source, reports.Add, TestContext.Current.CancellationToken); // Notice different source, drop only in-memory binding.
        capture.Poll(source, reports.Add, TestContext.Current.CancellationToken); // New durable source cutoff is 200, not zero.
        Assert.Empty(reports); Assert.Equal(2, Directory.GetFiles(_root, "*.json").Length);
    }

    [Fact]
    public void FullPendingBoundStopsBeforeTheUnretainedHoleAndRecoversAfterOneResolves()
    {
        var source = new Source(); var capture = Capture(); var reports = new List<SaleReport>(); capture.Poll(source, reports.Add, TestContext.Current.CancellationToken);
        source.Maximum = 357;
        for (var id = 101; id <= 357; id++) source.Rows[id] = Refund(id, posted: false);
        for (var pass = 0; pass < 4; pass++) capture.Poll(source, reports.Add, TestContext.Current.CancellationToken);
        Assert.Throws<InvalidDataException>(() => capture.Poll(source, reports.Add, TestContext.Current.CancellationToken));
        Assert.Equal(356, source.RequestedAfter[^1]); Assert.Empty(reports);
        source.Rows[101] = Refund(101); capture.Poll(source, reports.Add, TestContext.Current.CancellationToken);
        Assert.Single(reports);
        source.Rows[357] = Refund(357); capture.Poll(source, reports.Add, TestContext.Current.CancellationToken);
        Assert.Equal(new long?[] { 101, 357 }, reports.Select(report => report.SourceDocumentId));
    }

    [Theory]
    [InlineData("unposted")]
    [InlineData("details")]
    [InlineData("sale-type")]
    [InlineData("negative-quantity")]
    [InlineData("zero-total")]
    [InlineData("no-fiscal")]
    [InlineData("no-time")]
    [InlineData("fractional-money")]
    public void UnprovenNativeRefundIsRetainedInsteadOfFalsePaymentOrOriginalHold(string problem)
    {
        var source = new Source(); var capture = Capture(); var reports = new List<SaleReport>(); capture.Poll(source, reports.Add, TestContext.Current.CancellationToken);
        var row = Refund(101); var document = row.Document!;
        document = problem switch {
            "unposted" => document with { Status = 0 }, "details" => document with { FinalDetailsRead = false },
            "sale-type" => document with { DocumentType = 3 },
            "negative-quantity" => document with { Lines = [document.Lines[0] with { SignedQuantity = -1 }] },
            "zero-total" => document with { SignedTotal = 0 }, "no-fiscal" => document with { FiscalNumber = 0 },
            "no-time" => document with { PostedAt = null, CommittedAt = null },
            _ => document with { SignedTotal = 100.5m, Lines = [document.Lines[0] with { SourceTotal = 100.5m, SignedAmount = 100.5m }] },
        };
        source.Maximum = 101; source.Rows[101] = row with { Document = document }; capture.Poll(source, reports.Add, TestContext.Current.CancellationToken);
        Assert.Empty(reports);
        source.Rows[101] = Refund(101); Capture().Poll(source, reports.Add, TestContext.Current.CancellationToken);
        Assert.Single(reports); // The incomplete observation was retained durably, not skipped.
    }

    [Fact]
    public void ForeignWorkstationCanNeverBeUploadedByThisObserver()
    {
        var source = new Source(); var reports = new List<SaleReport>(); var capture = Capture(); capture.Poll(source, reports.Add, TestContext.Current.CancellationToken);
        source.Maximum = 101; source.Rows[101] = Refund(101) with { Local = false };
        capture.Poll(source, reports.Add, TestContext.Current.CancellationToken); Assert.Empty(reports);
    }

    [Fact]
    public void InvisibleLowerIdCommitIsFoundAfterANewerCommittedIdAndRestart()
    {
        var source = new Source(); var reports = new List<SaleReport>(); var capture = Capture();
        capture.Poll(source, reports.Add, TestContext.Current.CancellationToken);
        source.Maximum = 102; source.Rows[102] = Refund(102); // 101 is still an invisible native transaction.
        capture.Poll(source, reports.Add, TestContext.Current.CancellationToken);
        Assert.Equal(102, Assert.Single(reports).SourceDocumentId);
        source.Rows[101] = Refund(101);
        Capture().Poll(source, reports.Add, TestContext.Current.CancellationToken);
        Assert.Equal(new long?[] { 102, 101 }, reports.Select(report => report.SourceDocumentId));
        Capture().Poll(source, reports.Add, TestContext.Current.CancellationToken); Assert.Equal(2, reports.Count);
    }

    [Fact]
    public void TransactionAllocatedBeforeActivationButPostedAfterwardsIsCapturedWithoutOldHistory()
    {
        var source = new Source(); var reports = new List<SaleReport>(); var capture = Capture();
        source.Rows[90] = Refund(90); capture.Poll(source, reports.Add, TestContext.Current.CancellationToken);
        Assert.Empty(reports);
        var late = Refund(99);
        source.Rows[99] = late with { Document = late.Document! with { PostedAt = source.DatabaseTime.AddSeconds(1) } };
        Capture().Poll(source, reports.Add, TestContext.Current.CancellationToken);
        Assert.Equal(99, Assert.Single(reports).SourceDocumentId);
        Capture().Poll(source, reports.Add, TestContext.Current.CancellationToken); Assert.Single(reports);
    }

    [Fact]
    public void CompletedBoundStopsBeforeEnqueueingAnUnretainedReturn()
    {
        var source = new Source(); var capture = Capture(); capture.Poll(source, _ => { }, TestContext.Current.CancellationToken);
        var path = Assert.Single(Directory.GetFiles(_root, "*.json"));
        var envelope = System.Text.Json.JsonSerializer.Deserialize<NativeRefundCapture.Envelope>(File.ReadAllText(path), EpharmJson.Options)!;
        var state = System.Text.Json.JsonSerializer.Deserialize<NativeRefundCapture.State>(envelope.Payload, EpharmJson.Options)! with {
            MaximumSeen = 20_000, Completed = Enumerable.Range(101, NativeRefundCapture.MaximumCompleted).Select(id => (long)id).ToList(),
        };
        var payload = System.Text.Json.JsonSerializer.Serialize(state, EpharmJson.Options);
        var hash = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(payload))).ToLowerInvariant();
        File.WriteAllText(path, System.Text.Json.JsonSerializer.Serialize(new NativeRefundCapture.Envelope { Payload = payload, Hash = hash }, EpharmJson.Options));
        source.Maximum = 20_001; source.Rows[20_001] = Refund(20_001); var reports = new List<SaleReport>();
        Assert.Throws<InvalidDataException>(() => Capture().Poll(source, reports.Add, TestContext.Current.CancellationToken));
        Assert.Empty(reports);
        Assert.Throws<InvalidDataException>(() => Capture().Poll(source, reports.Add, TestContext.Current.CancellationToken));
        Assert.Empty(reports);
    }

    [Fact]
    public void OldReturnDraftDoesNotVetoAProvenPostedSaleButRecentRefundRemainsAmbiguous()
    {
        var sale = NativeCashPrintObservation.Classify([(100, 9, 0), (200, 3, 1)]);
        Assert.False(sale.Refund); Assert.Equal(new long[] { 200 }, sale.SaleDocumentIds);
        var ambiguous = NativeCashPrintObservation.Classify([(100, 9, 1), (200, 3, 1)]);
        Assert.True(ambiguous.Refund);
        var returnOnly = NativeCashPrintObservation.Classify([(100, 9, 0), (200, 3, 2)]);
        Assert.True(returnOnly.Refund); Assert.Empty(returnOnly.SaleDocumentIds);
    }

    private sealed class Source : INativeRefundSource
    {
        public string Namespace = new('a', 64);
        public long Maximum = 100;
        public int Baselines;
        public int Reads;
        public List<long> RequestedAfter = new();
        public Dictionary<long, NativeRefundObservation> Rows = new();
        public DateTimeOffset DatabaseTime = DateTimeOffset.UtcNow;
        public bool TryGetRefundBaseline(out string sourceNamespace, out long maximumDocumentId, out DateTimeOffset databaseTime) {
            Baselines++; sourceNamespace = Namespace; maximumDocumentId = Maximum; databaseTime = DatabaseTime; return true;
        }
        public bool TryReadNativeRefunds(string sourceNamespace, NativeRefundScan scan, out NativeRefundBatch? batch) {
            Reads++; RequestedAfter.Add(scan.PageAfter);
            var all = Rows.Values.Where(row => row.DocumentId > scan.PageAfter &&
                (row.DocumentId > scan.Baseline || row.Document is { Status: 1, PostedAt: { } posted } && posted >= scan.ActivatedAt))
                .OrderBy(row => row.DocumentId).ToArray();
            batch = new(Namespace, Maximum, all.Take(64).ToArray(), scan.Pending.Where(Rows.ContainsKey).Select(id => Rows[id]).ToArray(), all.Length <= 64);
            return true;
        }
    }
    public void Dispose() { if (Directory.Exists(_root)) Directory.Delete(_root, true); else if (File.Exists(_root)) File.Delete(_root); }
}
