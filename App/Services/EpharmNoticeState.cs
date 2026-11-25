using System;
using System.IO;
using System.Text.Json;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// «ePharm на технических работах» on this till. The last state the server gave is kept on disk, so a till that was
    /// switched off while HQ ended the works still announces «ePharm снова работает» once on its next start, and a
    /// till that never saw the works announces nothing.
    /// </summary>
    public sealed class EpharmNoticeState
    {
        /// <summary>null until the server answered once (or the disk remembered an answer).</summary>
        public bool? Maintenance { get; private set; }

        public EpharmNoticeState(bool? maintenance = null) => Maintenance = maintenance;

        /// <summary>Applies a server answer. True when the works just ended for this till: announce it once.</summary>
        public bool Apply(bool maintenance)
        {
            var announce = Maintenance == true && !maintenance;
            Maintenance = maintenance;
            return announce;
        }

        public static EpharmNoticeState Load(string path)
        {
            try
            {
                if (!File.Exists(path)) return new EpharmNoticeState();
                using var doc = JsonDocument.Parse(File.ReadAllText(path));
                if (doc.RootElement.TryGetProperty("maintenance", out var m) &&
                    (m.ValueKind == JsonValueKind.True || m.ValueKind == JsonValueKind.False))
                    return new EpharmNoticeState(m.GetBoolean());
            }
            catch
            {
                // A damaged file only costs one possible «снова работает» message.
            }
            return new EpharmNoticeState();
        }

        public void Save(string path, Action<string>? log = null)
        {
            if (Maintenance == null) return;
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                var temp = path + ".tmp";
                File.WriteAllText(temp, JsonSerializer.Serialize(new { maintenance = Maintenance.Value, savedAt = DateTimeOffset.Now }));
                File.Move(temp, path, overwrite: true);
            }
            catch (Exception error)
            {
                log?.Invoke("ePharm: не удалось сохранить состояние технических работ: " + error.Message);
            }
        }
    }
}
