using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using CustomerDisplay.Models.Posm;
using FirebirdSql.Data.FirebirdClient;

namespace CustomerDisplay.Services;

public sealed partial class StandardNDbLookup : INativeRefundSource
{
    public bool TryGetRefundBaseline(out string sourceNamespace, out long maximumDocumentId, out DateTimeOffset databaseTime)
    {
        sourceNamespace = ""; maximumDocumentId = 0; databaseTime = default;
        if (!_cfg.StandardNDbEnabled) return false;
        try {
            using var connection = OpenConnection();
            using var transaction = connection.BeginTransaction(new FbTransactionOptions {
                TransactionBehavior = FbTransactionBehavior.Read | FbTransactionBehavior.Concurrency | FbTransactionBehavior.Wait });
            using var command = connection.CreateCommand(); command.Transaction = transaction; command.CommandTimeout = TimeoutSec();
            command.CommandText = "select (select coalesce(max(ID),0) from DOCS),CURRENT_TIMESTAMP from RDB$DATABASE";
            using var reader = command.ExecuteReader(); if (!reader.Read()) return false;
            maximumDocumentId = Convert.ToInt64(reader.GetValue(0), CultureInfo.InvariantCulture);
            databaseTime = new DateTimeOffset(Convert.ToDateTime(reader.GetValue(1), CultureInfo.InvariantCulture));
            sourceNamespace = RewardSourceNamespace ?? "";
            return sourceNamespace.Length == 64 && maximumDocumentId >= 0 && databaseTime != default;
        } catch { LogErrorRateLimited("Native refund baseline unavailable; historical documents are not imported."); return false; }
    }

    public bool TryReadNativeRefunds(string sourceNamespace, NativeRefundScan scan, out NativeRefundBatch? batch)
    {
        batch = null;
        var pending = scan.Pending;
        if (!_cfg.StandardNDbEnabled || scan.PageAfter < 0 || scan.Baseline < 0 || scan.ActivatedAt == default ||
            pending.Count > NativeRefundCapture.MaximumPending || scan.Completed.Count > NativeRefundCapture.MaximumCompleted || pending.Any(id => id <= 0)) return false;
        try {
            using var connection = OpenConnection();
            using var transaction = connection.BeginTransaction(new FbTransactionOptions {
                TransactionBehavior = FbTransactionBehavior.Read | FbTransactionBehavior.Concurrency | FbTransactionBehavior.Wait });
            var ns = RewardSourceNamespace ?? "";
            var maximum = RefundMaximum(connection, transaction);
            if (ns != sourceNamespace) { batch = new(ns, maximum, [], []); return true; }
            var machine = StaffSessionResolver.CanonicalComputer(_machineName);
            if (machine.Length == 0) return false;
            var newRows = RefundHeaders(connection, transaction, @"d.ID>@pageAfter and
                (d.ID>@activationId or (d.STATUS=1 and d.POSTDT>=@activationTime)) and upper(w.COMPNAME) containing @machine", command => {
                command.Parameters.AddWithValue("@pageAfter", scan.PageAfter); command.Parameters.AddWithValue("@activationId", scan.Baseline);
                command.Parameters.AddWithValue("@activationTime", scan.ActivatedAt.DateTime); command.Parameters.AddWithValue("@machine", machine);
            }, 65);
            var selected = newRows.Take(64).ToArray();
            var pendingRows = pending.Count == 0 ? [] : RefundHeaders(connection, transaction,
                "d.ID in (" + string.Join(",", pending.Select((_, i) => "@pending" + i)) + ")", command => {
                    for (var i = 0; i < pending.Count; i++) command.Parameters.AddWithValue("@pending" + i, pending[i]);
                }, NativeRefundCapture.MaximumPending + 1);
            if (pendingRows.Count > NativeRefundCapture.MaximumPending) return false;
            var human = HasPostedHumanQuantity(connection, transaction);
            NativeRefundObservation Read(RefundHeader row)
            {
                var local = StaffSessionResolver.CanonicalComputer(row.Machine) == machine &&
                    (row.Program.Contains("zkassa", StringComparison.OrdinalIgnoreCase) || row.Program.Contains("kassir", StringComparison.OrdinalIgnoreCase));
                var identity = new RewardSourceIdentity { SourceNamespace = ns, RawUserId = row.User,
                    NativeSessionId = row.Header.SessionId, WorkstationId = row.Workstation, IdentityStatus = "unknown" };
                if (!local) return new(row.Header.Id, false, null, new());
                if (scan.Completed.Contains(row.Header.Id)) return new(row.Header.Id, true, null, identity);
                var document = StandardNReceiptProjection.Document(row.Header, []);
                if (row.Header.Status == 1) {
                    using var command = connection.CreateCommand(); command.Transaction = transaction; command.CommandTimeout = TimeoutSec();
                    command.CommandText = StandardNReceiptProjection.PostedLinesSql(1, human, 9);
                    command.Parameters.AddWithValue("@sessionId", row.Header.SessionId); command.Parameters.AddWithValue("@doc0", row.Header.Id);
                    var lines = new List<StandardNReceiptLine>();
                    using (var reader = command.ExecuteReader()) while (reader.Read()) lines.Add(StandardNReceiptProjection.Line(reader));
                    if (lines.Count > StandardNReceiptProjection.MaxLines) throw new System.IO.InvalidDataException("Native refund detail bound exceeded.");
                    EnrichReceiptProductIdentity(connection, transaction, lines);
                    StandardNOriginalReceipt? original = null;
                    if (row.Header.ParentDocumentId is > 0) {
                        var parents = ReadReceiptHeaders(connection, transaction, StandardNReceiptProjection.SingleDocumentSql,
                            ("@rootId", row.Header.ParentDocumentId.Value));
                        if (parents.Count == 1 && parents[0] is { Type: 3, Status: 1 } parent && parent.Id == row.Header.ParentDocumentId)
                            original = new(parent.Id, parent.Type, parent.Status, parent.Device, parent.FiscalNumber, parent.FiscalShift);
                    }
                    document = StandardNReceiptProjection.Document(row.Header, lines) with { FinalDetailsRead = true, OriginalReceipt = original };
                }
                return new(row.Header.Id, true, document, identity);
            }
            batch = new(ns, maximum, selected.Select(Read).ToArray(), pendingRows.Select(Read).ToArray(), newRows.Count <= 64);
            return true;
        } catch { LogErrorRateLimited("Native refunds not confirmed; pending observations and cashier data are preserved."); return false; }
    }

    public bool TryObserveNativeCashPrint(out NativeCashPrintObservation? observation)
    {
        observation = null;
        if (!_cfg.StandardNDbEnabled) return false;
        try {
            using var connection = OpenConnection();
            using var transaction = connection.BeginTransaction(new FbTransactionOptions {
                TransactionBehavior = FbTransactionBehavior.Read | FbTransactionBehavior.Concurrency | FbTransactionBehavior.Wait });
            var session = QueryExactReceiptSession(connection, transaction);
            if (session == null) return false;
            using var command = connection.CreateCommand(); command.Transaction = transaction; command.CommandTimeout = TimeoutSec();
            command.CommandText = @"select first 501 ID,DOC_TYPE,STATUS from DOCS where AUDIT_ID=@session
                and DOC_TYPE in (3,9) and (STATUS=0 or (STATUS=1 and POSTDT>=DATEADD(-5 SECOND TO CURRENT_TIMESTAMP))) order by ID";
            command.Parameters.AddWithValue("@session", session.SessionId);
            using var reader = command.ExecuteReader(); var rows = new List<(long Id, int Type, int Status)>();
            while (reader.Read()) {
                if (rows.Count >= 500) return false;
                rows.Add((Convert.ToInt64(reader.GetValue(0), CultureInfo.InvariantCulture),
                    Convert.ToInt32(reader.GetValue(1), CultureInfo.InvariantCulture), Convert.ToInt32(reader.GetValue(2), CultureInfo.InvariantCulture)));
            }
            observation = NativeCashPrintObservation.Classify(rows); return true;
        } catch { return false; }
    }

    private long RefundMaximum(FbConnection connection, FbTransaction transaction)
    {
        using var command = connection.CreateCommand(); command.Transaction = transaction; command.CommandTimeout = TimeoutSec();
        command.CommandText = "select coalesce(max(ID),0) from DOCS";
        return Convert.ToInt64(command.ExecuteScalar(), CultureInfo.InvariantCulture);
    }

    private sealed record RefundHeader(StandardNReceiptHeader Header, string User, string Workstation, string? Machine, string Program);
    private List<RefundHeader> RefundHeaders(FbConnection connection, FbTransaction transaction, string condition, Action<FbCommand> parameters, int limit)
    {
        using var command = connection.CreateCommand(); command.Transaction = transaction; command.CommandTimeout = TimeoutSec();
        // The only variable SQL pieces are bounded internal limits/placeholder lists, not user text.
        command.CommandText = $@"select first {limit} d.*,s.USER_ID as NATIVE_USER_ID,s.WS_ID as NATIVE_WS_ID,
            w.COMPNAME as NATIVE_COMPUTER,s.PROG as NATIVE_PROGRAM
            from DOCS d join SESSIONS s on s.ID=d.AUDIT_ID join WORKSTATIONS w on w.ID=s.WS_ID
            where d.DOC_TYPE=9 and {condition} order by d.ID";
        parameters(command);
        using var reader = command.ExecuteReader(); var result = new List<RefundHeader>();
        while (reader.Read()) result.Add(new(StandardNReceiptProjection.Header(reader), ReadString(reader, "NATIVE_USER_ID") ?? "",
            ReadString(reader, "NATIVE_WS_ID") ?? "", ReadString(reader, "NATIVE_COMPUTER"), ReadString(reader, "NATIVE_PROGRAM") ?? ""));
        return result;
    }
}
