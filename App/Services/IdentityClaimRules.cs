using System;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;

namespace CustomerDisplay.Services
{
    /// <summary>One typed value checked: the clean value to send, or a short message for the person.</summary>
    public readonly record struct IdentityCheck(bool Ok, string Value, string? Error);

    /// <summary>
    /// What a pharmacist may type about themselves in the personal window (1.0.101): the IIN with its Kazakhstan control
    /// digit and the full name of at least two words. The server checks the same rules again
    /// (<c>IdentityClaimRules.kt</c>) and is the one that decides; this class only gives a quick answer at the till.
    /// Pure and unit-tested, no window and no network.
    /// </summary>
    public static class IdentityClaimRules
    {
        public const int IinLength = 12;
        public const int NameMaxLength = 255;
        private const int WordMaxCount = 6;

        private static readonly int[] Weights1 = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 };
        private static readonly int[] Weights2 = { 3, 4, 5, 6, 7, 8, 9, 10, 11, 1, 2 };

        /// <summary>A word: letters of any script, a hyphen or an apostrophe inside, at least two letters.</summary>
        private static readonly Regex NameWord = new("^\\p{L}[\\p{L}\\p{M}'’ʼ-]*\\p{L}$", RegexOptions.Compiled | RegexOptions.CultureInvariant);
        private static readonly Regex Control = new("[\\p{Cc}\\p{Cf}]", RegexOptions.Compiled | RegexOptions.CultureInvariant);
        private static readonly Regex Spaces = new("\\s+", RegexOptions.Compiled | RegexOptions.CultureInvariant);

        public const string IinDigitsMessage = "Проверьте ИИН: нужно 12 цифр";
        public const string IinControlMessage = "Проверьте ИИН: контрольная цифра не сходится";
        public const string NameWordsMessage = "Укажите фамилию и имя полностью, не меньше двух слов";
        public const string NameLettersMessage = "В имени только буквы, дефис и апостроф, каждое слово не короче двух букв";
        public const string NameLongMessage = "Слишком длинное имя";

        /// <summary>Spaces, hyphens and non-breaking spaces the person may have typed between digit groups are dropped.</summary>
        public static string NormalizeIin(string? raw)
        {
            if (string.IsNullOrEmpty(raw)) return "";
            var builder = new StringBuilder(raw.Length);
            foreach (var ch in raw)
                if (!char.IsWhiteSpace(ch) && ch != '-' && ch != '‐' && ch != '‑') builder.Append(ch);
            return builder.ToString();
        }

        /// <summary>Twelve ASCII digits and the control digit (weights 1..11, then 3..11,1,2 when the first sum is 10).</summary>
        public static bool IsValidIin(string? iin)
        {
            if (iin == null || iin.Length != IinLength) return false;
            foreach (var ch in iin)
                if (ch < '0' || ch > '9') return false;
            if (iin == "000000000000") return false;
            var control = Checksum(iin, Weights1);
            if (control == 10) control = Checksum(iin, Weights2);
            if (control == 10) return false;
            return control == iin[11] - '0';
        }

        public static IdentityCheck CheckIin(string? raw)
        {
            var iin = NormalizeIin(raw);
            if (iin.Length != IinLength || iin.Any(ch => ch < '0' || ch > '9')) return new(false, iin, IinDigitsMessage);
            return IsValidIin(iin) ? new(true, iin, null) : new(false, iin, IinControlMessage);
        }

        /// <summary>Trimmed, one space between words, composed form; control characters become spaces.</summary>
        public static string NormalizeName(string? raw)
        {
            if (string.IsNullOrEmpty(raw)) return "";
            var composed = raw.Normalize(NormalizationForm.FormC);
            return Spaces.Replace(Control.Replace(composed, " "), " ").Trim();
        }

        public static int WordCount(string? name) =>
            NormalizeName(name).Split(' ', StringSplitOptions.RemoveEmptyEntries).Length;

        public static IdentityCheck CheckFullName(string? raw)
        {
            var name = NormalizeName(raw);
            if (name.Length > NameMaxLength) return new(false, name, NameLongMessage);
            var words = name.Split(' ', StringSplitOptions.RemoveEmptyEntries);
            if (words.Length < 2 || words.Length > WordMaxCount) return new(false, name, NameWordsMessage);
            if (words.Any(word => !NameWord.IsMatch(word))) return new(false, name, NameLettersMessage);
            return new(true, name, null);
        }

        private static int Checksum(string iin, int[] weights)
        {
            var sum = 0;
            for (var i = 0; i < 11; i++) sum += (iin[i] - '0') * weights[i];
            return sum % 11;
        }
    }
}
