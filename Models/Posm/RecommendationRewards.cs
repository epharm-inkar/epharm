using System;
using System.Collections.Generic;

namespace CustomerDisplay.Models.Posm;

// Optional v1 evidence. These are observations, never client authority to award money.
public sealed record RewardSourceIdentity
{
    public string SourceNamespace { get; init; } = "";
    public string RawUserId { get; init; } = "";
    public long NativeSessionId { get; init; }
    public string WorkstationId { get; init; } = "";
    public string IdentityStatus { get; init; } = "unknown";
}

public sealed record RewardCartLine
{
    public long SourceDocumentId { get; init; }
    public long SourceLineId { get; init; }
    public string? Sku { get; init; }
    public string? Barcode { get; init; }
    public decimal Qty { get; init; }
    public string UnitSemantics { get; init; } = "unknown";
}

public sealed record RewardShownEvidence
{
    public int Version { get; init; } = 1;
    public string CheckoutSessionId { get; init; } = "";
    public string ObservationId { get; init; } = "";
    public RewardSourceIdentity Identity { get; init; } = new();
    public bool CartComplete { get; init; }
    public List<RewardCartLine> Lines { get; init; } = new();
}

public sealed record RewardLineChange
{
    public string ObservationId { get; init; } = "";
    public string EventId { get; init; } = "";
    public string GenerationId { get; init; } = "";
    public long Sequence { get; init; }
    public DateTimeOffset ObservedAt { get; init; }
    public RewardSourceIdentity Identity { get; init; } = new();
    public long SourceDocumentId { get; init; }
    public long SourceLineId { get; init; }
    public string? Sku { get; init; }
    public string? Barcode { get; init; }
    public decimal BeforeQty { get; init; }
    public decimal AfterQty { get; init; }
    public string UnitSemantics { get; init; } = "unknown";
}

public sealed record RewardPaidLine
{
    public long SourceDocumentId { get; init; }
    public long SourceLineId { get; init; }
    [System.Text.Json.Serialization.JsonIgnore(Condition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull)]
    public long? ObservedSourceLineId { get; init; }
    [System.Text.Json.Serialization.JsonIgnore(Condition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull)]
    public string? LineLinkSource { get; init; }
    public string? Sku { get; init; }
    public string? Barcode { get; init; }
    public decimal Qty { get; init; }
    public decimal SignedQuantity { get; init; }
    public decimal SignedPrice { get; init; }
    public decimal SignedAmount { get; init; }
    public string UnitSemantics { get; init; } = "unknown";
}

public sealed record RewardCompletionEvidence
{
    public string Status { get; init; } = "unknown";
    public string Source { get; init; } = "";
    public DateTimeOffset CompletedAt { get; init; }
    public RewardSourceIdentity Identity { get; init; } = new();
    public long SourceDocumentId { get; init; }
    public int DocumentStatus { get; init; }
    [System.Text.Json.Serialization.JsonIgnore(Condition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull)]
    public int? DocumentType { get; init; }
    public bool FinalDetailsRead { get; init; }
    public decimal SignedTotal { get; init; }
    public string DeviceNumber { get; init; } = "";
    public long FiscalNumber { get; init; }
    public long FiscalShift { get; init; }
    public List<RewardPaidLine> Lines { get; init; } = new();
    public long? OriginalSourceDocumentId { get; init; }
    public long? OriginalFiscalNumber { get; init; }
    public long? OriginalFiscalShift { get; init; }
    [System.Text.Json.Serialization.JsonIgnore(Condition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull)]
    public string? OriginalDeviceNumber { get; init; }
}

public sealed record RewardSaleEvidence
{
    public int Version { get; init; } = 1;
    public string CheckoutSessionId { get; init; } = "";
    public bool JournalComplete { get; init; }
    public List<RewardLineChange> Changes { get; init; } = new();
    public RewardCompletionEvidence Completion { get; init; } = new();
}
