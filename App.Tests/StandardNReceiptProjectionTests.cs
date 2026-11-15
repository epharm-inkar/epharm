using System.Data;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class StandardNReceiptProjectionTests
{
    [Fact]
    public void ActualReaderProjectionKeepsBothLinkedPhysicalDocumentsAndExactAmounts()
    {
        var root = Header(100, -100m);
        var child = Header(101, -360m);
        var first = Line(501, 100, 10, 100m, -1m, -100m);
        var second = Line(502, 101, 10, 200m, -2m, -360m);

        var receipt = StandardNReceiptProjection.Current(1, "CASH-1", new("cashier", null, 1),
            [root, child], [root, child], [first, second]);

        Assert.False(StandardNDbLookup.IsCurrentReceiptPosting(1, [root, child], [root, child]));
        Assert.Equal(100, receipt.DocumentId);
        Assert.Equal(100, receipt.LinkId);
        Assert.Equal(2, receipt.Documents!.Count);
        Assert.Equal(2, receipt.Lines.Count);
        Assert.Equal(460m, receipt.Lines.Sum(line => line.SourceTotal));
        Assert.Equal(360m, receipt.Documents.Single(document => document.DocumentId == 101).Lines.Single().SourceTotal);
        Assert.Equal(10m, second.DiscountPercent);
    }

    [Fact]
    public void LiveReaderHoldsMixedGroupInsteadOfAdmittingFinalRowsAsFreshCart()
    {
        var posted = Header(100, -100m) with { Status = 1, Device = "register", FiscalNumber = 100, FiscalShift = 1 };
        var active = Header(101, -200m);
        Assert.True(StandardNDbLookup.IsCurrentReceiptPosting(1, [active], [posted, active]));
        // The generic projection can represent final details; the live reader must not admit them.
        var receipt = StandardNReceiptProjection.Current(1, "CASH-1", new("cashier", null, 1), [active],
            [posted, active], [Line(501, 100, 10, 100m, -1m, -100m), Line(502, 101, 20, 200m, -1m, -200m)]);
        Assert.Equal(300m, receipt.Lines.Sum(line => line.SourceTotal));
        Assert.Equal(2, receipt.Documents!.Count);
        Assert.True(receipt.Documents.Single(document => document.DocumentId == 100).FinalDetailsRead);
        Assert.False(receipt.Documents.Single(document => document.DocumentId == 101).FinalDetailsRead);
    }

    [Fact]
    public void LiveReaderHoldsWhenChildPostsBeforeRootAndWhenRootPostsBeforeChild()
    {
        var root = Header(23664, -210m, link: 23664);
        var child = Header(23665, -710m, link: 23664);
        Assert.True(StandardNDbLookup.IsCurrentReceiptPosting(1, [root],
            [root, child with { Status = 1, Device = "register", FiscalNumber = 1, FiscalShift = 3 }]));
        Assert.True(StandardNDbLookup.IsCurrentReceiptPosting(1, [child],
            [root with { Status = 1, Device = "register", FiscalNumber = 2, FiscalShift = 3 }, child]));
        Assert.False(StandardNDbLookup.IsCurrentReceiptPosting(1, [root, child], [root, child]));
    }

    [Fact]
    public void KnownExactGroupKeepsHealthyHoldAfterLastPostingBeforeHumanPrintCue()
    {
        var known = KnownGroup();
        Assert.True(StandardNDbLookup.KnownReceiptMatchesSession(known, 1, "cashier", "77", "DOMAIN\\CASH-1.company"));
        var posted = new[] { Header(100, -100m) with { Status = 1 }, Header(101, -200m) with { Status = 1 } };
        Assert.True(StandardNDbLookup.IsKnownReceiptPosting(known, posted));
        Assert.All(known.Documents!, document => {
            Assert.Equal(0, document.Status); Assert.False(document.FinalDetailsRead);
        });
        Assert.Equal(new long[] { 501, 502 }, known.Lines.Select(line => line.LineId));
    }

    [Theory]
    [InlineData("no-snapshot")]
    [InlineData("session")]
    [InlineData("user")]
    [InlineData("workstation")]
    [InlineData("missing-workstation")]
    [InlineData("missing-known-workstation")]
    [InlineData("machine")]
    [InlineData("missing-machine")]
    [InlineData("cashier-session")]
    public void KnownPostingHoldRequiresTheSameExactCurrentSessionAndWorkstation(string defect)
    {
        StandardNReceiptSnapshot? known = KnownGroup();
        known = defect switch {
            "no-snapshot" => null,
            "missing-known-workstation" => known with { WorkstationId = null },
            "cashier-session" => known with { Pharmacist = known.Pharmacist with { SessionId = 2 } },
            _ => known,
        };
        Assert.False(StandardNDbLookup.KnownReceiptMatchesSession(known, defect == "session" ? 2 : 1,
            defect == "user" ? "different" : "cashier", defect switch {
                "workstation" => "88", "missing-workstation" => null, _ => "77",
            }, defect switch { "machine" => "CASH-2", "missing-machine" => "", _ => "CASH-1" }));
    }

    [Theory]
    [InlineData("deleted")]
    [InlineData("new-member")]
    [InlineData("cancelled")]
    [InlineData("suspended")]
    [InlineData("still-active")]
    [InlineData("missing-known-documents")]
    [InlineData("wrong-known-link")]
    public void DeletedCancelledSuspendedOrChangedKnownGroupCannotMasqueradeAsPosting(string defect)
    {
        var known = KnownGroup();
        StandardNReceiptHeader[] members = [Header(100, -100m) with { Status = 1 }, Header(101, -200m) with { Status = 1 }];
        members = defect switch {
            "deleted" => [members[0]],
            "new-member" => [.. members, Header(102, -50m) with { Status = 1 }],
            "cancelled" => [members[0], members[1] with { Status = -1 }],
            "suspended" => [members[0], members[1] with { Status = 2 }],
            "still-active" => [members[0], members[1] with { Status = 0 }],
            _ => members,
        };
        known = defect switch {
            "missing-known-documents" => known with { Documents = null },
            "wrong-known-link" => known with { LinkId = 200 },
            _ => known,
        };
        Assert.False(StandardNDbLookup.IsKnownReceiptPosting(known, members));
    }

    [Theory]
    [InlineData("foreign-session")]
    [InlineData("foreign-link")]
    [InlineData("other-type")]
    public void ChangedPostedPhysicalIdentityIsRejectedEvenWithTheSameKnownDocumentIds(string defect)
    {
        var known = KnownGroup();
        var root = Header(100, -100m) with { Status = 1 };
        var child = Header(101, -200m) with { Status = 1 };
        child = defect switch {
            "foreign-session" => child with { SessionId = 2 },
            "foreign-link" => child with { LinkId = 200 },
            "other-type" => child with { Type = 9 },
            _ => child,
        };
        Assert.Throws<InvalidDataException>(() => StandardNDbLookup.IsKnownReceiptPosting(known, [root, child]));
    }

    [Theory]
    [InlineData("active")]
    [InlineData("held")]
    [InlineData("cancelled")]
    [InlineData("no-device")]
    [InlineData("zero-number")]
    [InlineData("zero-shift")]
    [InlineData("positive-quantity")]
    [InlineData("positive-amount")]
    [InlineData("negative-price")]
    [InlineData("wrong-doc")]
    [InlineData("duplicate-line")]
    [InlineData("wrong-total")]
    [InlineData("unread-final")]
    [InlineData("empty-nonzero")]
    public void PaymentFenceRejectsUnpaidUnrecognizedOrIncompletePhysicalProof(string defect)
    {
        var line = Line(501, 100, 10, 100m, -1m, -100m);
        var document = new StandardNReceiptDocument(100, 1, 100, 1, -100m, "register", 100, 1, [line])
            { FinalDetailsRead = true };
        document = defect switch
        {
            "active" => document with { Status = 0 },
            "held" => document with { Status = 2 },
            "cancelled" => document with { Status = -1 },
            "no-device" => document with { DeviceNumber = "" },
            "zero-number" => document with { FiscalNumber = 0 },
            "zero-shift" => document with { FiscalShift = 0 },
            "positive-quantity" => document with { Lines = [line with { SignedQuantity = 1 }] },
            "positive-amount" => document with { Lines = [line with { SignedAmount = 100 }] },
            "negative-price" => document with { Lines = [line with { SignedPrice = -100 }] },
            "wrong-doc" => document with { Lines = [line with { SourceDocumentId = 999 }] },
            "duplicate-line" => document with { Lines = [line, line] },
            "wrong-total" => document with { SignedTotal = -200 },
            "unread-final" => document with { FinalDetailsRead = false },
            "empty-nonzero" => document with { Lines = [] },
            _ => throw new InvalidOperationException(),
        };
        Assert.False(StandardNReceiptProjection.IsPostedPhysicalSale(document));
        Assert.False(StandardNReceiptProjection.IsPostedEmptyDocument(document));
    }

    [Fact]
    public void StrictPostedEmptyAndRealZeroAmountProductAreDifferentCases()
    {
        var empty = new StandardNReceiptDocument(100, 1, 100, 1, 0m, "register", 100, 1, []) { FinalDetailsRead = true };
        Assert.True(StandardNReceiptProjection.IsPostedEmptyDocument(empty));
        Assert.False(StandardNReceiptProjection.IsPostedPhysicalSale(empty));
        Assert.False(StandardNReceiptProjection.IsPostedEmptyDocument(empty with { FinalDetailsRead = false }));
        var free = empty with { Lines = [Line(501, 100, 10, 0m, -1m, 0m)] };
        Assert.True(StandardNReceiptProjection.IsPostedPhysicalSale(free));
        Assert.False(StandardNReceiptProjection.IsPostedEmptyDocument(free));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void MissingOrZeroLinkKeepsOneExactPhysicalDocumentOnly(bool columnExists)
    {
        var header = Header(100, -100m, link: columnExists ? 0 : null, hasLinkColumn: columnExists);
        var line = Line(501, 100, 10, 100m, -1m, -100m);
        var receipt = StandardNReceiptProjection.Current(1, "CASH-1", new("cashier", null, 1), [header], [header], [line]);
        Assert.Equal(100, receipt.DocumentId);
        Assert.Null(receipt.LinkId);
        Assert.Equal(100, Assert.Single(receipt.Documents!).DocumentId);
        Assert.Throws<InvalidDataException>(() => StandardNReceiptProjection.CurrentGroup(1,
            [header, Header(101, -100m, link: 0, hasLinkColumn: columnExists)]));
    }

    [Fact]
    public void MultipleOpenLinksAreUnknownInsteadOfChoosingLatestDocument()
    {
        Assert.Throws<InvalidDataException>(() => StandardNReceiptProjection.CurrentGroup(1,
            [Header(100, -100m), Header(200, -100m, link: 200)]));
        Assert.Null(StandardNReceiptProjection.CurrentGroup(1, []));
    }

    [Theory]
    [InlineData("foreign-audit")]
    [InlineData("missing-root")]
    [InlineData("wrong-root-link")]
    [InlineData("other-type")]
    [InlineData("held-child")]
    [InlineData("changed-membership")]
    public void InvalidLinkedMembershipCannotProduceAReceiptOrAnEmptySuccess(string defect)
    {
        var root = Header(100, -100m);
        var child = Header(101, -100m);
        var active = new[] { root, child };
        StandardNReceiptHeader[] members = [root, child];
        members = defect switch
        {
            "foreign-audit" => [root, child with { SessionId = 2 }],
            "missing-root" => [child],
            "wrong-root-link" => [root with { LinkId = 200 }, child],
            "other-type" => [root, child with { Type = 4 }],
            "held-child" => [root, child with { Status = 2 }],
            "changed-membership" => [root, child, Header(102, -100m)],
            _ => throw new InvalidOperationException(),
        };
        Assert.Throws<InvalidDataException>(() => StandardNReceiptProjection.Current(1, "CASH-1", new("cashier", null, 1),
            active, members, [Line(501, 100, 10, 100m, -1m, -100m), Line(502, 101, 20, 100m, -1m, -100m)]));
        Assert.Throws<InvalidDataException>(() => StandardNDbLookup.IsCurrentReceiptPosting(1, active, members));
    }

    [Fact]
    public void ForeignDuplicateOrIncompleteDetailRowsAreUnknown()
    {
        var header = Header(100, -100m);
        var line = Line(501, 100, 10, 100m, -1m, -100m);
        void Project(params StandardNReceiptLine[] lines) => StandardNReceiptProjection.Current(1, "CASH-1",
            new("cashier", null, 1), [header], [header], lines);
        Assert.Throws<InvalidDataException>(() => Project(line with { SourceDocumentId = 999 }));
        Assert.Throws<InvalidDataException>(() => Project(line, line));
        Assert.Throws<InvalidDataException>(() => Project(line with { SourceTotal = 99m }));
        Assert.Throws<InvalidDataException>(() => Project());
    }

    [Fact]
    public void SqlIsBoundedAndUsesExactScopeAndMembershipInsteadOfNewestDocument()
    {
        Assert.Contains("first 501", StandardNReceiptProjection.ActiveDocumentsSql);
        Assert.Contains("AUDIT_ID=@sessionId", StandardNReceiptProjection.ActiveDocumentsSql);
        Assert.DoesNotContain("desc", StandardNReceiptProjection.ActiveDocumentsSql);
        Assert.Contains("ID=@rootId or LINK_ID=@rootId", StandardNReceiptProjection.LinkedDocumentsSql);
        var lines = StandardNReceiptProjection.ActiveLinesSql(2);
        Assert.Contains("first 5001", lines);
        Assert.Contains("d.AUDIT_ID=@sessionId", lines);
        Assert.Contains("d.STATUS=0", lines);
        Assert.Contains("l.DOC_ID in (@doc0,@doc1)", lines);
        Assert.Throws<InvalidDataException>(() => StandardNReceiptProjection.ActiveLinesSql(0));
        Assert.Throws<InvalidDataException>(() => StandardNReceiptProjection.ActiveLinesSql(501));
        var posted = StandardNReceiptProjection.PostedLinesSql(2);
        Assert.Contains("from DOC_DETAIL l", posted);
        Assert.DoesNotContain("DOC_DETAIL_ACTIVE", posted);
        Assert.DoesNotContain("l.*", posted);
        Assert.DoesNotContain("FIO", posted);
        Assert.Contains("d.STATUS=1", posted);
    }

    [Fact]
    public void SessionProjectionRequiresExactlyOneExactLocalWorkstationAndNeverForeignFallback()
    {
        var row = new StandardNReceiptSessionRow(1, "cashier", 77, "DOMAIN\\CASH-1.company", "zkassa");
        Assert.Equal(row, StandardNReceiptProjection.ExactLocalSession("CASH-1", [row]));
        Assert.Null(StandardNReceiptProjection.ExactLocalSession("CASH-2", [row]));
        Assert.Null(StandardNReceiptProjection.ExactLocalSession("CASH-1", [row, row with { SessionId = 2 }]));
        Assert.Null(StandardNReceiptProjection.ExactLocalSession("CASH-1", [row with { UserId = "" }]));
        Assert.Throws<InvalidDataException>(() => StandardNReceiptProjection.ExactLocalSession("CASH-1", Enumerable.Repeat(row, 101).ToArray()));
    }

    [Fact]
    public void NativeHeartbeatDiscardsCrashedOpenSessionsInsteadOfSelectingTheNewestId()
    {
        var now = new DateTimeOffset(2026, 11, 11, 17, 0, 53, TimeSpan.FromHours(6));
        var stale = new StandardNReceiptSessionRow(1716, "-1", 77, "CASH-1", "zkassa") {
            HeartbeatAt = now.AddDays(-6), DatabaseNow = now,
        };
        var live = stale with { SessionId = 1719, WorkstationId = 78, HeartbeatAt = now.AddSeconds(-53) };
        Assert.Equal(live, StandardNReceiptProjection.ExactLocalSession("CASH-1", [stale, stale with {
            SessionId = 1717, HeartbeatAt = now.AddDays(-5) }, live], requireHeartbeat: true));
        Assert.Equal(live, StandardNReceiptProjection.ExactLocalSession("CASH-1", [live, stale with {
            SessionId = 1720 }], requireHeartbeat: true)); // ID order alone is never proof.
        Assert.Null(StandardNReceiptProjection.ExactLocalSession("CASH-1", [live, live with { SessionId = 1721 }], true));
        Assert.Null(StandardNReceiptProjection.ExactLocalSession("CASH-2", [live], true));
        Assert.Throws<InvalidDataException>(() => StandardNReceiptProjection.ExactLocalSession("CASH-1",
            Enumerable.Repeat(live, 101).ToArray(), true));
    }

    [Theory]
    [InlineData(0, true)]
    [InlineData(299, true)]
    [InlineData(300, false)]
    [InlineData(301, false)]
    [InlineData(-1, false)]
    public void HeartbeatUsesTheSameDatabaseClockAndAConfirmedFiveMinuteWindow(int ageSeconds, bool valid)
    {
        var databaseNow = new DateTimeOffset(2000, 1, 1, 0, 0, 0, TimeSpan.FromHours(6));
        var row = new StandardNReceiptSessionRow(1, "cashier", 77, "CASH-1", "zkassa") {
            HeartbeatAt = databaseNow.AddSeconds(-ageSeconds), DatabaseNow = databaseNow,
        };
        Assert.Equal(valid ? row : null, StandardNReceiptProjection.ExactLocalSession("CASH-1", [row], true));
    }

    [Fact]
    public void UnsupportedOrMissingHeartbeatSchemaRetainsExactOnlyLegacySelection()
    {
        var row = new StandardNReceiptSessionRow(1, "cashier", 77, "CASH-1", "zkassa");
        Assert.Equal(row, StandardNReceiptProjection.ExactLocalSession("CASH-1", [row], false));
        Assert.Null(StandardNReceiptProjection.ExactLocalSession("CASH-1", [row], true));
        Assert.Null(StandardNReceiptProjection.ExactLocalSession("CASH-1", [row with {
            HeartbeatAt = DateTimeOffset.UtcNow }], true));
        Assert.Null(StandardNReceiptProjection.ExactLocalSession("CASH-1", [row, row with { SessionId = 2 }], false));
        var legacy = StandardNReceiptProjection.CurrentSessionSql(false);
        Assert.DoesNotContain("HEARTBEATS", legacy); Assert.DoesNotContain("DB_NOW", legacy);
        var current = StandardNReceiptProjection.CurrentSessionSql(true);
        Assert.Contains("first 101", current); Assert.Contains("s.ENDDT is null", current);
        Assert.Contains("s.HEARTBEATS>DATEADD(-5 MINUTE TO cast(CURRENT_TIMESTAMP as timestamp))", current);
        Assert.Contains("s.HEARTBEATS<=cast(CURRENT_TIMESTAMP as timestamp)", current);
        Assert.DoesNotContain("desc", current); Assert.DoesNotContain("STARTDT", current);
    }

    [Fact]
    public void SessionReaderProjectsNativeHeartbeatAndDatabaseNowWithoutAssumingHostTime()
    {
        var now = new DateTime(2026, 11, 11, 17, 0, 53, DateTimeKind.Unspecified);
        using var table = new DataTable();
        foreach (var column in new[] { "SESSION_ID", "WS_ID" }) table.Columns.Add(column, typeof(long));
        foreach (var column in new[] { "USER_ID", "COMPNAME", "PROG" }) table.Columns.Add(column, typeof(string));
        foreach (var column in new[] { "HEARTBEATS", "DB_NOW" }) table.Columns.Add(column, typeof(DateTime));
        table.Rows.Add(1719L, 78L, "-1", "CASH-1", "zkassa", now.AddSeconds(-53), now);
        using var reader = table.CreateDataReader(); Assert.True(reader.Read());
        var row = StandardNReceiptProjection.Session(reader);
        Assert.Equal(TimeSpan.FromSeconds(53), row.DatabaseNow - row.HeartbeatAt);
        Assert.Equal(row, StandardNReceiptProjection.ExactLocalSession("CASH-1", [row], true));
    }

    [Fact]
    public void RequiredColumnsCannotBeSilentlyDefaultedWhenSchemaIsUnrecognized()
    {
        using var table = new DataTable();
        table.Columns.Add("ID", typeof(long));
        table.Rows.Add(100L);
        using var reader = table.CreateDataReader();
        Assert.True(reader.Read());
        Assert.Throws<InvalidDataException>(() => StandardNReceiptProjection.Header(reader));
        Assert.Throws<InvalidDataException>(() => StandardNReceiptProjection.Line(reader));
    }

    private static StandardNReceiptSnapshot KnownGroup()
    {
        var root = Header(100, -100m);
        var child = Header(101, -200m);
        return StandardNReceiptProjection.Current(1, "CASH-1", new("cashier", null, 1), [root, child], [root, child],
            [Line(501, 100, 10, 100m, -1m, -100m), Line(502, 101, 20, 200m, -1m, -200m)]) with { WorkstationId = "77" };
    }

    private static StandardNReceiptHeader Header(long id, decimal amount, long? link = 100, bool hasLinkColumn = true)
    {
        using var table = new DataTable();
        foreach (var column in new[] { "ID", "AUDIT_ID", "DOC_TYPE", "STATUS" }) table.Columns.Add(column, typeof(long));
        if (hasLinkColumn) table.Columns.Add("LINK_ID", typeof(long));
        table.Columns.Add("SUMMA", typeof(decimal));
        var row = table.NewRow();
        row["ID"] = id; row["AUDIT_ID"] = 1L; row["DOC_TYPE"] = 3L; row["STATUS"] = 0L; row["SUMMA"] = amount;
        if (hasLinkColumn) row["LINK_ID"] = (object?)link ?? DBNull.Value;
        table.Rows.Add(row);
        using var reader = table.CreateDataReader();
        Assert.True(reader.Read());
        return StandardNReceiptProjection.Header(reader);
    }

    private static StandardNReceiptLine Line(long id, long document, int part, decimal price, decimal quantity, decimal amount)
    {
        using var table = new DataTable();
        foreach (var column in new[] { "ID", "DOC_ID", "PART_ID" }) table.Columns.Add(column, typeof(long));
        foreach (var column in new[] { "PRICE", "QUANT", "SUMMA" }) table.Columns.Add(column, typeof(decimal));
        table.Rows.Add(id, document, (long)part, price, quantity, amount);
        using var reader = table.CreateDataReader();
        Assert.True(reader.Read());
        return StandardNReceiptProjection.Line(reader);
    }
}
