using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using System.Threading;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services;

public sealed record NativeRefundObservation(long DocumentId, bool Local,
    StandardNReceiptDocument? Document, RewardSourceIdentity Identity);
public sealed record NativeCashPrintObservation(bool Refund, IReadOnlyList<long> SaleDocumentIds)
{
    // The reader supplies only active rows or postings in the bounded print observation window.
    public static NativeCashPrintObservation Classify(IReadOnlyList<(long Id, int Type, int Status)> rows)
    {
        var postedSale = rows.Any(row => row.Type == 3 && row.Status == 1);
        var refund = rows.Any(row => row.Type == 9 && row.Status == 1) ||
            !postedSale && rows.Any(row => row.Type == 9 && row.Status == 0);
        return new(refund, rows.Where(row => row.Type == 3 && row.Status is 0 or 1).Select(row => row.Id).ToArray());
    }
}
public sealed record NativeRefundScan(long Baseline, DateTimeOffset ActivatedAt, long PageAfter,
    IReadOnlyList<long> Pending, IReadOnlySet<long> Completed);
public sealed record NativeRefundBatch(string SourceNamespace, long MaximumDocumentId,
    IReadOnlyList<NativeRefundObservation> NewDocuments, IReadOnlyList<NativeRefundObservation> PendingDocuments,
    bool CompletedPass = false);
public interface INativeRefundSource
{
    bool TryGetRefundBaseline(out string sourceNamespace, out long maximumDocumentId, out DateTimeOffset databaseTime);
    bool TryReadNativeRefunds(string sourceNamespace, NativeRefundScan scan,
        out NativeRefundBatch? batch);
}

/// <summary>
/// Separate DB-posted refund observer. Never reuses the generic print cue or relabels held sales.
/// A fixed activation DB time excludes old postings; rotating pages also catch late commits with older IDs.
/// Enqueue precedes cursor advance, so crash replay is idempotent rather than a lost refund.
/// </summary>
public sealed class NativeRefundCapture
{
    public const int MaximumPending = 256;
    public const int MaximumCompleted = 16_384;
    private const int MaximumStateBytes = 1024 * 1024;
    public const string CaptureSource = "standardn-db-refund";
    public const string EvidenceSource = "standardn-posted-refund";
    private readonly string _root;
    private readonly string _pharmacy;
    private readonly string _machine;
    private readonly object _gate = new();
    private State? _state;

    public NativeRefundCapture(string directory, string pharmacy, string machine)
    {
        _root = Path.GetFullPath(directory); _pharmacy = pharmacy; _machine = machine;
        Directory.CreateDirectory(_root); RejectLinks(_root);
        if (OperatingSystem.IsWindows()) {
            var acl = new DirectorySecurity(); acl.SetAccessRuleProtection(true, false);
            foreach (var sid in new[] { WindowsIdentity.GetCurrent().User!, new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null) })
                acl.AddAccessRule(new FileSystemAccessRule(sid, FileSystemRights.FullControl,
                    InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
            new DirectoryInfo(_root).SetAccessControl(acl);
        } else File.SetUnixFileMode(_root, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
    }

    public void Poll(INativeRefundSource source, Action<SaleReport> enqueue, CancellationToken cancellation = default)
    {
        lock (_gate)
        {
            cancellation.ThrowIfCancellationRequested();
            if (_state == null) {
                if (!source.TryGetRefundBaseline(out var ns, out var maximum, out var databaseTime) ||
                    !Namespace(ns) || maximum < 0 || databaseTime == default) return;
                var initial = Read(ns) ?? new State { SourceNamespace = ns, Scope = Scope(ns), Baseline = maximum,
                    MaximumSeen = maximum, ActivatedAt = databaseTime };
                Write(initial); _state = initial;
            }
            var scan = new NativeRefundScan(_state.Baseline, _state.ActivatedAt, _state.PageAfter,
                _state.Pending.ToArray(), _state.Completed.ToHashSet());
            if (!source.TryReadNativeRefunds(_state.SourceNamespace, scan, out var batch) || batch == null) return;
            if (batch.SourceNamespace != _state.SourceNamespace) { _state = null; return; }
            if (batch.MaximumDocumentId < 0 || batch.NewDocuments.Count > 64 || batch.PendingDocuments.Count > MaximumPending ||
                batch.PendingDocuments.Select(row => row.DocumentId).Distinct().Count() != batch.PendingDocuments.Count ||
                batch.PendingDocuments.Any(row => !_state.Pending.Contains(row.DocumentId)) ||
                batch.NewDocuments.Any(row => row.DocumentId <= _state.PageAfter || row.DocumentId > batch.MaximumDocumentId) ||
                batch.NewDocuments.Select(row => row.DocumentId).Distinct().Count() != batch.NewDocuments.Count ||
                !batch.NewDocuments.Select(row => row.DocumentId).SequenceEqual(batch.NewDocuments.Select(row => row.DocumentId).OrderBy(id => id)))
                throw new InvalidDataException("Native refund observation is incomplete or out of order.");
            // Copy before enqueue: an enqueue/persistence error must not advance the in-memory scan.
            var next = _state with { Pending = _state.Pending.ToList(), Completed = _state.Completed.ToList() };
            var completed = next.Completed.ToHashSet();
            bool Retain(NativeRefundObservation row) {
                if (completed.Contains(row.DocumentId)) return true;
                if (!row.Local) return true;
                var afterActivation = row.DocumentId > next.Baseline || row.Document?.PostedAt is { } posted && posted >= next.ActivatedAt;
                if (afterActivation && TryBuild(row, next.SourceNamespace) is { } report) {
                    if (completed.Count >= MaximumCompleted) return false;
                    cancellation.ThrowIfCancellationRequested(); enqueue(report);
                    completed.Add(row.DocumentId); next.Completed.Add(row.DocumentId); next.Pending.Remove(row.DocumentId);
                    return true;
                }
                if (next.Pending.Contains(row.DocumentId)) return true;
                if (next.Pending.Count >= MaximumPending) return false;
                next.Pending.Add(row.DocumentId); return true;
            }
            var allRetained = true;
            foreach (var row in batch.PendingDocuments) {
                cancellation.ThrowIfCancellationRequested();
                if (!Retain(row)) { allRetained = false; break; }
            }
            if (allRetained) foreach (var row in batch.NewDocuments) {
                cancellation.ThrowIfCancellationRequested();
                if (!Retain(row)) { allRetained = false; break; }
                next.PageAfter = row.DocumentId;
            }
            // Never assume generator order is commit order. Every finished pass starts again.
            if (allRetained && batch.CompletedPass) next.PageAfter = 0;
            // Cancelling the highest unpaid draft can lower MAX(DOCS.ID); never lower the durable cutoff.
            next.MaximumSeen = Math.Max(next.MaximumSeen, batch.MaximumDocumentId);
            cancellation.ThrowIfCancellationRequested(); Write(next); _state = next;
            if (!allRetained) throw new InvalidDataException("Native refund evidence bound reached; observations preserved for review.");
        }
    }

    private SaleReport? TryBuild(NativeRefundObservation row, string ns)
    {
        var document = row.Document; var identity = row.Identity;
        var completed = document?.PostedAt ?? document?.CommittedAt;
        if (!row.Local || document == null || document.DocumentId != row.DocumentId || completed == null ||
            !StandardNReceiptProjection.IsPostedPhysicalRefund(document) || !StandardNReceiptProjection.CanRepresentLongKzt(document) ||
            identity.SourceNamespace != ns || string.IsNullOrWhiteSpace(identity.RawUserId) || identity.NativeSessionId != document.SessionId ||
            string.IsNullOrWhiteSpace(identity.WorkstationId)) return null;
        var checkout = "refund_" + Hash($"{ns}|{document.DocumentId.ToString(CultureInfo.InvariantCulture)}")[..48];
        var original = document.OriginalReceipt is { DocumentType: 3, Status: 1, DocumentId: > 0 } candidate &&
            candidate.DocumentId == document.ParentDocumentId ? candidate : null;
        var originalFiscal = original != null && !string.IsNullOrWhiteSpace(original.DeviceNumber) && original.FiscalNumber is > 0 && original.FiscalShift is > 0;
        var sale = new SaleReport {
            SaleId = "sale_" + Hash($"{_pharmacy}|native-refund|{ns}|{document.DocumentId.ToString(CultureInfo.InvariantCulture)}")[..48],
            PharmacyId = _pharmacy, PharmacistId = identity.RawUserId, SessionId = checkout,
            SourceDocumentId = document.DocumentId, CaptureSource = CaptureSource,
            PrintedAt = completed.Value, TotalAmount = (long)document.SignedTotal,
            Items = document.Lines.Select(line => new SaleReportItem { Sku = line.PartId.ToString(CultureInfo.InvariantCulture),
                Barcode = line.Barcode, Name = line.Name, Qty = (double)line.Qty, Price = (long)line.Price, Total = (long)line.SourceTotal!.Value }).ToList(),
        };
        sale.RewardEvidence = new RewardSaleEvidence { CheckoutSessionId = checkout, JournalComplete = false,
            Completion = new RewardCompletionEvidence { Status = "refund", Source = EvidenceSource, CompletedAt = completed.Value,
                Identity = identity, SourceDocumentId = document.DocumentId, DocumentType = document.DocumentType,
                DocumentStatus = document.Status, FinalDetailsRead = document.FinalDetailsRead, SignedTotal = document.SignedTotal,
                DeviceNumber = document.DeviceNumber ?? "", FiscalNumber = document.FiscalNumber ?? 0, FiscalShift = document.FiscalShift ?? 0,
                OriginalSourceDocumentId = original?.DocumentId,
                OriginalDeviceNumber = originalFiscal ? original!.DeviceNumber : null,
                OriginalFiscalNumber = originalFiscal ? original!.FiscalNumber : null, OriginalFiscalShift = originalFiscal ? original!.FiscalShift : null,
                Lines = document.Lines.Select(line => new RewardPaidLine { SourceDocumentId = document.DocumentId, SourceLineId = line.LineId,
                    Sku = line.PartId.ToString(CultureInfo.InvariantCulture), Barcode = line.Barcode, Qty = line.Qty,
                    SignedQuantity = line.SignedQuantity!.Value, SignedPrice = line.SignedPrice!.Value, SignedAmount = line.SignedAmount!.Value,
                    UnitSemantics = line.UnitSemantics }).ToList(),
            } };
        return sale;
    }

    private static bool Namespace(string value) => value.Length == 64 && value.All(ch => ch is >= '0' and <= '9' or >= 'a' and <= 'f');
    private static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();
    private string Scope(string ns) => Hash($"{_pharmacy.Length}:{_pharmacy}{_machine.Length}:{_machine}{ns}");
    private string PathFor(string ns) => Path.Combine(_root, Scope(ns) + ".json");
    private State? Read(string ns)
    {
        var path = PathFor(ns); RejectLinks(path); if (!File.Exists(path)) return null;
        if (new FileInfo(path).Length > MaximumStateBytes) throw new InvalidDataException("Native refund cursor is oversized.");
        var envelope = JsonSerializer.Deserialize<Envelope>(File.ReadAllBytes(path), EpharmJson.Options) ?? throw new InvalidDataException("Invalid native refund cursor.");
        if (envelope.Hash != Hash(envelope.Payload)) throw new InvalidDataException("Native refund cursor checksum failed.");
        var state = JsonSerializer.Deserialize<State>(envelope.Payload, EpharmJson.Options);
        if (state == null || state.Version != 2 || state.Scope != Scope(ns) || state.SourceNamespace != ns || state.Baseline < 0 ||
            state.ActivatedAt == default || state.MaximumSeen < state.Baseline || state.PageAfter < 0 || state.PageAfter > state.MaximumSeen ||
            state.Pending.Count > MaximumPending || state.Completed.Count > MaximumCompleted ||
            state.Pending.Concat(state.Completed).Any(id => id <= 0 || id > state.MaximumSeen) ||
            state.Pending.Concat(state.Completed).Distinct().Count() != state.Pending.Count + state.Completed.Count) throw new InvalidDataException("Native refund cursor identity is invalid.");
        return state;
    }
    private void Write(State state)
    {
        RejectLinks(_root); var path = PathFor(state.SourceNamespace); RejectLinks(path);
        if (!File.Exists(path) && Directory.GetFiles(_root, "*.json").Length >= 32) throw new InvalidDataException("Native refund source bound exceeded.");
        var payload = JsonSerializer.Serialize(state, EpharmJson.Options);
        var bytes = JsonSerializer.SerializeToUtf8Bytes(new Envelope { Payload = payload, Hash = Hash(payload) }, EpharmJson.Options);
        if (bytes.Length > MaximumStateBytes) throw new InvalidDataException("Native refund cursor bound exceeded.");
        var temporary = path + ".tmp-" + Guid.NewGuid().ToString("N");
        try {
            using (var file = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None)) { file.Write(bytes); file.Flush(true); }
            if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(temporary, UnixFileMode.UserRead | UnixFileMode.UserWrite);
            File.Move(temporary, path, true);
        } finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }
    private static void RejectLinks(string path)
    {
        for (var item = Path.GetFullPath(path); !string.IsNullOrEmpty(item); item = Path.GetDirectoryName(item))
            if ((File.Exists(item) || Directory.Exists(item)) && (File.GetAttributes(item) & FileAttributes.ReparsePoint) != 0)
                throw new InvalidDataException("Linked native refund evidence is not accepted.");
    }
    public sealed record State {
        public int Version { get; init; } = 2; public string SourceNamespace { get; init; } = ""; public string Scope { get; init; } = "";
        public long Baseline { get; init; } public DateTimeOffset ActivatedAt { get; init; }
        public long MaximumSeen { get; set; } public long PageAfter { get; set; }
        public List<long> Pending { get; init; } = new(); public List<long> Completed { get; init; } = new();
    }
    public sealed class Envelope { public string Payload { get; init; } = ""; public string Hash { get; init; } = ""; }
}
