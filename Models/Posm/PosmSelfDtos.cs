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

    /// <summary>Either the value, or a message for the pharmacist (server text or a network problem).</summary>
    public sealed record PosmSelfResult<T>(T? Value, string? Error) where T : class
    {
        public bool Ok => Value != null;
    }
}
