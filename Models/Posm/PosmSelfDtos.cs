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

    /// <summary>Either the value, or a message for the pharmacist (server text or a network problem).</summary>
    public sealed record PosmSelfResult<T>(T? Value, string? Error) where T : class
    {
        public bool Ok => Value != null;
    }
}
