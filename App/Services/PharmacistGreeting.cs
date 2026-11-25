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
        /// <summary>A greeting day starts at 05:00, so a night shift that crosses midnight is greeted once.</summary>
        public const int DayStartHour = 5;

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

        /// <summary>The greeting day of a moment: the calendar day of (now - 5 h).</summary>
        public static DateTime GreetingDay(DateTime localNow) => localNow.AddHours(-DayStartHour).Date;

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
    /// Who has been greeted at this till on the current greeting day. Kept on disk so a restart does not greet again;
    /// a damaged file only costs one extra greeting. Bounded to 200 people.
    /// </summary>
    public sealed class GreetingLog
    {
        private const int MaxPeople = 200;
        private DateTime _day;
        private readonly HashSet<string> _greeted = new(StringComparer.OrdinalIgnoreCase);

        public GreetingLog() { }

        private GreetingLog(DateTime day, IEnumerable<string> greeted)
        {
            _day = day;
            foreach (var user in greeted.Where(u => !string.IsNullOrWhiteSpace(u)).Take(MaxPeople)) _greeted.Add(user.Trim());
        }

        /// <summary>True when this Standard-N user has not been greeted yet on this greeting day.</summary>
        public bool ShouldGreet(string user, DateTime localNow)
        {
            if (string.IsNullOrWhiteSpace(user)) return false;
            return PharmacistGreeting.GreetingDay(localNow) != _day || !_greeted.Contains(user.Trim());
        }

        public void Remember(string user, DateTime localNow)
        {
            if (string.IsNullOrWhiteSpace(user)) return;
            var day = PharmacistGreeting.GreetingDay(localNow);
            if (day != _day)
            {
                _day = day;
                _greeted.Clear();
            }
            if (_greeted.Count < MaxPeople) _greeted.Add(user.Trim());
        }

        /// <summary>Reads the log; without it, the 1.0.96 file (one person, calendar day) still counts for today.</summary>
        public static GreetingLog Load(string path, string? legacyPath = null)
        {
            try
            {
                if (File.Exists(path))
                {
                    using var doc = JsonDocument.Parse(File.ReadAllText(path));
                    var day = DateTime.ParseExact(doc.RootElement.GetProperty("day").GetString() ?? "", "yyyy-MM-dd", CultureInfo.InvariantCulture);
                    var users = doc.RootElement.GetProperty("users").EnumerateArray().Select(u => u.GetString() ?? "").ToList();
                    return new GreetingLog(day, users);
                }
                if (legacyPath != null && File.Exists(legacyPath))
                {
                    using var doc = JsonDocument.Parse(File.ReadAllText(legacyPath));
                    var user = doc.RootElement.GetProperty("user").GetString() ?? "";
                    var calendarDay = DateTime.ParseExact(doc.RootElement.GetProperty("day").GetString() ?? "", "yyyy-MM-dd", CultureInfo.InvariantCulture);
                    // The old file kept a calendar day; it greeted at the first sign-in of that day, after 05:00 as a rule.
                    return new GreetingLog(calendarDay, new[] { user });
                }
            }
            catch
            {
                // Unreadable: start empty.
            }
            return new GreetingLog();
        }

        public void Save(string path, Action<string>? log = null)
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                var temp = path + ".tmp";
                File.WriteAllText(temp, JsonSerializer.Serialize(new
                {
                    day = _day.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
                    users = _greeted.OrderBy(u => u, StringComparer.Ordinal).ToArray(),
                }));
                File.Move(temp, path, overwrite: true);
            }
            catch (Exception error)
            {
                log?.Invoke("ePharm: не удалось сохранить приветствие: " + error.Message);
            }
        }
    }
}
