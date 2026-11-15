using System;
using System.Security.Cryptography;
using System.Text.Json;

namespace CustomerDisplay.Services;

/// <summary>Exact source absence, never proof of cancellation, payment or eligibility.</summary>
public sealed record StandardNUnpaidGroupAbsenceProof(string SourceNamespace, string GroupFingerprint,
    long DatabaseTransactionId, DateTime DatabaseObservedAt)
{
    public static string Fingerprint(StandardNObservedReceiptGroup group)
    {
        ArgumentNullException.ThrowIfNull(group);
        return Convert.ToHexString(SHA256.HashData(JsonSerializer.SerializeToUtf8Bytes(group, EpharmJson.Options))).ToLowerInvariant();
    }
}
