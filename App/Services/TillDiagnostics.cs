using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;

namespace CustomerDisplay.Services
{
    /// <summary>One file of the diagnostics archive: text written as is, or the tail of a file on disk.</summary>
    public sealed record TillDiagnosticsEntry(string Name, string? Text = null, string? SourcePath = null, long MaxBytes = 512 * 1024);

    /// <summary>
    /// «Собрать диагностику» from the admin (no AnyDesk, 1.0.96): a zip of the till's own journals and state, at most
    /// 5 MB. IINs, phone numbers and anything that looks like a key, token or password are masked before they leave
    /// the till; whole files are never sent beyond their tail.
    /// </summary>
    public static class TillDiagnostics
    {
        public const long MaxZipBytes = 5L * 1024 * 1024 - 64 * 1024;

        private static readonly Regex Iin = new(@"(?<!\d)(\d{6})\d{5}(\d)(?!\d)", RegexOptions.Compiled);
        private static readonly Regex Phone = new(@"(?<!\d)(?:\+7|8|7)[\s\-]?\(?7\d{2}\)?[\s\-]?\d{3}[\s\-]?\d{2}[\s\-]?(\d{2})(?!\d)", RegexOptions.Compiled);
        private static readonly Regex Secret = new(
            @"(?i)(""?[a-z_\-]*(?:key|token|password|passwd|pwd|secret)[a-z_\-]*""?\s*[:=]\s*)(""[^""]*""|[^\s,;]+)",
            RegexOptions.Compiled);
        private static readonly Regex Bearer = new(@"(?i)(bearer\s+)[a-z0-9\-\._~\+/]+=*", RegexOptions.Compiled);

        /// <summary>Masks personal data and secrets in one piece of text.</summary>
        public static string Mask(string text)
        {
            if (string.IsNullOrEmpty(text)) return text ?? "";
            var masked = Secret.Replace(text, m => m.Groups[1].Value + "[скрыто]");
            masked = Bearer.Replace(masked, m => m.Groups[1].Value + "[скрыто]");
            masked = Phone.Replace(masked, m => "+7 ••• ••• •• " + m.Groups[1].Value);
            masked = Iin.Replace(masked, m => m.Groups[1].Value + "•••••" + m.Groups[2].Value);
            return masked;
        }

        /// <summary>Builds the zip; entries that do not fit the size limit are replaced by a note, never cut silently.</summary>
        public static byte[] Build(IEnumerable<TillDiagnosticsEntry> entries)
        {
            var list = entries.ToList();
            var notes = new List<string>();
            for (var attempt = 0; attempt < 6; attempt++)
            {
                using var buffer = new MemoryStream();
                using (var zip = new ZipArchive(buffer, ZipArchiveMode.Create, leaveOpen: true))
                {
                    foreach (var entry in list)
                    {
                        var content = Read(entry, notes);
                        var file = zip.CreateEntry(SafeName(entry.Name), CompressionLevel.Optimal);
                        using var writer = new StreamWriter(file.Open(), new UTF8Encoding(false));
                        writer.Write(Mask(content));
                    }
                    if (notes.Count > 0)
                    {
                        var file = zip.CreateEntry("README.txt", CompressionLevel.Optimal);
                        using var writer = new StreamWriter(file.Open(), new UTF8Encoding(false));
                        writer.Write(string.Join("\r\n", notes.Distinct()));
                    }
                }
                if (buffer.Length <= MaxZipBytes) return buffer.ToArray();
                // Too big: halve the tails of the file entries and try again.
                list = list.Select(e => e.SourcePath == null ? e : e with { MaxBytes = Math.Max(16 * 1024, e.MaxBytes / 2) }).ToList();
                notes.Add("Архив был больше 5 МБ: хвосты журналов укорочены.");
            }
            throw new InvalidOperationException("Диагностика не помещается в 5 МБ.");
        }

        private static string Read(TillDiagnosticsEntry entry, List<string> notes)
        {
            if (entry.SourcePath == null) return entry.Text ?? "";
            try
            {
                if (!File.Exists(entry.SourcePath)) return "(файла нет: " + entry.SourcePath + ")";
                using var stream = new FileStream(entry.SourcePath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                var length = stream.Length;
                var take = Math.Min(length, Math.Max(1, entry.MaxBytes));
                stream.Seek(length - take, SeekOrigin.Begin);
                var bytes = new byte[take];
                var read = 0;
                while (read < take)
                {
                    var n = stream.Read(bytes, read, (int)(take - read));
                    if (n <= 0) break;
                    read += n;
                }
                var text = Encoding.UTF8.GetString(bytes, 0, read);
                if (take < length)
                {
                    var firstLine = text.IndexOf('\n');
                    if (firstLine >= 0) text = text[(firstLine + 1)..];
                    notes.Add($"{entry.Name}: последние {take / 1024} КБ из {length / 1024} КБ.");
                }
                return text;
            }
            catch (Exception error)
            {
                return "(не удалось прочитать " + entry.SourcePath + ": " + error.Message + ")";
            }
        }

        private static string SafeName(string name)
        {
            var clean = new string(name.Select(c => char.IsLetterOrDigit(c) || c is '-' or '_' or '.' ? c : '_').ToArray());
            return clean.Length == 0 ? "entry.txt" : clean;
        }

        /// <summary>A listing of a folder (names, sizes, times), never the file contents.</summary>
        public static string Listing(string? folder, int max = 400)
        {
            if (string.IsNullOrWhiteSpace(folder)) return "(папка не задана)";
            try
            {
                if (!Directory.Exists(folder)) return "(папки нет: " + folder + ")";
                var files = new DirectoryInfo(folder).EnumerateFiles("*", SearchOption.AllDirectories)
                    .OrderByDescending(f => f.LastWriteTimeUtc).Take(max).ToList();
                var sb = new StringBuilder();
                sb.AppendLine(folder);
                foreach (var f in files)
                    sb.AppendLine($"{f.LastWriteTime:yyyy-MM-dd HH:mm:ss}  {f.Length,10}  {Path.GetRelativePath(folder, f.FullName)}");
                return sb.ToString();
            }
            catch (Exception error)
            {
                return "(не удалось прочитать папку " + folder + ": " + error.Message + ")";
            }
        }
    }
}
