using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace CustomerDisplay.Services
{
    /// <summary>A notice from HQ as the till keeps it (POST /api/posm/self/messages).</summary>
    public sealed record TillMessage(string Id, string Title, string Text, DateTimeOffset SentAt, DateTimeOffset? ClosedAt, bool Pending);

    public enum TillMessageAckKind { Shown, Closed }

    /// <summary>How a notice's time reads on the till: «сегодня в 14:30», «вчера в 09:05», «25 ноября в 14:30».</summary>
    public static class TillMessageFormat
    {
        private static readonly string[] Months =
        {
            "января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря",
        };

        /// <summary>The time in the till's own zone (the offset of <paramref name="localNow"/>).</summary>
        public static string Sent(DateTimeOffset sentAt, DateTimeOffset localNow)
        {
            var local = sentAt.ToOffset(localNow.Offset);
            var time = local.ToString("HH:mm", CultureInfo.InvariantCulture);
            if (local.Date == localNow.Date) return "сегодня в " + time;
            if (local.Date == localNow.Date.AddDays(-1)) return "вчера в " + time;
            var year = local.Year != localNow.Year ? " " + local.Year.ToString(CultureInfo.InvariantCulture) : "";
            return local.Day.ToString(CultureInfo.InvariantCulture) + " " + Months[local.Month - 1] + year + " в " + time;
        }

        /// <summary>The «Уведомления» page: one section per notice, newest first, open ones marked «Новое».</summary>
        public static IReadOnlyList<EpharmPageSection> HistorySections(IEnumerable<TillMessage> messages, DateTimeOffset localNow) =>
            messages.Select(m => new EpharmPageSection(
                    m.Title,
                    Paragraphs(m.Text),
                    "Отправлено " + Sent(m.SentAt, localNow) + (m.ClosedAt is { } closed ? ", прочитано " + Sent(closed, localNow) : ""),
                    m.Pending && m.ClosedAt == null ? "Новое" : null,
                    Bullets: false))
                .ToList();

        private static IReadOnlyList<string> Paragraphs(string text)
        {
            var lines = text.Replace("\r\n", "\n").Split('\n').Select(l => l.Trim()).Where(l => l.Length > 0).ToList();
            return lines.Count > 0 ? lines : new List<string> { text.Trim() };
        }
    }

    /// <summary>A report the till still owes the server: the notice appeared, or the person closed it.</summary>
    public sealed record TillMessageAck(string User, string MessageId, TillMessageAckKind Kind);

    /// <summary>
    /// Notices from HQ on this till (1.0.99), per Standard-N user. A notice stays on screen until the person closes it
    /// with ×. The close is kept on disk at once, so the notice never comes back after a lost report or a restart, and
    /// the report is sent again until the server takes it. Pure: no window, no HTTP; the host calls it on the UI thread.
    /// </summary>
    public sealed class PharmacistMessageInbox
    {
        public const int MaxEntries = 500;
        public static readonly TimeSpan KeepFor = TimeSpan.FromDays(45);

        private readonly Dictionary<string, List<TillMessage>> _server = new(StringComparer.OrdinalIgnoreCase);
        private readonly Dictionary<(string User, string Id), Entry> _entries = new();

        /// <summary>What the server said for this person; local closes not yet reported are applied on top.</summary>
        public void Apply(string user, IEnumerable<TillMessage> messages, DateTimeOffset now)
        {
            if (string.IsNullOrWhiteSpace(user)) return;
            var list = messages.Where(m => !string.IsNullOrWhiteSpace(m.Id)).GroupBy(m => m.Id).Select(g => g.First()).ToList();
            _server[user.Trim()] = list;
            foreach (var message in list.Where(m => m.ClosedAt != null))
            {
                // The server already has the close (maybe from another till): nothing left to report.
                if (_entries.TryGetValue(Key(user, message.Id), out var entry))
                {
                    entry.ShownSent = entry.ClosedSent = true;
                    entry.ClosedAt ??= message.ClosedAt;
                }
            }
            Prune(now);
        }

        /// <summary>Notices waiting for this person, oldest first: the server's open ones minus those closed here.</summary>
        public IReadOnlyList<TillMessage> Pending(string user) =>
            ServerList(user)
                .Where(m => m.Pending && m.ClosedAt == null && !ClosedHere(user, m.Id))
                .OrderBy(m => m.SentAt).ThenBy(m => m.Id, StringComparer.Ordinal)
                .ToList();

        public TillMessage? NextToShow(string user) => Pending(user).FirstOrDefault();

        /// <summary>
        /// Whether a notice on screen may stay: still open for this person. After HQ withdrew it, its last day passed or
        /// the person closed it on another till, the card leaves the screen without a report (the server needs none).
        /// </summary>
        public bool IsPending(string user, string id) =>
            Pending(user).Any(m => string.Equals(m.Id, id.Trim(), StringComparison.Ordinal));

        /// <summary>Every notice of the person for the history, newest first, with closes made here applied.</summary>
        public IReadOnlyList<TillMessage> History(string user) =>
            ServerList(user)
                .Select(m => _entries.TryGetValue(Key(user, m.Id), out var e) && e.ClosedAt != null && m.ClosedAt == null
                    ? m with { ClosedAt = e.ClosedAt, Pending = false }
                    : m)
                .OrderByDescending(m => m.SentAt).ThenByDescending(m => m.Id, StringComparer.Ordinal)
                .ToList();

        /// <summary>The notice appeared on screen: report it once.</summary>
        public void MarkShown(string user, string id, DateTimeOffset now)
        {
            var entry = EntryFor(user, id, now);
            entry.ShownAt ??= now;
        }

        /// <summary>The person closed the notice: it never shows again here; the report goes until the server takes it.</summary>
        public void MarkClosed(string user, string id, DateTimeOffset now)
        {
            var entry = EntryFor(user, id, now);
            entry.ShownAt ??= now;
            entry.ClosedAt ??= now;
        }

        /// <summary>Reports still owed to the server, for every person seen at this till. A close also records the show.</summary>
        public IReadOnlyList<TillMessageAck> AcksToSend() =>
            _entries.Values
                .Where(e => (e.ClosedAt != null && !e.ClosedSent) || (e.ClosedAt == null && e.ShownAt != null && !e.ShownSent))
                .OrderBy(e => e.Touched)
                .Select(e => new TillMessageAck(e.User, e.Id, e.ClosedAt != null ? TillMessageAckKind.Closed : TillMessageAckKind.Shown))
                .ToList();

        /// <summary>The server took the report, or refused it for good (the notice is gone): stop sending it.</summary>
        public void AckDone(TillMessageAck ack)
        {
            if (!_entries.TryGetValue(Key(ack.User, ack.MessageId), out var entry)) return;
            entry.ShownSent = true;
            if (ack.Kind == TillMessageAckKind.Closed) entry.ClosedSent = true;
        }

        public int PendingCount(string user) => Pending(user).Count;

        /// <summary>Forgets the server list of a person (the cashier changed); local closes and owed reports stay.</summary>
        public void ForgetServerList(string user) => _server.Remove(user.Trim());

        private IReadOnlyList<TillMessage> ServerList(string user) =>
            !string.IsNullOrWhiteSpace(user) && _server.TryGetValue(user.Trim(), out var list) ? list : Array.Empty<TillMessage>();

        private bool ClosedHere(string user, string id) => _entries.TryGetValue(Key(user, id), out var e) && e.ClosedAt != null;

        private Entry EntryFor(string user, string id, DateTimeOffset now)
        {
            var key = Key(user, id);
            if (!_entries.TryGetValue(key, out var entry))
            {
                entry = new Entry { User = key.User, Id = key.Id };
                _entries[key] = entry;
            }
            entry.Touched = now;
            if (_entries.Count > MaxEntries) Prune(now);
            return entry;
        }

        /// <summary>Old entries go; reports still owed are kept as long as they are not too old.</summary>
        private void Prune(DateTimeOffset now)
        {
            foreach (var key in _entries.Where(p => now - p.Value.Touched > KeepFor).Select(p => p.Key).ToList()) _entries.Remove(key);
            if (_entries.Count <= MaxEntries) return;
            var settledFirst = _entries.OrderBy(p => Owed(p.Value) ? 1 : 0).ThenBy(p => p.Value.Touched).Select(p => p.Key).ToList();
            foreach (var key in settledFirst.Take(_entries.Count - MaxEntries)) _entries.Remove(key);
        }

        private static bool Owed(Entry e) => (e.ClosedAt != null && !e.ClosedSent) || (e.ShownAt != null && !e.ShownSent);

        private static (string User, string Id) Key(string user, string id) => (user.Trim(), id.Trim());

        // ── disk ────────────────────────────────────────────────────────────

        private static readonly JsonSerializerOptions Json = new() { DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull };

        public static PharmacistMessageInbox Load(string path)
        {
            var inbox = new PharmacistMessageInbox();
            try
            {
                if (!File.Exists(path)) return inbox;
                var stored = JsonSerializer.Deserialize<Stored>(File.ReadAllText(path), Json);
                foreach (var e in stored?.Entries ?? new List<Entry>())
                {
                    if (string.IsNullOrWhiteSpace(e.User) || string.IsNullOrWhiteSpace(e.Id)) continue;
                    inbox._entries[Key(e.User, e.Id)] = e;
                }
            }
            catch
            {
                // A damaged file: a notice closed before may show once more; nothing else is lost.
            }
            return inbox;
        }

        public void Save(string path, Action<string>? log = null)
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                var temp = path + ".tmp";
                File.WriteAllText(temp, JsonSerializer.Serialize(new Stored { Entries = _entries.Values.OrderBy(e => e.Touched).ToList() }, Json));
                File.Move(temp, path, overwrite: true);
            }
            catch (Exception error)
            {
                log?.Invoke("ePharm: не удалось сохранить уведомления: " + error.Message);
            }
        }

        private sealed class Stored
        {
            public int Version { get; set; } = 1;
            public List<Entry> Entries { get; set; } = new();
        }

        private sealed class Entry
        {
            public string User { get; set; } = "";
            public string Id { get; set; } = "";
            public DateTimeOffset? ShownAt { get; set; }
            public bool ShownSent { get; set; }
            public DateTimeOffset? ClosedAt { get; set; }
            public bool ClosedSent { get; set; }
            public DateTimeOffset Touched { get; set; }
        }
    }
}
