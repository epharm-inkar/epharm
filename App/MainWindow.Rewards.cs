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
    private volatile int _rewardEvidenceProtocol;
    private bool _nativePrintGuardAdmitted;
    private bool RewardEvidenceEnabled => _rewardEvidenceProtocol == 1 && _posmConfig?.RecommendationRewardsEnabled != false;
    private bool NativeRefundObservationEnabled => _rewardEvidenceProtocol == 1 && _posmConfig?.NativeRefundCaptureEnabled != false;

    private void ApplyRewardCapability(int? protocol)
    {
        var next = protocol == 1 ? 1 : 0;
        // Print classification is ordinary sale safety, not permission to collect/credit rewards.
        if (next == 1 && (_posmConfig?.RecommendationRewardsEnabled != false || _posmConfig?.NativeRefundCaptureEnabled != false))
            _nativePrintGuardAdmitted = true;
        if (_rewardEvidenceProtocol == next) return;
        _rewardEvidenceProtocol = next;
        // Never revive a checkout with an observation gap; later checkouts use fresh authoritative carts.
        InvalidateRewardObservation();
    }

    private RecommendationRewardJournal? _rewardJournal;
    private StandardNReceiptSnapshot? _rewardSnapshot;
    private DateTimeOffset _rewardSnapshotAt;
    private string? _rewardSnapshotNamespace;

    private RecommendationRewardJournal? RewardJournal()
    {
        if (!RewardEvidenceEnabled || _posmConfig is not { } config) return null;
        try { return _rewardJournal ??= new RecommendationRewardJournal(Path.Combine(config.ReceiptCaptureDir, "reward-evidence")); }
        catch { return null; } // Display, checkout, login and the ordinary sale queue are independent.
    }

    private RewardSourceIdentity RewardIdentity(StandardNReceiptSnapshot? source) => new() {
        SourceNamespace = (ReferenceEquals(source, _rewardSnapshot) ? _rewardSnapshotNamespace : _standardNDb?.RewardSourceNamespace) ?? "", RawUserId = source?.Pharmacist.Id ?? "",
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
        _rewardSnapshot = source;
        _rewardSnapshotAt = _receiptProofClock();
        _rewardSnapshotNamespace = _standardNDb?.RewardSourceNamespace;
        if (!RewardEvidenceEnabled) return;
        RewardJournal()?.Observe(_session.SessionId, RewardIdentity(source), RewardLines(source), _rewardSnapshotAt);
    }

    private void InvalidateRewardObservation()
    {
        var observedCheckout = _rewardSnapshot != null || _standardNDocumentId.HasValue || ReceiptItems.Count > 0;
        _rewardSnapshot = null;
        _rewardSnapshotNamespace = null;
        // An idle connectivity gap cannot poison an ID which has never observed a cart.
        if (observedCheckout) _rewardJournal?.MarkIncomplete(_session.SessionId);
    }

    private string? RecommendSourceNamespace(RecommendRequest request)
    {
        var source = _rewardSnapshot;
        var ns = _rewardSnapshotNamespace;
        if (!HasCurrentRewardSnapshot(source, _receiptProofClock()) || source == null || ns == null || ns.Length != 64 ||
            ns.Any(c => c is not (>= 'a' and <= 'f') and not (>= '0' and <= '9')) ||
            request.SessionId != _session.SessionId || request.PharmacistId != source.Pharmacist.Id ||
            source.SessionId != _session.StandardNSessionId || string.IsNullOrWhiteSpace(source.WorkstationId)) return null;
        return ns;
    }

    private bool HasCurrentRewardSnapshot(StandardNReceiptSnapshot? source, DateTimeOffset at)
    {
        var current = ReceiptItems.Where(item => item.PartId > 0).ToArray();
        return source != null && at >= _rewardSnapshotAt && at - _rewardSnapshotAt <= TimeSpan.FromSeconds(2) &&
            current.Length == source.Lines.Count && current.All(item => source.Lines.Any(line =>
                item.SourceDocumentId == line.SourceDocumentId && item.SourceLineId == line.LineId &&
                item.PartId == line.PartId && item.Qty == line.Qty && string.Equals(item.Barcode?.Trim(), line.Barcode?.Trim(), StringComparison.Ordinal)));
    }

    private RewardShownEvidence? CaptureRewardShown(Recommendation recommendation, DateTimeOffset shownAt)
    {
        var journal = RewardJournal();
        if (journal == null) return null;
        var source = _rewardSnapshot;
        var complete = HasCurrentRewardSnapshot(source, shownAt);
        return journal.RecordShown(_session.SessionId, recommendation.EventId, recommendation.RecommendBarcode,
            recommendation.RecommendSku, RewardIdentity(source), source == null ? new() : RewardLines(source), shownAt, complete,
            recommendation.RecommendBarcodes);
    }

    private void AttachRewardSale(SaleReport sale, CheckoutSession session, StandardNReceiptDocument document,
        StandardNReceiptSnapshot? completionSource, DateTimeOffset completedAt)
    {
        try { if (RewardJournal() is { } journal) sale.RewardEvidence = journal.BuildSale(session.SessionId, document, RewardIdentity(completionSource), completedAt); }
        catch { /* Missing evidence never blocks an ordinary sale or manufactures eligibility. */ }
    }
}
