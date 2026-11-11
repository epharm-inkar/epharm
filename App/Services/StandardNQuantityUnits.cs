using System;
using System.Globalization;
using System.Linq;

namespace CustomerDisplay.Services;

/// <summary>
/// Standard-N's controlled whole-pack UI writes HUMAN_QUANT="1", QUANT=-1; its fractional UI
/// preserves e.g. "3/10" or "10/30". Posting copies HUMAN_QUANT unchanged. KOEF is an inventory
/// participation flag, NOT a pack coefficient. Never change the native quantity or round it.
/// </summary>
public static class StandardNQuantityUnits
{
    public static string Classify(decimal quantity, string? humanQuantity)
    {
        if (quantity is < -1_000_000 or > 1_000_000) return "unknown";
        var text = humanQuantity?.Trim();
        if (string.IsNullOrEmpty(text) || text.Length > 7 || text.Any(ch => ch is < '0' or > '9') ||
            !decimal.TryParse(text, NumberStyles.None, CultureInfo.InvariantCulture, out var whole) ||
            whole <= 0 || whole > 1_000_000 || whole != Math.Abs(quantity)) return "unknown";
        return "full_package";
    }
}
