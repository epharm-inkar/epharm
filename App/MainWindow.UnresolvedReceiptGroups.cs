using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using CustomerDisplay.Services;

namespace CustomerDisplay;

public partial class MainWindow
{
    private readonly Dictionary<string, StandardNUnpaidGroupAbsenceProof> _unpaidGroupAbsence = new(StringComparer.Ordinal);
    private DateTimeOffset _nextUnpaidGroupProbe;
    private bool _unpaidGroupProbeBusy;
    private int _unpaidGroupProbeOffset;
    private Task _unpaidGroupProbeTask = Task.CompletedTask;
    private readonly Dictionary<string, DateTimeOffset> _observedGroupSeenAt = new(StringComparer.Ordinal);
    private DateTimeOffset? _observedGroupsSeenSince;
    private DateTimeOffset _nextObservedCapacityCheck;

    private void MarkObservedGroupSeen(StandardNReceiptSnapshot snapshot) =>
        _observedGroupSeenAt[ObservedGroupKey(snapshot)] = _receiptProofClock();

    /// <summary>Only the current receipt and recently seen groups may take a new doc-less print cue.</summary>
    private bool IsLiveForPrintCue(StandardNObservedReceiptGroup group)
    {
        var key = ObservedGroupKey(group.Snapshot);
        if (_standardNCurrentReceipt != null && ObservedGroupKey(_standardNCurrentReceipt) == key) return true;
        return ObservedGroupCapacity.IsLive(_observedGroupSeenAt.TryGetValue(key, out var seen) ? seen : null, _receiptProofClock());
    }

    /// <summary>
    /// Keeps the bounded hot journal from filling with groups that can never resolve (INK-122):
    /// stale or, under pressure, the oldest non-current groups move to a private local archive.
    /// </summary>
    private void ReleaseObservedGroupCapacity(StandardNReceiptSnapshot? incoming)
    {
        var now = _receiptProofClock();
        if (_receiptObserversClosed || _physicalReceiptProbeBusy || _unpaidGroupProbeBusy || _receiptArtifacts == null ||
            !LoadObservedReceiptGroups()) return;
        _observedGroupsSeenSince ??= now;
        var pressure = _observedReceiptGroups.Count > ObservedGroupCapacity.HotLimit - ObservedGroupCapacity.FreeSlotsToKeep;
        if (!pressure && now < _nextObservedCapacityCheck) return;
        _nextObservedCapacityCheck = now.AddSeconds(15);
        var protectedKeys = new[] { _standardNCurrentReceipt, incoming }.Where(receipt => receipt != null)
            .Select(receipt => ObservedGroupKey(receipt!)).ToHashSet(StringComparer.Ordinal);
        var since = _observedGroupsSeenSince.Value;
        var releases = ObservedGroupCapacity.SelectReleases(_observedReceiptGroups.Values.ToArray(),
            group => protectedKeys.Contains(ObservedGroupKey(group.Snapshot)),
            group => _observedGroupSeenAt.TryGetValue(ObservedGroupKey(group.Snapshot), out var seen) ? seen : since, now);
        if (releases.Count == 0) return;
        var released = 0;
        try
        {
            foreach (var (group, reason) in releases)
            {
                if (!_receiptArtifacts.ReleaseStaleObservedGroup(group, reason)) continue;
                var key = ObservedGroupKey(group.Snapshot);
                _observedReceiptGroups.Remove(key);
                _physicalReceiptRetryAfter.Remove(key);
                _unpaidGroupAbsence.Remove(key);
                _observedGroupSeenAt.Remove(key);
                released++;
            }
        }
        catch (Exception error)
        {
            _nextObservedCapacityCheck = now.AddSeconds(60);
            Log("Место в журнале незавершённых чеков не освобождено; данные сохранены: " + error.GetBaseException().Message);
        }
        if (released > 0)
            Log($"Журнал незавершённых чеков: {released} давних групп перенесено в локальный архив без продажи, " +
                $"свободно мест {ObservedGroupCapacity.HotLimit - _observedReceiptGroups.Count}.");
    }

    private void ReconcileUnresolvedReceiptGroups()
    {
        var now = _receiptProofClock();
        if (_receiptObserversClosed || _unpaidGroupProbeBusy || _physicalReceiptProbeBusy ||
            now < _nextUnpaidGroupProbe || !LoadObservedReceiptGroups()) return;
        var eligible = _observedReceiptGroups.Values.Where(group =>
            !group.PrintObserved && group.PendingPrint == null &&
            group.PrintDocumentTimes.Count == 0 && group.AnchoredPrintCueIds.Count == 0 &&
            group.QueuedDocumentIds.Count == 0 && group.ResolvedEmptyDocumentIds.Count == 0 &&
            group.InitiallyPostedDocumentIds.Count == 0 &&
            (_standardNCurrentReceipt == null || ObservedGroupKey(_standardNCurrentReceipt) != ObservedGroupKey(group.Snapshot))).ToArray();
        if (eligible.Length == 0) return;
        foreach (var stale in _unpaidGroupAbsence.Keys.Where(key => !_observedReceiptGroups.ContainsKey(key)).ToArray())
            _unpaidGroupAbsence.Remove(stale);
        try { _rewardJournal ??= new RecommendationRewardJournal(Path.Combine(_posmConfig!.ReceiptCaptureDir, "reward-evidence")); }
        catch { return; }
        var offset = _unpaidGroupProbeOffset % eligible.Length;
        var candidates = eligible.Skip(offset).Concat(eligible.Take(offset)).Take(4).ToArray();
        var namespaces = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var group in candidates)
        {
            var ns = group.SourceNamespace;
            if (ns == null && _rewardJournal?.TryGetRecordedSourceNamespace(group.CheckoutSessionId, group.Snapshot, out var recorded) == true) ns = recorded;
            if (ns != null) namespaces[ObservedGroupKey(group.Snapshot)] = ns;
        }
        _unpaidGroupProbeOffset = (offset + candidates.Length) % eligible.Length;
        _nextUnpaidGroupProbe = now.AddSeconds(2);
        _unpaidGroupProbeBusy = true;
        _unpaidGroupProbeTask = Task.Run(() =>
        {
            var results = new List<(StandardNObservedReceiptGroup Group, StandardNUnpaidGroupAbsenceProof? Proof)>();
            foreach (var group in candidates)
            {
                StandardNUnpaidGroupAbsenceProof? proof = null;
                try
                {
                    if (namespaces.TryGetValue(ObservedGroupKey(group.Snapshot), out var ns))
                        if (_standardNDb?.TryGetUnpaidGroupAbsenceProof(group, ns, out proof) != true) proof = null;
                }
                catch { /* UNKNOWN retains every original observation. */ }
                results.Add((group, proof));
            }
            try
            {
                Dispatcher.Invoke(() =>
                {
                    try
                    {
                        if (_receiptObserversClosed) return;
                        foreach (var (original, proof) in results)
                        {
                            var key = ObservedGroupKey(original.Snapshot);
                            if (!_observedReceiptGroups.TryGetValue(key, out var current) ||
                                StandardNUnpaidGroupAbsenceProof.Fingerprint(current) != StandardNUnpaidGroupAbsenceProof.Fingerprint(original) ||
                                _standardNCurrentReceipt != null && ObservedGroupKey(_standardNCurrentReceipt) == key || proof == null)
                            { _unpaidGroupAbsence.Remove(key); continue; }
                            if (!namespaces.TryGetValue(key, out var ns) || proof.SourceNamespace != ns ||
                                proof.GroupFingerprint != StandardNUnpaidGroupAbsenceProof.Fingerprint(current))
                            { _unpaidGroupAbsence.Remove(key); continue; }
                            if (!_unpaidGroupAbsence.TryGetValue(key, out var first) ||
                                first.GroupFingerprint != proof.GroupFingerprint || first.SourceNamespace != proof.SourceNamespace ||
                                proof.DatabaseObservedAt < first.DatabaseObservedAt)
                            { _unpaidGroupAbsence[key] = proof; continue; }
                            if (first.DatabaseTransactionId == proof.DatabaseTransactionId ||
                                proof.DatabaseObservedAt - first.DatabaseObservedAt < TimeSpan.FromSeconds(1)) continue;
                            var audit = JsonSerializer.Serialize(new { reason = "source_disappeared_unresolved", first, second = proof }, EpharmJson.Options);
                            // Journal tombstone first: even a later store-write failure cannot revive money.
                            _rewardJournal ??= new RecommendationRewardJournal(Path.Combine(_posmConfig!.ReceiptCaptureDir, "reward-evidence"));
                            if (!_rewardJournal.ArchiveUnresolved(current.CheckoutSessionId, proof.GroupFingerprint, audit) ||
                                _receiptArtifacts?.ArchiveUnresolvedObservedGroup(current, audit) != true) continue;
                            _observedReceiptGroups.Remove(key);
                            _physicalReceiptRetryAfter.Remove(key);
                            _unpaidGroupAbsence.Remove(key);
                            _observedGroupSeenAt.Remove(key);
                            Log("Исчезнувший неоплаченный источник сохранён в неизменяемом архиве без подтверждения продажи; активный слот освобождён.");
                        }
                    }
                    catch (Exception error)
                    { Log("Архив неопределённого чека не завершён; данные сохранены: " + error.GetBaseException().Message); }
                    finally { _unpaidGroupProbeBusy = false; }
                });
            }
            catch { /* Shutdown preserves the last durable state. */ }
        });
    }
}
