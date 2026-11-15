using System.Text.Json;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class UnresolvedObservedGroupArchiveTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "epharm-unresolved-store-" + Guid.NewGuid().ToString("N"));
    private static readonly string Source = new('a', 64);
    private static readonly DateTime At = new(2026, 11, 15, 0, 0, 0, DateTimeKind.Utc);
    private string Receipts => Path.Combine(_root, "receipts");
    private string Archives => Path.Combine(Receipts, "unresolved-observed-groups");
    private string Index => Path.Combine(Receipts, "observed-groups", "groups.json");
    private ReceiptArtifactStore Store() => new(Receipts, new FiscalReceiptInboxSource(Path.Combine(_root, "inbox"), ["ofd-api"]),
        utcNow: () => new DateTimeOffset(At));
    private static StandardNObservedReceiptGroup Group(long id) => ObservedReceiptGroupTests.Group(id) with { SourceNamespace = Source };
    private static string Audit(StandardNObservedReceiptGroup group, long transaction = 1, string? source = null) =>
        JsonSerializer.Serialize(new
        {
            reason = "source_disappeared_unresolved",
            first = new StandardNUnpaidGroupAbsenceProof(source ?? Source, StandardNUnpaidGroupAbsenceProof.Fingerprint(group), transaction, At),
            second = new StandardNUnpaidGroupAbsenceProof(source ?? Source, StandardNUnpaidGroupAbsenceProof.Fingerprint(group), transaction + 1, At.AddSeconds(2)),
        }, EpharmJson.Options);

    [Fact]
    public void NinetySixUnresolvedArchivesRestartAndFreshPaidReceiptDoNotExhaustHotSlots()
    {
        var held = Group(9000);
        Assert.True(Store().SaveObservedGroup(held));
        for (var id = 1; id <= 96; id++)
        {
            var group = Group(id);
            Assert.True(Store().SaveObservedGroup(group));
            Assert.True(Store().ArchiveUnresolvedObservedGroup(group, Audit(group)));
            Assert.Equal(held.CheckoutSessionId, Assert.Single(Store().ReadObservedGroups()).CheckoutSessionId);
        }
        Assert.Equal(96, Directory.GetFiles(Archives, "*.json").Length);
        var fresh = Group(10000);
        var posted = Assert.Single(fresh.Snapshot.Documents!) with
        { Status = 1, DeviceNumber = "synthetic-register", FiscalNumber = 1, FiscalShift = 1, FinalDetailsRead = true };
        var paid = Printed(fresh) with { QueuedDocumentIds = [10000], Snapshot = fresh.Snapshot with { Documents = [posted] } };
        Assert.True(Store().SaveObservedGroup(paid));
        Store().RetireObservedGroup(paid); // Existing paid retirement contract remains usable.
        Assert.Equal(held.CheckoutSessionId, Assert.Single(Store().ReadObservedGroups()).CheckoutSessionId);
    }

    [Fact]
    public void ArchiveKeepsExactOriginalDraftAndFirstAuditAcrossDifferentAuditRetryAndRestart()
    {
        var group = Group(10); var store = Store();
        Assert.True(store.SaveObservedGroup(group));
        var saleId = ReceiptSaleId.Create(group.PharmacyId, 10, group.CheckoutSessionId);
        store.SaveDraft(new SaleReport { SaleId = saleId, CaptureSource = "standardn-active" });
        var draft = Path.Combine(Receipts, "active", saleId, "sale.json"); var before = File.ReadAllBytes(draft);
        Assert.True(store.ArchiveUnresolvedObservedGroup(group, Audit(group)));
        var path = Assert.Single(Directory.GetFiles(Archives, "*.json")); var bytes = File.ReadAllBytes(path);
        Assert.True(Store().ArchiveUnresolvedObservedGroup(group, Audit(group, 20)));
        Assert.Equal(bytes, File.ReadAllBytes(path)); Assert.Equal(before, File.ReadAllBytes(draft));
        using var outer = JsonDocument.Parse(bytes);
        using var payload = JsonDocument.Parse(outer.RootElement.GetProperty("payloadJson").GetString()!);
        Assert.Equal("source_disappeared_unresolved", payload.RootElement.GetProperty("reason").GetString());
        Assert.Equal(JsonSerializer.Serialize(group, EpharmJson.Options), payload.RootElement.GetProperty("originalGroupJson").GetString());
        Assert.Equal(Audit(group), payload.RootElement.GetProperty("confirmationAuditJson").GetString());
        if (!OperatingSystem.IsWindows())
        {
            Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite, File.GetUnixFileMode(path));
            Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute, File.GetUnixFileMode(Archives));
        }
    }

    [Fact]
    public void CrashAfterArchiveBeforeIndexReplacementCannotRestorePhysicalIdentityOrCheckout()
    {
        var group = Group(20); Assert.True(Store().SaveObservedGroup(group)); var oldIndex = File.ReadAllBytes(Index);
        Assert.True(Store().ArchiveUnresolvedObservedGroup(group, Audit(group)));
        var archive = Assert.Single(Directory.GetFiles(Archives, "*.json")); var bytes = File.ReadAllBytes(archive);
        File.WriteAllBytes(Index, oldIndex); // Simulate a crash between archive commit and hot-index commit.
        Assert.Empty(Store().ReadObservedGroups());
        Assert.False(Store().SaveObservedGroup(group));
        Assert.False(Store().SaveObservedGroup(group with { CheckoutSessionId = "sess_replacement" }));
        Assert.False(Store().SaveObservedGroup(Group(21) with { CheckoutSessionId = group.CheckoutSessionId }));
        Assert.False(Store().SaveObservedGroup(group with { SourceNamespace = new string('b', 64) }));
        Assert.True(Store().ArchiveUnresolvedObservedGroup(group, Audit(group, 30)));
        Assert.Empty(Store().ReadObservedGroups()); Assert.Equal(bytes, File.ReadAllBytes(archive));
    }

    [Fact]
    public void ChangedGroupAndConflictingArchiveRemainIntact()
    {
        var group = Group(30); Assert.True(Store().SaveObservedGroup(group));
        var changed = group with { Snapshot = group.Snapshot with { OpenedAt = new DateTimeOffset(At) } };
        Assert.True(Store().SaveObservedGroup(changed)); var index = File.ReadAllBytes(Index);
        Assert.Throws<InvalidDataException>(() => Store().ArchiveUnresolvedObservedGroup(group, Audit(group)));
        Assert.Equal(index, File.ReadAllBytes(Index)); Assert.False(Directory.Exists(Archives));
        Assert.True(Store().ArchiveUnresolvedObservedGroup(changed, Audit(changed)));
        var archive = Assert.Single(Directory.GetFiles(Archives)); var bytes = File.ReadAllBytes(archive);
        Assert.Throws<InvalidDataException>(() => Store().ArchiveUnresolvedObservedGroup(group, Audit(group)));
        Assert.Equal(bytes, File.ReadAllBytes(archive));
    }

    [Theory]
    [InlineData("pending")]
    [InlineData("printed")]
    [InlineData("queued")]
    [InlineData("initially_posted")]
    [InlineData("resolved_empty")]
    [InlineData("fiscal")]
    [InlineData("posted")]
    [InlineData("unknown")]
    [InlineData("final_details")]
    public void PrintPaidUnknownAndFailedQueueEvidenceCannotBeArchived(string kind)
    {
        var group = Group(40); var document = Assert.Single(group.Snapshot.Documents!);
        group = kind switch
        {
            "pending" => group with { PendingPrint = new(new DateTimeOffset(At), [40], Guid.NewGuid().ToString("N")) },
            "printed" => Printed(group),
            "queued" => Printed(group) with { QueuedDocumentIds = [40] },
            "initially_posted" => group with { InitiallyPostedDocumentIds = [40], ObservedActiveDocumentIds = [] },
            "resolved_empty" => Printed(group) with { ResolvedEmptyDocumentIds = [40] },
            "fiscal" => group with { Snapshot = group.Snapshot with { Documents = [document with { FiscalNumber = 1 }] } },
            "posted" => group with { Snapshot = group.Snapshot with { Documents = [document with { Status = 1 }] } },
            "unknown" => group with { Snapshot = group.Snapshot with { Documents = [document with { Status = 2 }] } },
            _ => group with { Snapshot = group.Snapshot with { Documents = [document with { FinalDetailsRead = true }] } },
        };
        Assert.True(Store().SaveObservedGroup(group)); var bytes = File.ReadAllBytes(Index);
        Assert.Throws<InvalidDataException>(() => Store().ArchiveUnresolvedObservedGroup(group, Audit(group)));
        Assert.Equal(bytes, File.ReadAllBytes(Index)); Assert.Single(Store().ReadObservedGroups());
    }

    [Fact]
    public void PendingSaleAfterQueueFailureProtectsUnprintedGroupAndAllReceiptBytes()
    {
        var group = Group(50); var store = Store(); Assert.True(store.SaveObservedGroup(group));
        var saleId = ReceiptSaleId.Create(group.PharmacyId, 50, group.CheckoutSessionId);
        store.Complete(new SaleReport { SaleId = saleId, CaptureSource = "standardn-print-log" });
        var pending = Path.Combine(Receipts, "pending", saleId, "sale.json"); var before = File.ReadAllBytes(pending);
        Assert.Throws<InvalidDataException>(() => store.ArchiveUnresolvedObservedGroup(group, Audit(group)));
        Assert.Single(Store().ReadObservedGroups()); Assert.Equal(before, File.ReadAllBytes(pending));
    }

    [Fact]
    public void LegacyNullNamespaceUsesTwoMatchingJournalSourceProofsWithoutRetroactiveMutation()
    {
        var group = Group(60) with { SourceNamespace = null }; Assert.True(Store().SaveObservedGroup(group));
        Assert.Throws<InvalidDataException>(() => Store().SaveObservedGroup(group with { SourceNamespace = Source }));
        Assert.Null(Assert.Single(Store().ReadObservedGroups()).SourceNamespace);
        Assert.ThrowsAny<Exception>(() => Store().ArchiveUnresolvedObservedGroup(group, "{}"));
        Assert.ThrowsAny<Exception>(() => Store().ArchiveUnresolvedObservedGroup(group, Audit(Group(61))));
        Assert.True(Store().ArchiveUnresolvedObservedGroup(group, Audit(group)));
        using var outer = JsonDocument.Parse(File.ReadAllText(Assert.Single(Directory.GetFiles(Archives))));
        using var archive = JsonDocument.Parse(outer.RootElement.GetProperty("payloadJson").GetString()!);
        var saved = JsonSerializer.Deserialize<StandardNObservedReceiptGroup>(archive.RootElement.GetProperty("originalGroupJson").GetString()!, EpharmJson.Options)!;
        Assert.Null(saved.SourceNamespace);
    }

    [Fact]
    public void AuditCannotMixSourcesFingerprintsOrSameTransaction()
    {
        var group = Group(65); Assert.True(Store().SaveObservedGroup(group));
        foreach (var audit in new[]
        {
            Audit(group, source: new string('b', 64)),
            Audit(group).Replace("\"databaseTransactionId\":2", "\"databaseTransactionId\":1"),
            Audit(group).Replace("\"sourceNamespace\":\"" + Source + "\"", "\"sourceNamespace\":\"unknown\""),
        }) Assert.ThrowsAny<Exception>(() => Store().ArchiveUnresolvedObservedGroup(group, audit));
        Assert.Single(Store().ReadObservedGroups()); Assert.False(Directory.Exists(Archives));
    }

    [Fact]
    public void ArchiveFailureAndCorruptionNeverRemoveOrOverwriteHotEvidence()
    {
        var group = Group(70); Assert.True(Store().SaveObservedGroup(group)); var index = File.ReadAllBytes(Index);
        File.WriteAllText(Archives, "occupied; retain");
        Assert.ThrowsAny<Exception>(() => Store().ArchiveUnresolvedObservedGroup(group, Audit(group)));
        Assert.Equal(index, File.ReadAllBytes(Index)); Assert.Equal("occupied; retain", File.ReadAllText(Archives));
        File.Delete(Archives); // Test fixture obstacle, not an application retention operation.
        Assert.True(Store().ArchiveUnresolvedObservedGroup(group, Audit(group)));
        File.WriteAllBytes(Index, index); var archive = Assert.Single(Directory.GetFiles(Archives));
        File.AppendAllText(archive, "corrupt"); var corrupt = File.ReadAllBytes(archive);
        Assert.ThrowsAny<Exception>(() => Store().ReadObservedGroups());
        Assert.ThrowsAny<Exception>(() => Store().ArchiveUnresolvedObservedGroup(group, Audit(group)));
        Assert.Equal(corrupt, File.ReadAllBytes(archive)); Assert.Equal(index, File.ReadAllBytes(Index));
    }

    [Fact]
    public void LinkedArchiveRootCannotWriteOutsidePrivateStore()
    {
        var group = Group(80); Assert.True(Store().SaveObservedGroup(group)); var before = File.ReadAllBytes(Index);
        var outside = Path.Combine(_root, "outside"); Directory.CreateDirectory(outside); File.WriteAllText(Path.Combine(outside, "keep.txt"), "keep");
        TestDirectoryLink.Create(Archives, outside);
        Assert.ThrowsAny<Exception>(() => Store().ArchiveUnresolvedObservedGroup(group, Audit(group)));
        Assert.Equal(before, File.ReadAllBytes(Index)); Assert.Equal("keep", File.ReadAllText(Path.Combine(outside, "keep.txt")));
        Assert.Single(Directory.GetFiles(outside));
    }

    private static StandardNObservedReceiptGroup Printed(StandardNObservedReceiptGroup group) => group with
    {
        PrintObserved = true,
        PrintDocumentTimes = new Dictionary<long, DateTimeOffset> { [group.Snapshot.DocumentId] = new DateTimeOffset(At) },
        AnchoredPrintCueIds = [Guid.NewGuid().ToString("N")],
    };

    public void Dispose()
    {
        if (Directory.Exists(Archives) && (File.GetAttributes(Archives) & FileAttributes.ReparsePoint) != 0) Directory.Delete(Archives);
        if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true);
    }
}
