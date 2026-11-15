using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text.RegularExpressions;
using CustomerDisplay.Models;

namespace CustomerDisplay.Services;

/// <summary>
/// Parses the detailed Add2Cheque fallback emitted by some Standard-N builds. Field names are
/// stable, but real installations vary whitespace, separator (: or =) and field order.
/// </summary>
internal static class StandardNLogParser
{
    private const RegexOptions Options =
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant | RegexOptions.Compiled;

    private static readonly Regex PartIdPattern = new(
        @"(?<![\p{L}\p{N}_])iPartID\s*[:=]\s*[""']?(?<value>\d+)",
        Options);

    private static readonly Regex NamePattern = FieldPattern("sname");
    private static readonly Regex PricePattern = FieldPattern("price");
    private static readonly Regex QuantityPattern = FieldPattern("quant");

    private static readonly Regex ExplicitBarcodePattern = new(
        @"(?<![\p{L}\p{N}_])(?:orig_bcode_izg|bcode_izg|barcode1?|bar_?code|ean(?:13)?|bcode|shtrih|штрих(?:код)?)\s*[:=]\s*[""']?(?<value>\d{8,14})",
        Options);

    private static readonly Regex IpartBarcodePattern = new(
        @"iPartID\s*[:=]\s*[""']?\d+[""']?\s*\(\s*(?<value>\d{8,14})\s*\)",
        Options);

    public static ReceiptItem? TryParseAdd2Cheque(string? line)
    {
        if (string.IsNullOrWhiteSpace(line) ||
            !line.Contains("Add2Cheque", StringComparison.OrdinalIgnoreCase) ||
            line.Contains("(delete)", StringComparison.OrdinalIgnoreCase))
            return null;

        var partId = TryExtractPartId(line);
        var quantityText = ExtractField(QuantityPattern, line);
        if (!partId.HasValue || partId.Value <= 0 ||
            string.IsNullOrWhiteSpace(quantityText) ||
            !TryParseDecimal(quantityText, out var quantity) ||
            quantity <= 0)
            return null;

        var price = 0m;
        var priceText = ExtractField(PricePattern, line);
        if (!string.IsNullOrWhiteSpace(priceText))
            _ = TryParseDecimal(priceText, out price);

        return new ReceiptItem
        {
            PartId = partId.Value,
            Barcode = ExtractBarcode(line, partId.Value.ToString(CultureInfo.InvariantCulture)),
            Name = ExtractField(NamePattern, line) ?? "",
            Price = price,
            Qty = quantity,
            DiscountPercent = 0m,
        };
    }

    public static int? TryExtractPartId(string? line)
    {
        if (string.IsNullOrWhiteSpace(line)) return null;
        var match = PartIdPattern.Match(line);
        return match.Success &&
               int.TryParse(match.Groups["value"].Value, NumberStyles.None, CultureInfo.InvariantCulture, out var partId)
            ? partId
            : null;
    }

    private static Regex FieldPattern(string names) => new(
        $@"(?<![\p{{L}}\p{{N}}_])(?:{names})\s*[:=]\s*(?<value>[^;\r\n]*)",
        Options);

    private static string? ExtractField(Regex pattern, string line)
    {
        var match = pattern.Match(line);
        if (!match.Success) return null;
        var value = match.Groups["value"].Value.Trim();
        if (value.Length >= 2 &&
            ((value[0] == '"' && value[^1] == '"') ||
             (value[0] == '\'' && value[^1] == '\'')))
            value = value[1..^1].Trim();
        return value;
    }

    private static string? ExtractBarcode(string line, string partId)
    {
        foreach (Match match in ExplicitBarcodePattern.Matches(line))
        {
            var value = match.Groups["value"].Value;
            if (IsBarcode(value)) return value;
        }

        var ipartMatch = IpartBarcodePattern.Match(line);
        var inner = ipartMatch.Success ? ipartMatch.Groups["value"].Value : null;
        return IsBarcode(inner) && !string.Equals(inner, partId, StringComparison.Ordinal)
            ? inner
            : null;
    }

    private static bool TryParseDecimal(string raw, out decimal value)
    {
        var normalized = raw.Trim().Trim('"', '\'').Replace(" ", "").Replace(',', '.');
        return decimal.TryParse(
            normalized,
            NumberStyles.AllowLeadingSign | NumberStyles.AllowDecimalPoint,
            CultureInfo.InvariantCulture,
            out value);
    }

    private static bool IsBarcode(string? value)
    {
        if (string.IsNullOrWhiteSpace(value) || value.Length is not (8 or 12 or 13 or 14)) return false;
        foreach (var ch in value)
            if (ch is < '0' or > '9') return false;
        return true;
    }
}

internal sealed class StandardNPrintLogBatch
{
    internal const int MaximumLines = 4096;
    private readonly HashSet<long> _documents = new();
    private static readonly Regex Script = new(@"^\s*(?:\d+\s+(?:\.\s*)+)?RunScriptByIndex\s*:\s*(?<name>Перед печатью очереди чеков|После печати очереди чеков)\s*$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex Document = new(@"^\s*(?:\d+\s+(?:\.\s*)+)?Destroy\s+Cheque\s+ID\s*=\s*(?<id>-?\d+)\s*$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private bool _collecting;
    private int _lines;

    internal IReadOnlySet<long>? Observe(string line)
    {
        if (line.Length > MaximumLines) { Reset(); return null; }
        var script = Script.Match(line);
        if (script.Success && script.Groups["name"].Value.Equals("Перед печатью очереди чеков", StringComparison.OrdinalIgnoreCase))
        { Reset(); _collecting = true; return null; }
        if (script.Success) {
            IReadOnlySet<long>? result = _collecting && _documents.Count > 0 ? new HashSet<long>(_documents) : null;
            Reset(); return result;
        }
        if (!_collecting) return null;
        if (++_lines > MaximumLines ||
            line.Contains("PrintChequeList stop end", StringComparison.OrdinalIgnoreCase) ||
            line.Contains("PrintChequeList results:", StringComparison.OrdinalIgnoreCase) ||
            line.Contains("PrintChequeList error", StringComparison.OrdinalIgnoreCase) ||
            line.Contains("PrintChequeList exception", StringComparison.OrdinalIgnoreCase)) { Reset(); return null; }
        if (line.Contains("Destroy Cheque", StringComparison.OrdinalIgnoreCase)) {
            var match = Document.Match(line);
            if (!match.Success || !long.TryParse(match.Groups["id"].Value, NumberStyles.Integer,
                    CultureInfo.InvariantCulture, out var id)) { Reset(); return null; }
            if (id > 0) _documents.Add(id);
            else if (id != -1) { Reset(); return null; } // Only -1 is the proven temporary cheque object.
            if (_documents.Count > StandardNReceiptProjection.MaxDocuments) Reset();
        }
        return null;
    }

    internal void Reset() { _collecting = false; _lines = 0; _documents.Clear(); }
}
