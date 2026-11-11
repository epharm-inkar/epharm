using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using CustomerDisplay.Services;

namespace CustomerDisplay;

public partial class MainWindow
{
    private Task _nativeRefundTask = Task.CompletedTask;
    private Task _nativePrintGuardTask = Task.CompletedTask;

    private void GuardNativePrintCue(DateTimeOffset markerAt, IReadOnlySet<long>? exactDocuments = null)
    {
        IReadOnlySet<long>? retained = null;
        IReadOnlySet<long>? batch = null;
        if (exactDocuments != null) {
            if (exactDocuments.Count is < 1 or > StandardNReceiptProjection.MaxDocuments ||
                exactDocuments.Any(id => id <= 0) || !LoadObservedReceiptGroups()) return;
            retained = exactDocuments.Intersect(_observedReceiptGroups.Values.SelectMany(group =>
                group.ObservedActiveDocumentIds.Except(group.QueuedDocumentIds).Except(group.ResolvedEmptyDocumentIds))).ToHashSet();
            if (retained.Count == 0) return;
            batch = new HashSet<long>(exactDocuments); // Unknown return IDs can veto, never authorize, a sale.
        }
        _nativePrintGuardTask = Task.Run(() => {
            if (_receiptObserversClosed || _standardNDb?.TryObserveNativeCashPrint(out var operation, batch) != true || operation == null ||
                operation.Refund || operation.SaleDocumentIds.Count == 0) return;
            var permitted = operation.SaleDocumentIds.ToHashSet();
            if (retained != null) permitted.IntersectWith(retained);
            if (permitted.Count == 0) return;
            try { Dispatcher.Invoke(() => {
                if (!_receiptObserversClosed && _posmConfig?.NativeRefundCaptureEnabled == true)
                    RecordPhysicalReceiptPrint(permitted, markerAt);
            }); } catch { }
        });
    }

    private void StartNativeRefundPolling(CancellationToken cancellation)
    {
        if (_posmConfig?.NativeRefundCaptureEnabled != true || _standardNDb == null || _saleReporter == null) return;
        _nativeRefundTask = Task.Run(async () => {
            NativeRefundCapture? capture = null;
            var lastError = DateTimeOffset.MinValue;
            while (!cancellation.IsCancellationRequested && !_receiptObserversClosed) {
                try {
                    capture ??= new NativeRefundCapture(Path.Combine(_posmConfig.ReceiptCaptureDir, "native-refunds"),
                        _posmConfig.PharmacyId, Environment.MachineName);
                    capture.Poll(_standardNDb, _saleReporter.Enqueue, cancellation);
                }
                catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { return; }
                catch {
                    if (DateTimeOffset.UtcNow - lastError >= TimeSpan.FromSeconds(30)) {
                        lastError = DateTimeOffset.UtcNow;
                        Log("Native refund observation is incomplete; source/cursor preserved for review, cashier continues.");
                    }
                }
                try { await Task.Delay(TimeSpan.FromSeconds(2), cancellation).ConfigureAwait(false); }
                catch (OperationCanceledException) { return; }
            }
        }, cancellation);
    }
}
