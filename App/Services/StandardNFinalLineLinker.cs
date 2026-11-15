using System;
using System.Collections.Generic;
using System.Linq;

namespace CustomerDisplay.Services;

/// <summary>
/// PR_DOC_DETAIL_INSERT creates a NEW posted ID and copies the original monetary fields and
/// HUMAN_QUANT. ONCOMMIT has no universal finance-safe ID map. Preserve both real IDs and link
/// only a unique one-to-one signature inside the exact same physical document; never use names.
/// </summary>
public static class StandardNFinalLineLinker
{
    public const string Source = "standardn-unique-final-line";

    public static IReadOnlyList<StandardNReceiptLine> Link(long document,
        IReadOnlyList<StandardNReceiptLine> posted, IReadOnlyList<StandardNReceiptLine> observed)
    {
        if (posted.Count > StandardNReceiptProjection.MaxLines || observed.Count > StandardNReceiptProjection.MaxLines)
            return posted.Select(Clear).ToArray();
        return posted.Select(line =>
        {
            var candidates = observed.Where(before => Same(document, line, before)).ToArray();
            if (candidates.Length != 1 || posted.Count(other => Same(document, other, candidates[0])) != 1)
                return Clear(line);
            var observedId = candidates[0].ObservedSourceLineId is > 0 && candidates[0].LineLinkSource == Source
                ? candidates[0].ObservedSourceLineId.GetValueOrDefault() : candidates[0].LineId;
            if (observed.Count(before => (before.ObservedSourceLineId is > 0 && before.LineLinkSource == Source
                    ? before.ObservedSourceLineId.GetValueOrDefault() : before.LineId) == observedId) != 1) return Clear(line);
            return line with { ObservedSourceLineId = observedId, LineLinkSource = Source };
        }).ToArray();
    }

    private static StandardNReceiptLine Clear(StandardNReceiptLine line) =>
        line with { ObservedSourceLineId = null, LineLinkSource = null };

    private static bool Same(long document, StandardNReceiptLine after, StandardNReceiptLine before) =>
        document > 0 && after.SourceDocumentId == document && before.SourceDocumentId == document &&
        after.LineId > 0 && before.LineId > 0 && after.PartId > 0 && after.PartId == before.PartId &&
        after.Qty > 0 && after.Qty == before.Qty && after.Price == before.Price &&
        after.SourceTotal.HasValue && after.SourceTotal == before.SourceTotal &&
        after.DiscountPercent == before.DiscountPercent && !string.IsNullOrWhiteSpace(after.HumanQuantity) &&
        string.Equals(after.HumanQuantity.Trim(), before.HumanQuantity?.Trim(), StringComparison.Ordinal);
}
