using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;

namespace CustomerDisplay;

public partial class MainWindow
{
    private RecommendationRewardJournal? _rewardJournal;
    private StandardNReceiptSnapshot? _rewardSnapshot;
    private DateTimeOffset _rewardSnapshotAt;

    private RecommendationRewardJournal? RewardJournal()
    {
        if (_posmConfig?.RecommendationRewardsEnabled != true) return null;
        try { return _rewardJournal ??= new RecommendationRewardJournal(Path.Combine(_posmConfig.ReceiptCaptureDir, "reward-evidence")); }
        catch { return null; } // Display, checkout, login and the ordinary sale queue are independent.
    }

    private RewardSourceIdentity RewardIdentity(StandardNReceiptSnapshot? source) => new() {
        SourceNamespace = _standardNDb?.RewardSourceNamespace ?? "", RawUserId = source?.Pharmacist.Id ?? "",
        NativeSessionId = source?.SessionId ?? 0, WorkstationId = source?.WorkstationId ?? "",
        // RPS authenticates its own account independently. Do not invent a source match from its token/name.
        IdentityStatus = "unknown",
    };

    private static List<RewardCartLine> RewardLines(StandardNReceiptSnapshot source) => source.Lines.Select(line => new RewardCartLine {
        SourceDocumentId = line.SourceDocumentId ?? 0, SourceLineId = line.LineId,
        Sku = line.PartId.ToString(CultureInfo.InvariantCulture), Barcode = line.Barcode?.Trim(), Qty = line.Qty,
        UnitSemantics = line.UnitSemantics,
    }).ToList();

    private void ObserveRewardSnapshot(StandardNReceiptSnapshot source)
    {
        if (_posmConfig?.RecommendationRewardsEnabled != true) return;
        _rewardSnapshot = source;
        _rewardSnapshotAt = _receiptProofClock();
        RewardJournal()?.Observe(_session.SessionId, RewardIdentity(source), RewardLines(source), _rewardSnapshotAt);
    }

    private void InvalidateRewardObservation()
    {
        _rewardSnapshot = null;
        _rewardJournal?.MarkIncomplete(_session.SessionId);
    }

    private RewardShownEvidence? CaptureRewardShown(Recommendation recommendation, DateTimeOffset shownAt)
    {
        var journal = RewardJournal();
        if (journal == null) return null;
        var source = _rewardSnapshot;
        var current = ReceiptItems.Where(item => item.PartId > 0).ToArray();
        var complete = source != null && shownAt >= _rewardSnapshotAt && shownAt - _rewardSnapshotAt <= TimeSpan.FromSeconds(2) &&
            current.Length == source.Lines.Count && current.All(item => source.Lines.Any(line =>
                item.SourceDocumentId == line.SourceDocumentId && item.SourceLineId == line.LineId &&
                item.PartId == line.PartId && item.Qty == line.Qty && string.Equals(item.Barcode?.Trim(), line.Barcode?.Trim(), StringComparison.Ordinal)));
        return journal.RecordShown(_session.SessionId, recommendation.EventId, recommendation.RecommendBarcode,
            recommendation.RecommendSku, RewardIdentity(source), source == null ? new() : RewardLines(source), shownAt, complete);
    }

    private void AttachRewardSale(SaleReport sale, CheckoutSession session, StandardNReceiptDocument document,
        StandardNReceiptSnapshot? completionSource, DateTimeOffset completedAt)
    {
        try { if (RewardJournal() is { } journal) sale.RewardEvidence = journal.BuildSale(session.SessionId, document, RewardIdentity(completionSource), completedAt); }
        catch { /* Missing evidence never blocks an ordinary sale or manufactures eligibility. */ }
    }
}
