using System;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text;
using CustomerDisplay.Services;
using Xunit;

/// <summary>Diagnostics from the admin: masked personal data and secrets, a 5 MB ceiling, tails of long journals.</summary>
public sealed class TillDiagnosticsTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "epharm-diag-" + Guid.NewGuid().ToString("N"));

    public TillDiagnosticsTests() => Directory.CreateDirectory(_dir);

    [Fact]
    public void Masks_iin_phone_and_secrets()
    {
        var text = "ИИН 920214500012, тел +7 701 123 45 67 и 87011234567; \"DeviceKey\": \"abc.def\", X-Posm-Key: secret-1, Authorization: Bearer eyJhbGciOi.x.y";
        var masked = TillDiagnostics.Mask(text);
        Assert.DoesNotContain("920214500012", masked);
        Assert.Contains("920214•••••2", masked);
        Assert.DoesNotContain("123 45 67", masked);
        Assert.DoesNotContain("87011234567", masked);
        Assert.DoesNotContain("abc.def", masked);
        Assert.DoesNotContain("secret-1", masked);
        Assert.DoesNotContain("eyJhbGciOi", masked);
        Assert.Contains("[скрыто]", masked);
    }

    [Fact]
    public void Long_journal_keeps_the_tail_and_says_so()
    {
        var log = Path.Combine(_dir, "big.log");
        var sb = new StringBuilder();
        for (var i = 0; i < 40_000; i++) sb.AppendLine($"{i:D6} строка журнала кассы");
        File.WriteAllText(log, sb.ToString(), Encoding.UTF8);

        var zip = TillDiagnostics.Build(new[] { new TillDiagnosticsEntry("log.txt", SourcePath: log, MaxBytes: 64 * 1024) });

        using var archive = new ZipArchive(new MemoryStream(zip));
        var content = new StreamReader(archive.GetEntry("log.txt")!.Open()).ReadToEnd();
        Assert.Contains("039999", content);
        Assert.DoesNotContain("000001 ", content);
        var readme = new StreamReader(archive.GetEntry("README.txt")!.Open()).ReadToEnd();
        Assert.Contains("log.txt", readme);
    }

    [Fact]
    public void Archive_stays_under_five_megabytes()
    {
        var log = Path.Combine(_dir, "noise.log");
        var random = new Random(7);
        using (var writer = new StreamWriter(log))
            for (var i = 0; i < 300_000; i++) writer.WriteLine(Convert.ToBase64String(BitConverter.GetBytes(random.NextDouble())) + random.Next());

        var zip = TillDiagnostics.Build(new[] { new TillDiagnosticsEntry("noise.txt", SourcePath: log, MaxBytes: 40L * 1024 * 1024) });

        Assert.True(zip.Length <= TillDiagnostics.MaxZipBytes);
    }

    [Fact]
    public void Missing_files_and_folders_are_described_not_thrown()
    {
        var zip = TillDiagnostics.Build(new[]
        {
            new TillDiagnosticsEntry("absent.txt", SourcePath: Path.Combine(_dir, "nope.log")),
            new TillDiagnosticsEntry("listing.txt", Text: TillDiagnostics.Listing(Path.Combine(_dir, "no-folder"))),
        });
        using var archive = new ZipArchive(new MemoryStream(zip));
        Assert.Equal(new[] { "absent.txt", "listing.txt" }, archive.Entries.Select(e => e.Name).OrderBy(n => n).ToArray());
    }

    public void Dispose()
    {
        try { Directory.Delete(_dir, recursive: true); } catch { }
    }
}
