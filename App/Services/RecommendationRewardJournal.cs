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
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services;

/// <summary>
/// Bounded private observations, not a wallet. A missing/failed/ambiguous observation can only
/// lower evidence quality; it must never block a receipt or turn a sale into a bonus locally.
/// </summary>
public sealed class RecommendationRewardJournal
{
    public const int MaximumCheckouts = 32;
    public const int MaximumChanges = 2048;
    public const int MaximumLines = 512;
    private const int MaximumOffers = 128;
    private const long MaximumBytes = 8 * 1024 * 1024;
    private const long MaximumFileBytes = 2 * 1024 * 1024;
    private readonly string _root;
    private readonly string _instance = Guid.NewGuid().ToString("N");
    private readonly object _gate = new();
    private readonly HashSet<string> _failed = new(StringComparer.Ordinal);
    private bool _unavailable;

    public RecommendationRewardJournal(string root)
    {
        _root = Path.GetFullPath(root);
        Directory.CreateDirectory(_root);
        RejectLink(_root);
        if (OperatingSystem.IsWindows())
        {
            var acl = new DirectorySecurity();
            acl.SetAccessRuleProtection(true, false);
            foreach (var sid in new[] { WindowsIdentity.GetCurrent().User!, new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null) })
                acl.AddAccessRule(new FileSystemAccessRule(sid, FileSystemRights.FullControl,
                    InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
            new DirectoryInfo(_root).SetAccessControl(acl);
        }
        else File.SetUnixFileMode(_root, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
    }

    // Include the local machine only for a local Firebird server; credentials never enter the digest.
    public static string SourceNamespace(string host, int port, string database, string machine)
    {
        if (string.IsNullOrWhiteSpace(database) || port is < 1 or > 65535) return "";
        host = host.Trim().ToLowerInvariant();
        if (host is "" or "localhost" or "127.0.0.1" or "::1" or ".") host = StaffSessionResolver.CanonicalComputer(machine);
        var path = database.Trim().Replace('\\', '/').ToUpperInvariant();
        var raw = $"standardn-v1|{host.Length}:{host}|{port.ToString(CultureInfo.InvariantCulture)}|{path.Length}:{path}";
        return Hash(Encoding.UTF8.GetBytes(raw));
    }

    public void Observe(string checkout, RewardSourceIdentity identity, IReadOnlyList<RewardCartLine> lines,
        DateTimeOffset observedAt, bool cartComplete = true)
    {
        lock (_gate) TryChange(checkout, state => Observe(state, identity, lines, observedAt, cartComplete));
    }

    public void MarkIncomplete(string checkout)
    {
        lock (_gate)
        {
            Fail(checkout);
            // Do not allocate a journal for a disabled/missing receipt.
            if (File.Exists(PathFor(checkout))) TryChange(checkout, state => state.Complete = false);
        }
    }

    public RewardShownEvidence? RecordShown(string checkout, string eventId, string? barcode, string? sku,
        RewardSourceIdentity identity, IReadOnlyList<RewardCartLine> cart, DateTimeOffset shownAt, bool cartComplete)
    {
        lock (_gate)
        {
            RewardShownEvidence? result = null;
            TryChange(checkout, state =>
            {
                Observe(state, identity, cart, shownAt, cartComplete);
                var previous = state.Offers.SingleOrDefault(offer => offer.EventId == eventId);
                if (previous != null) {
                    if (previous.Barcode != Clean(barcode) || previous.Sku != Clean(sku)) state.Complete = false;
                    result = previous.Evidence; return;
                } // Never reset a timer/baseline on a repeat show.
                if (state.Offers.Count >= MaximumOffers || string.IsNullOrWhiteSpace(eventId))
                    throw new InvalidDataException("Reward show bound/identity is invalid.");
                result = new RewardShownEvidence { CheckoutSessionId = checkout, ObservationId = Id(), Identity = identity,
                    CartComplete = cartComplete && ValidIdentity(identity) && ValidLines(cart), Lines = cart.Take(MaximumLines).ToList() };
                state.Offers.Add(new Offer { EventId = eventId, Barcode = Clean(barcode), Sku = Clean(sku),
                    ShownAt = shownAt, Evidence = result });
            });
            return _unavailable || _failed.Contains(checkout) ? null : result;
        }
    }

    public RewardSaleEvidence BuildSale(string checkout, StandardNReceiptDocument document,
        RewardSourceIdentity identity, DateTimeOffset completedAt)
    {
        lock (_gate)
        {
            State? state = null;
            try { state = Read(checkout, create: false); } catch { Fail(checkout); }
            var paid = StandardNReceiptProjection.IsPostedPhysicalSale(document);
            var completion = new RewardCompletionEvidence {
                Status = paid ? "paid" : "unknown", Source = paid ? "standardn-posted-print" : "",
                CompletedAt = completedAt, Identity = identity, SourceDocumentId = document.DocumentId,
                DocumentStatus = document.Status, FinalDetailsRead = document.FinalDetailsRead, SignedTotal = document.SignedTotal,
                DocumentType = document.DocumentType,
                DeviceNumber = document.DeviceNumber ?? "", FiscalNumber = document.FiscalNumber ?? 0, FiscalShift = document.FiscalShift ?? 0,
                Lines = document.Lines.Take(MaximumLines).Select(line => new RewardPaidLine {
                    SourceDocumentId = line.SourceDocumentId ?? 0, SourceLineId = line.LineId, Sku = line.PartId.ToString(CultureInfo.InvariantCulture),
                    ObservedSourceLineId = line.ObservedSourceLineId, LineLinkSource = line.LineLinkSource,
                    Barcode = Clean(line.Barcode), Qty = line.Qty, SignedQuantity = line.SignedQuantity ?? 0,
                    SignedPrice = line.SignedPrice ?? -1, SignedAmount = line.SignedAmount ?? 1, UnitSemantics = line.UnitSemantics,
                }).ToList(),
            };
            var exact = state != null && document.Lines.Count <= MaximumLines && document.Lines.All(line =>
                (!document.FinalLineLinkRequired || line.ObservedSourceLineId is > 0 && line.LineLinkSource == StandardNFinalLineLinker.Source) &&
                state.Lines.Any(previous => previous.Line.SourceDocumentId == document.DocumentId &&
                    previous.Line.SourceLineId == (line.ObservedSourceLineId ?? line.LineId) &&
                    previous.Line.Qty == line.Qty && previous.Line.Barcode == Clean(line.Barcode) &&
                    previous.Line.Sku == line.PartId.ToString(CultureInfo.InvariantCulture) && previous.Line.UnitSemantics == line.UnitSemantics));
            return new RewardSaleEvidence { CheckoutSessionId = checkout, JournalComplete = state?.Complete == true &&
                !_unavailable && !_failed.Contains(checkout) && paid && exact && ValidIdentity(identity) && state.LastIdentity == identity &&
                document.SessionId == identity.NativeSessionId && document.Lines.All(line => line.UnitSemantics == "full_package" && decimal.Truncate(line.Qty) == line.Qty),
                Changes = state?.Changes.ToList() ?? new(), Completion = completion };
        }
    }

    // The existing physical-group owner calls this only after ALL documents were durably enqueued.
    // Both shown baselines and paid evidence then live in the durable outbox, including offline retry.
    public void Retire(string checkout)
    {
        lock (_gate)
        {
            try { var path = PathFor(checkout); if (File.Exists(path)) { RejectLink(path); File.Delete(path); } }
            catch { /* A retained bounded file is safer than forgetting unqueued evidence. */ }
            _failed.Remove(checkout);
        }
    }

    private void Observe(State state, RewardSourceIdentity identity, IReadOnlyList<RewardCartLine> cart,
        DateTimeOffset at, bool cartComplete)
    {
        if (state.LastAt > at || !cartComplete || !ValidIdentity(identity) || !ValidLines(cart)) state.Complete = false;
        if (state.LastIdentity != null && state.LastIdentity != identity) state.Complete = false;
        state.LastIdentity = identity;
        state.LastAt = at;
        if (cart.Count > MaximumLines || cart.Select(Key).Distinct().Count() != cart.Count)
        { state.Complete = false; return; }
        var incoming = cart.Select(line => line with { Barcode = Clean(line.Barcode), Sku = Clean(line.Sku) }).ToArray();
        var old = state.Lines.ToDictionary(line => Key(line.Line), StringComparer.Ordinal);
        var next = new List<ObservedLine>();
        foreach (var line in incoming)
        {
            old.Remove(Key(line), out var previous);
            var sameProduct = previous != null && previous.Line.Sku == line.Sku && previous.Line.Barcode == line.Barcode &&
                previous.Line.UnitSemantics == line.UnitSemantics;
            if (previous != null && !sameProduct)
            {
                state.Complete = false;
                AddChange(state, previous.Line, previous.Generation, previous.Line.Qty, 0, identity, at);
            }
            var generation = sameProduct ? previous!.Generation : Id();
            if (state.Initialized && (!sameProduct || previous!.Line.Qty != line.Qty))
                AddChange(state, line, generation, sameProduct ? previous!.Line.Qty : 0, line.Qty, identity, at);
            next.Add(new() { Line = line, Generation = generation });
        }
        foreach (var removed in old.Values) AddChange(state, removed.Line, removed.Generation, removed.Line.Qty, 0, identity, at);
        state.Lines = next;
        state.Initialized = true;
    }

    private static void AddChange(State state, RewardCartLine line, string generation, decimal before, decimal after,
        RewardSourceIdentity identity, DateTimeOffset at)
    {
        if (state.Changes.Count >= MaximumChanges) { state.Complete = false; return; }
        var offer = state.Offers.Where(offer => offer.ShownAt <= at &&
            (!string.IsNullOrWhiteSpace(line.Barcode) ? offer.Barcode == line.Barcode :
                !string.IsNullOrWhiteSpace(line.Sku) && offer.Sku == line.Sku))
            .OrderByDescending(offer => offer.ShownAt).FirstOrDefault();
        state.Changes.Add(new RewardLineChange { ObservationId = Id(), EventId = offer?.EventId ?? "", GenerationId = generation,
            Sequence = ++state.Sequence, ObservedAt = at, Identity = identity, SourceDocumentId = line.SourceDocumentId,
            SourceLineId = line.SourceLineId, Sku = line.Sku, Barcode = line.Barcode,
            BeforeQty = before, AfterQty = after, UnitSemantics = line.UnitSemantics });
    }

    private static bool ValidIdentity(RewardSourceIdentity identity) => !string.IsNullOrWhiteSpace(identity.SourceNamespace) &&
        !string.IsNullOrWhiteSpace(identity.RawUserId) && identity.NativeSessionId > 0 && !string.IsNullOrWhiteSpace(identity.WorkstationId) &&
        identity.IdentityStatus is "match" or "unknown";
    private static bool ValidLines(IReadOnlyList<RewardCartLine> lines) => lines.Count <= MaximumLines &&
        lines.Select(Key).Distinct().Count() == lines.Count && lines.All(line => line.SourceDocumentId > 0 && line.SourceLineId > 0 &&
            line.Qty > 0 && decimal.Truncate(line.Qty) == line.Qty && line.UnitSemantics == "full_package" && !string.IsNullOrWhiteSpace(line.Barcode));
    private static string Key(RewardCartLine line) => line.SourceDocumentId.ToString(CultureInfo.InvariantCulture) + ":" + line.SourceLineId.ToString(CultureInfo.InvariantCulture);
    private static string? Clean(string? value) => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
    private static string Id() => Guid.NewGuid().ToString("N");
    private static string Hash(byte[] bytes) => Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
    private string PathFor(string checkout) => Path.Combine(_root, Hash(Encoding.UTF8.GetBytes(checkout)) + ".json");

    private void TryChange(string checkout, Action<State> change)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(checkout) || checkout.Length > 128) throw new InvalidDataException("Invalid reward checkout.");
            var state = Read(checkout, create: true)!;
            change(state);
            if (_unavailable || _failed.Contains(checkout)) state.Complete = false;
            Write(checkout, state);
        }
        catch { Fail(checkout); }
    }

    private void Fail(string checkout)
    {
        if (_failed.Count < MaximumCheckouts || _failed.Contains(checkout)) _failed.Add(checkout);
        else _unavailable = true;
    }

    private State? Read(string checkout, bool create)
    {
        RejectLink(_root);
        var path = PathFor(checkout);
        if (!File.Exists(path)) return create ? new State { Checkout = checkout, ObserverInstance = _instance } : null;
        RejectLink(path);
        if (new FileInfo(path).Length > MaximumFileBytes) throw new InvalidDataException("Reward journal size exceeded.");
        var envelope = JsonSerializer.Deserialize<Envelope>(File.ReadAllBytes(path), EpharmJson.Options) ?? throw new InvalidDataException("Missing reward envelope.");
        if (envelope.Version != 1 || envelope.Hash != Hash(Encoding.UTF8.GetBytes(envelope.Payload))) throw new InvalidDataException("Invalid reward journal checksum.");
        var state = JsonSerializer.Deserialize<State>(envelope.Payload, EpharmJson.Options) ?? throw new InvalidDataException("Missing reward journal.");
        if (state.Checkout != checkout || state.Lines.Count > MaximumLines || state.Changes.Count > MaximumChanges || state.Offers.Count > MaximumOffers)
            throw new InvalidDataException("Invalid reward journal identity/bounds.");
        // The unobserved crash/restart interval is UNKNOWN; preserve evidence but never invent additions.
        if (state.ObserverInstance != _instance) { state.Complete = false; state.ObserverInstance = _instance; }
        return state;
    }

    private void Write(string checkout, State state)
    {
        var path = PathFor(checkout);
        var payload = JsonSerializer.Serialize(state, EpharmJson.Options);
        var bytes = JsonSerializer.SerializeToUtf8Bytes(new Envelope { Payload = payload, Hash = Hash(Encoding.UTF8.GetBytes(payload)) }, EpharmJson.Options);
        var files = Directory.GetFiles(_root);
        foreach (var file in files) RejectLink(file);
        // Include orphaned temporary files and the replacement's peak space; never erase evidence to fit.
        if (bytes.Length > MaximumFileBytes || !File.Exists(path) && files.Count(file => file.EndsWith(".json", StringComparison.Ordinal)) >= MaximumCheckouts ||
            files.Sum(file => new FileInfo(file).Length) + bytes.Length > MaximumBytes)
            throw new InvalidDataException("Reward journal bound exceeded.");
        if (File.Exists(path)) RejectLink(path);
        var temporary = path + ".tmp-" + Id();
        try
        {
            using (var file = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            { file.Write(bytes); file.Flush(true); }
            if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(temporary, UnixFileMode.UserRead | UnixFileMode.UserWrite);
            File.Move(temporary, path, true);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }
    private static void RejectLink(string path)
    { if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0) throw new InvalidDataException("Reward evidence link is not accepted."); }

    public sealed class Envelope { public int Version { get; set; } = 1; public string Payload { get; set; } = ""; public string Hash { get; set; } = ""; }
    public sealed class ObservedLine { public RewardCartLine Line { get; set; } = new(); public string Generation { get; set; } = ""; }
    public sealed class Offer {
        public string EventId { get; set; } = ""; public string? Barcode { get; set; } public string? Sku { get; set; }
        public DateTimeOffset ShownAt { get; set; } public RewardShownEvidence Evidence { get; set; } = new();
    }
    public sealed class State {
        public string Checkout { get; set; } = ""; public string ObserverInstance { get; set; } = "";
        public bool Complete { get; set; } = true; public bool Initialized { get; set; } public long Sequence { get; set; }
        public DateTimeOffset LastAt { get; set; } public RewardSourceIdentity? LastIdentity { get; set; }
        public List<ObservedLine> Lines { get; set; } = new(); public List<Offer> Offers { get; set; } = new(); public List<RewardLineChange> Changes { get; set; } = new();
    }
}
