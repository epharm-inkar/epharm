using System.Data;
using System.Text.Json;
using CustomerDisplay.Config;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class StandardNUnpaidGroupAbsenceTests
{
    private static readonly string Source = new('a', 64);
    private static readonly DateTime NativeTime = new(2026, 11, 15, 10, 0, 0, DateTimeKind.Unspecified);

    private static StandardNObservedReceiptGroup Group()
    {
        var original = ObservedReceiptGroupTests.Group(100);
        var first = original.Snapshot.Documents!.Single();
        var line = first.Lines.Single() with { LineId = 602, SourceDocumentId = 101 };
        var child = first with { DocumentId = 101, Lines = [line] };
        return original with {
            SourceNamespace = Source,
            Snapshot = original.Snapshot with { WorkstationId = "77", Documents = [first, child], Lines = [first.Lines[0], line] },
            ObservedActiveDocumentIds = [100, 101],
        };
    }

    private static StandardNReceiptSessionRow Session() => new(1, "cashier", 77, "CASH-1", "zkassa.exe");

    [Fact]
    public void CompleteNativeAbsenceBindsOriginalGroupWithoutChangingItsEvidence()
    {
        var group = Group();
        var before = JsonSerializer.Serialize(group, EpharmJson.Options);
        Assert.True(StandardNDbLookup.CanInspectUnpaidGroup(group, Source, "synthetic-pharmacy", "CASH-1"));
        Assert.True(StandardNDbLookup.UnpaidGroupSessionMatches(group, "CASH-1", [Session()]));
        var proof = ReadProof(group);
        Assert.NotNull(proof);
        Assert.Equal(Source, proof.SourceNamespace);
        Assert.Equal(StandardNUnpaidGroupAbsenceProof.Fingerprint(group), proof.GroupFingerprint);
        Assert.Equal(71, proof.DatabaseTransactionId);
        Assert.Equal(NativeTime, proof.DatabaseObservedAt);
        Assert.Equal(DateTimeKind.Unspecified, proof.DatabaseObservedAt.Kind);
        Assert.Equal(before, JsonSerializer.Serialize(group, EpharmJson.Options));
    }

    [Theory]
    [InlineData("one-known-header", "HEADER_ID", 100)]
    [InlineData("extra-linked-sibling", "HEADER_ID", 102)]
    [InlineData("held-or-pending-header", "HEADER_ID", 101)]
    [InlineData("orphan-active-detail", "ACTIVE_DETAIL_ID", 602)]
    [InlineData("orphan-posted-detail", "FINAL_DETAIL_ID", 450)]
    public void AnyRemainingNativeRowBlocksAbsenceEvenWhenOtherMembersDisappeared(string scenario, string column, long id)
    {
        Assert.False(string.IsNullOrWhiteSpace(scenario));
        Assert.Null(ReadProof(Group(), table => table.Rows[0][column] = id));
    }

    [Theory]
    [InlineData("query-no-row")]
    [InlineData("missing-header-column")]
    [InlineData("missing-active-column")]
    [InlineData("missing-final-column")]
    [InlineData("missing-time-column")]
    [InlineData("invalid-time")]
    [InlineData("missing-time")]
    [InlineData("zero-transaction")]
    [InlineData("negative-transaction")]
    [InlineData("string-transaction")]
    public void FailedIncompleteOrUnsupportedNativeProbeNeverBecomesAbsence(string defect)
    {
        Assert.Null(ReadProof(Group(), table => {
            switch (defect) {
                case "query-no-row": table.Rows.Clear(); break;
                case "missing-header-column": table.Columns.Remove("HEADER_ID"); break;
                case "missing-active-column": table.Columns.Remove("ACTIVE_DETAIL_ID"); break;
                case "missing-final-column": table.Columns.Remove("FINAL_DETAIL_ID"); break;
                case "missing-time-column": table.Columns.Remove("DB_NOW"); break;
                case "invalid-time": table.Rows[0]["DB_NOW"] = "2026-11-15"; break;
                case "missing-time": table.Rows[0]["DB_NOW"] = DBNull.Value; break;
                case "zero-transaction": table.Rows[0]["TRANSACTION_ID"] = 0L; break;
                case "negative-transaction": table.Rows[0]["TRANSACTION_ID"] = -1L; break;
                case "string-transaction": table.Rows[0]["TRANSACTION_ID"] = "71"; break;
            }
        }));
    }

    [Theory]
    [InlineData("print")]
    [InlineData("cue")]
    [InlineData("queue")]
    [InlineData("empty-resolved")]
    [InlineData("print-time")]
    [InlineData("anchored")]
    [InlineData("initially-posted")]
    [InlineData("posted")]
    [InlineData("held")]
    [InlineData("read-final")]
    [InlineData("wrong-pharmacy")]
    [InlineData("wrong-machine")]
    [InlineData("missing-workstation")]
    [InlineData("unobserved-child")]
    [InlineData("missing-documents")]
    public void OnlyCompleteUnpaidUnanchoredGroupsCanBeInspected(string defect)
    {
        var group = Group();
        group = defect switch {
            "print" => group with { PrintObserved = true },
            "cue" => group with { PendingPrint = new(NativeTime, [100], new string('b', 32)) },
            "queue" => group with { QueuedDocumentIds = [100] },
            "empty-resolved" => group with { ResolvedEmptyDocumentIds = [100] },
            "print-time" => group with { PrintDocumentTimes = new Dictionary<long, DateTimeOffset> { [100] = DateTimeOffset.UtcNow } },
            "anchored" => group with { AnchoredPrintCueIds = [new string('b', 32)] },
            "initially-posted" => group with { InitiallyPostedDocumentIds = [100] },
            "posted" => group with { Snapshot = group.Snapshot with { Documents = [group.Snapshot.Documents![0] with { Status = 1 }, group.Snapshot.Documents[1]] } },
            "held" => group with { Snapshot = group.Snapshot with { Documents = [group.Snapshot.Documents![0] with { Status = 2 }, group.Snapshot.Documents[1]] } },
            "read-final" => group with { Snapshot = group.Snapshot with { Documents = [group.Snapshot.Documents![0] with { FinalDetailsRead = true }, group.Snapshot.Documents[1]] } },
            "missing-workstation" => group with { Snapshot = group.Snapshot with { WorkstationId = null } },
            "unobserved-child" => group with { ObservedActiveDocumentIds = [100] },
            "missing-documents" => group with { Snapshot = group.Snapshot with { Documents = null } },
            _ => group,
        };
        Assert.False(StandardNDbLookup.CanInspectUnpaidGroup(group, Source,
            defect == "wrong-pharmacy" ? "different" : "synthetic-pharmacy", defect == "wrong-machine" ? "CASH-2" : "CASH-1"));
    }

    [Theory]
    [InlineData("absent")]
    [InlineData("ambiguous")]
    [InlineData("foreign-session")]
    [InlineData("foreign-user")]
    [InlineData("foreign-workstation")]
    [InlineData("foreign-machine")]
    [InlineData("other-program")]
    public void OriginalSessionMustBeOneExactNativeRowWithoutARecencyFallback(string defect)
    {
        StandardNReceiptSessionRow[] sessions = [Session()];
        sessions = defect switch {
            "absent" => [], "ambiguous" => [Session(), Session()],
            "foreign-session" => [Session() with { SessionId = 2 }],
            "foreign-user" => [Session() with { UserId = "other" }],
            "foreign-workstation" => [Session() with { WorkstationId = 88 }],
            "foreign-machine" => [Session() with { WorkstationName = "CASH-2" }],
            "other-program" => [Session() with { Program = "manager" }],
            _ => sessions,
        };
        Assert.False(StandardNDbLookup.UnpaidGroupSessionMatches(Group(), "CASH-1", sessions));
    }

    [Fact]
    public void PersistedLegacyNamespaceMayBeSuppliedButCannotContradictTheGroupOrOpenedConnection()
    {
        var group = Group();
        var other = new string('b', 64);
        Assert.False(StandardNDbLookup.UnpaidGroupSourceMatches(group, other, other));
        Assert.False(StandardNDbLookup.UnpaidGroupSourceMatches(group, Source, other));
        var legacy = group with { SourceNamespace = null };
        Assert.True(StandardNDbLookup.UnpaidGroupSourceMatches(legacy, Source, Source));
        Assert.False(StandardNDbLookup.UnpaidGroupSourceMatches(legacy, "", Source));
        Assert.False(StandardNDbLookup.UnpaidGroupSourceMatches(legacy, null, Source));
        Assert.False(StandardNDbLookup.UnpaidGroupSourceMatches(legacy, other, Source));
        using var lookup = new StandardNDbLookup(new EpharmConfig { StandardNDbEnabled = true, PharmacyId = "synthetic-pharmacy" }, _ => { }, "CASH-1");
        Assert.False(lookup.TryGetUnpaidGroupAbsenceProof(legacy, out var proof));
        Assert.Null(proof); // Missing recorded provenance returns before opening any connection.
        Assert.False(lookup.TryGetUnpaidGroupAbsenceProof(group, other, out proof));
        Assert.Null(proof);
    }

    [Fact]
    public void ProbeIsBoundedAndChecksOrphanDetailsAndAnyLinkedFamilyWithoutStatusFilters()
    {
        var sql = StandardNDbLookup.UnpaidGroupAbsenceSql(2);
        Assert.Contains("d.ID in (@doc0,@doc1)", sql);
        Assert.Contains("d.LINK_ID in (@doc0,@doc1)", sql);
        Assert.Contains("d.PARENT_ID in (@doc0,@doc1)", sql);
        Assert.Contains("from DOC_DETAIL_ACTIVE l where l.DOC_ID in (@doc0,@doc1)", sql);
        Assert.Contains("from DOC_DETAIL l where l.DOC_ID in (@doc0,@doc1)", sql);
        Assert.Contains("CURRENT_TRANSACTION", sql);
        Assert.Contains("cast(CURRENT_TIMESTAMP as timestamp)", sql);
        Assert.DoesNotContain("STATUS=", sql); Assert.DoesNotContain("AUDIT_ID", sql);
        Assert.DoesNotContain("join DOCS", sql); Assert.DoesNotContain("delete", sql, StringComparison.OrdinalIgnoreCase);
        Assert.Throws<InvalidDataException>(() => StandardNDbLookup.UnpaidGroupAbsenceSql(0));
        Assert.Throws<InvalidDataException>(() => StandardNDbLookup.UnpaidGroupAbsenceSql(501));
        var session = StandardNDbLookup.UnpaidGroupOriginalSessionSql;
        Assert.Contains("first 2", session); Assert.Contains("where s.ID=@sessionId", session);
        Assert.DoesNotContain("ENDDT", session); Assert.DoesNotContain("HEARTBEATS", session);
    }

    private static StandardNUnpaidGroupAbsenceProof? ReadProof(StandardNObservedReceiptGroup group, Action<DataTable>? change = null)
    {
        using var table = new DataTable();
        table.Columns.Add("TRANSACTION_ID", typeof(object)); table.Columns.Add("DB_NOW", typeof(object));
        foreach (var column in new[] { "HEADER_ID", "ACTIVE_DETAIL_ID", "FINAL_DETAIL_ID" }) table.Columns.Add(column, typeof(long));
        table.Rows.Add(71L, NativeTime, DBNull.Value, DBNull.Value, DBNull.Value);
        change?.Invoke(table);
        using var reader = table.CreateDataReader();
        return reader.Read() ? StandardNDbLookup.ReadUnpaidGroupAbsenceProof(group, Source, reader) : null;
    }
}
