using System;
using System.Collections.Generic;

namespace CustomerDisplay.Models.Posm
{
    /// <summary>
    /// Asks the server about the person signed in to Standard-N at this till (no ePharm login). The device key in the
    /// headers proves the till; <see cref="Link"/> = false skips issuing a one-time cabinet link (background checks).
    /// </summary>
    public sealed record PosmSelfRequestDto(
        string PharmacyId,
        string StandardNUserId,
        string? Phone = null,
        string? Password = null,
        bool Link = true);

    public sealed record PosmSelfRewardDto(string Kind, string Label, long BonusKzt, DateTimeOffset At);

    public sealed record PosmSelfDto(
        string FullName,
        string IinMasked,
        string Pharmacy,
        long EarnedMonthKzt,
        long BalanceKzt,
        List<PosmSelfRewardDto> Recent,
        bool PhoneBound,
        bool PasswordSet,
        string? CabinetUrl,
        DateTimeOffset? CabinetUrlExpiresAt);

    public sealed record PosmCabinetLinkDto(string Url, DateTimeOffset ExpiresAt);

    /// <summary>
    /// One task from HQ for the person (POST /api/posm/self/tasks): today a training assignment. Status: new,
    /// in_progress, done, overdue; done comes by itself when the course is completed.
    /// </summary>
    public sealed record PosmSelfTaskDto(
        string Id,
        string Kind,
        string Title,
        string? Instructions,
        long? RewardKzt,
        bool Required,
        string Status,
        int ProgressPct,
        DateTimeOffset? DueAt,
        DateTimeOffset? CompletedAt);

    /// <summary>
    /// RequiredOpenCount (servers from the 1.0.99 release): required courses not finished yet; null from an older server.
    /// </summary>
    public sealed record PosmSelfTasksDto(List<PosmSelfTaskDto>? Tasks, int NewCount, int OpenCount, int? RequiredOpenCount = null);

    /// <summary>
    /// A notice from HQ for the person at this till (POST /api/posm/self/messages, 1.0.99). Pending = shown until the
    /// person closes it with ×; ClosedAt = when they did (on any till).
    /// </summary>
    public sealed record PosmSelfMessageDto(string Id, string Title, string Text, DateTimeOffset SentAt, DateTimeOffset? ClosedAt, bool? Pending);

    public sealed record PosmSelfMessagesDto(List<PosmSelfMessageDto>? Messages, int PendingCount);

    /// <summary>
    /// Either the value, or a message for the pharmacist (server text or a network problem). <see cref="Code"/> is the
    /// server's machine code of the error when it sent one (1.0.101): <c>STANDARDN_USER_UNKNOWN</c> means the Standard-N user
    /// at this till is not a known person yet and the window asks for the IIN and the full name.
    /// </summary>
    public sealed record PosmSelfResult<T>(T? Value, string? Error, string? Code = null) where T : class
    {
        public const string StandardNUserUnknown = "STANDARDN_USER_UNKNOWN";

        public bool Ok => Value != null;

        public bool IdentityUnknown => Value == null && string.Equals(Code, StandardNUserUnknown, StringComparison.Ordinal);
    }

    /// <summary>
    /// A Standard-N user without a card sends the IIN and the full name they typed (POST /api/posm/self/identity/claim), or
    /// only asks where they stand (POST /api/posm/self/identity: Iin and FullName empty). The device key proves the till.
    /// </summary>
    public sealed record PosmSelfIdentityRequestDto(
        string PharmacyId,
        string StandardNUserId,
        string? StandardNUserName = null,
        string? Iin = null,
        string? FullName = null);

    /// <summary>
    /// Where the Standard-N user at this till stands: <c>known</c> (the window works as usual), <c>unknown</c> (show the
    /// form), <c>pending</c> (sent, the office has not decided) or <c>rejected</c> (the office said no, Reason says why).
    /// </summary>
    public sealed record PosmSelfIdentityDto(string State, string? IinMasked = null, string? FullName = null, string? Reason = null)
    {
        public const string Known = "known";
        public const string Unknown = "unknown";
        public const string Pending = "pending";
        public const string Rejected = "rejected";
    }
}
