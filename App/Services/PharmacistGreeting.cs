using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// The greeting at the till (1.0.99, Grigoriy 25.11.2026): «Доброе утро, Имя Отчество! Желаем хорошего дня», by the
    /// time of day, with one or two lines on how ePharm helps. No numbers and no money in it.
    /// </summary>
    public static class PharmacistGreeting
    {
        /// <summary>How long the window stays when nobody touches it.</summary>
        public static readonly TimeSpan Lifetime = TimeSpan.FromSeconds(45);

        /// <summary>The two short lines under the wish: what ePharm does at the till and where courses are.</summary>
        public static readonly IReadOnlyList<string> HelpLines = new[]
        {
            "На кассе ePharm подскажет, какой товар предложить покупателю.",
            "Курсы и задания: значок ePharm у часов и «Мой кабинет».",
        };

        /// <summary>Hello and wish by the local hour: утро 05-12, день 12-18, вечер 18-23, ночью просто «Здравствуйте».</summary>
        public static (string Hello, string Wish) Words(DateTime localNow) => localNow.Hour switch
        {
            >= 5 and < 12 => ("Доброе утро", "Желаем хорошего дня"),
            >= 12 and < 18 => ("Добрый день", "Желаем хорошего дня"),
            >= 18 and < 23 => ("Добрый вечер", "Желаем хорошего вечера"),
            _ => ("Здравствуйте", "Желаем спокойной смены"),
        };

        /// <summary>«Доброе утро, Нұрсая Серікқызы!»; without a known name just «Доброе утро!».</summary>
        public static string Title(DateTime localNow, string? fullName)
        {
            var hello = Words(localNow).Hello;
            var name = FirstNameAndPatronymic(fullName);
            return name.Length > 0 ? hello + ", " + name + "!" : hello + "!";
        }

        /// <summary>
        /// Standard-N keeps «Фамилия Имя Отчество»: the greeting uses everything after the surname (a Kazakh patronymic
        /// may be two words, «Серік қызы»). A single word is used as it is. ALL-CAPS words get a capital letter only.
        /// </summary>
        public static string FirstNameAndPatronymic(string? fullName)
        {
            if (string.IsNullOrWhiteSpace(fullName)) return "";
            var words = fullName.Trim().Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
            var kept = words.Length == 1 ? words : words.Skip(1).Take(3).ToArray();
            var name = string.Join(' ', kept.Select(Capitalize));
            return name.Length <= 48 ? name : name[..48].TrimEnd();
        }

        private static string Capitalize(string word)
        {
            var letters = word.Where(char.IsLetter).ToList();
            if (letters.Count < 2 || letters.Any(char.IsLower)) return word;
            var result = new StringBuilder(word.Length);
            var startOfPart = true;
            foreach (var c in word)
            {
                result.Append(startOfPart ? char.ToUpperInvariant(c) : char.ToLowerInvariant(c));
                startOfPart = c == '-' || c == '\'';
            }
            return result.ToString();
        }
    }

    /// <summary>
    /// Who has been greeted at this till in their current shift. A shift is a stretch in which the person is seen signed
    /// in to Standard-N at the till with no gap of 8 hours or more: a night shift 20:00-08:00 is greeted once, not again
    /// in the morning, and a colleague signing in between does not make the first person greeted again. The last time
    /// each person was seen is kept on disk, so a restart does not greet again; a damaged file only costs one extra
    /// greeting. At most 200 people, a week back.
    /// </summary>
    public sealed class GreetingLog
    {
        /// <summary>This long without the person at the till ends their shift: the next sign-in is greeted again.</summary>
        public static readonly TimeSpan ShiftGap = TimeSpan.FromHours(8);

        private const int MaxPeople = 200;
        private static readonly TimeSpan KeepFor = TimeSpan.FromDays(7);
        private readonly Dictionary<string, Shift> _people = new(StringComparer.OrdinalIgnoreCase);

        /// <summary>The person is signed in at the till now (every status check); 8 hours without them start a new shift.</summary>
        public void Seen(string user, DateTime localNow)
        {
            if (string.IsNullOrWhiteSpace(user)) return;
            var key = user.Trim();
            if (!_people.TryGetValue(key, out var shift) || localNow - shift.LastSeen >= ShiftGap)
            {
                _people[key] = new Shift { User = key, Start = localNow, LastSeen = localNow };
                Prune(localNow);
                return;
            }
            // A clock set back never shortens the shift.
            if (localNow > shift.LastSeen) shift.LastSeen = localNow;
        }

        /// <summary>True when the person's shift (or the one starting now) has not been greeted yet.</summary>
        public bool ShouldGreet(string user, DateTime localNow)
        {
            if (string.IsNullOrWhiteSpace(user)) return false;
            if (!_people.TryGetValue(user.Trim(), out var shift)) return true;
            return localNow - shift.LastSeen >= ShiftGap || !shift.Greeted;
        }

        /// <summary>The greeting went out: not again in this shift.</summary>
        public void Remember(string user, DateTime localNow)
        {
            if (string.IsNullOrWhiteSpace(user)) return;
            Seen(user, localNow);
            if (_people.TryGetValue(user.Trim(), out var shift)) shift.Greeted = true;
        }

        private void Prune(DateTime localNow)
        {
            foreach (var key in _people.Where(p => localNow - p.Value.LastSeen > KeepFor).Select(p => p.Key).ToList()) _people.Remove(key);
            if (_people.Count <= MaxPeople) return;
            foreach (var key in _people.OrderBy(p => p.Value.LastSeen).Take(_people.Count - MaxPeople).Select(p => p.Key).ToList())
                _people.Remove(key);
        }

        /// <summary>
        /// Reads the log; without it, the 1.0.96 file (one person greeted on a calendar day) still counts as greeted when
        /// that day is today.
        /// </summary>
        public static GreetingLog Load(string path, string? legacyPath, DateTime localNow)
        {
            var log = new GreetingLog();
            try
            {
                if (File.Exists(path))
                {
                    var stored = JsonSerializer.Deserialize<List<Shift>>(File.ReadAllText(path)) ?? new List<Shift>();
                    foreach (var shift in stored.Where(s => !string.IsNullOrWhiteSpace(s.User))) log._people[shift.User.Trim()] = shift;
                    log.Prune(localNow);
                    return log;
                }
                if (legacyPath != null && File.Exists(legacyPath))
                {
                    using var doc = JsonDocument.Parse(File.ReadAllText(legacyPath));
                    var user = doc.RootElement.GetProperty("user").GetString() ?? "";
                    var day = DateTime.ParseExact(doc.RootElement.GetProperty("day").GetString() ?? "", "yyyy-MM-dd", CultureInfo.InvariantCulture);
                    if (day == localNow.Date) log.Remember(user, localNow);
                }
            }
            catch
            {
                // Unreadable: start empty.
                return new GreetingLog();
            }
            return log;
        }

        public void Save(string path, Action<string>? log = null)
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                var temp = path + ".tmp";
                File.WriteAllText(temp, JsonSerializer.Serialize(_people.Values.OrderBy(s => s.User, StringComparer.Ordinal).ToList()));
                File.Move(temp, path, overwrite: true);
            }
            catch (Exception error)
            {
                log?.Invoke("ePharm: не удалось сохранить приветствие: " + error.Message);
            }
        }

        /// <summary>One person's shift at this till, as kept on disk.</summary>
        public sealed class Shift
        {
            public string User { get; set; } = "";
            public DateTime Start { get; set; }
            public DateTime LastSeen { get; set; }
            public bool Greeted { get; set; }
        }
    }
}
