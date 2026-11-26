using System.Diagnostics;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

/// <summary>
/// The OS reading itself, with real processes (Windows only; skipped elsewhere). The exact-name lookup that missed
/// «zkassa_BACKUP_2_6_88» lived in this boundary, so a copy of a harmless system program is started under the till's
/// names and read back by the production probe. The tests of one class never run side by side.
/// </summary>
public sealed class StaffProcessProbeWindowsTests
{
    [Fact]
    public void ARenamedCashierProgramRunningInThisSessionIsFound()
    {
        Assert.SkipUnless(OperatingSystem.IsWindows(), "Windows processes only");
        using var program = RunningCopy.Start("zkassa_BACKUP_2_6_88");

        var presence = MainWindow.ObserveStaffProcessTest();

        Assert.Equal("resolved", presence.Status);
        Assert.StartsWith(program.Id + ":", presence.Identity);
    }

    [Fact]
    public void ACashierProgramInAnyCaseIsFound()
    {
        Assert.SkipUnless(OperatingSystem.IsWindows(), "Windows processes only");
        using var program = RunningCopy.Start("ZKassa");

        var presence = MainWindow.ObserveStaffProcessTest();

        Assert.Equal("resolved", presence.Status);
        Assert.StartsWith(program.Id + ":", presence.Identity);
    }

    [Fact]
    public void AComputerWithOnlyTheManagerProgramIsManagerOnly()
    {
        Assert.SkipUnless(OperatingSystem.IsWindows(), "Windows processes only");
        using var program = RunningCopy.Start("ManagerXP2");

        Assert.Equal(new StaffProcessPresence("manager_only"), MainWindow.ObserveStaffProcessTest());
    }

    [Fact]
    public void WithoutTheTillsProgramsNothingIsFound()
    {
        Assert.SkipUnless(OperatingSystem.IsWindows(), "Windows processes only");
        using var program = RunningCopy.Start("epharm_unrelated_program");

        Assert.Equal(new StaffProcessPresence("no_process"), MainWindow.ObserveStaffProcessTest());
    }

    /// <summary>A copy of ping.exe under another name, running for a minute in a hidden window; removed on dispose.</summary>
    private sealed class RunningCopy : IDisposable
    {
        private readonly string _directory;
        private readonly Process _process;

        private RunningCopy(string directory, Process process)
        {
            _directory = directory;
            _process = process;
        }

        public int Id => _process.Id;

        public static RunningCopy Start(string name)
        {
            var directory = Path.Combine(Path.GetTempPath(), "epharm-staff-probe-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(directory);
            try
            {
                var executable = Path.Combine(directory, name + ".exe");
                File.Copy(Path.Combine(Environment.SystemDirectory, "ping.exe"), executable);
                var process = Process.Start(new ProcessStartInfo(executable, "-n 60 127.0.0.1")
                {
                    UseShellExecute = false,
                    CreateNoWindow = true,
                }) ?? throw new InvalidOperationException("the test program did not start");
                if (process.WaitForExit(200))
                {
                    process.Dispose();
                    throw new InvalidOperationException("the test program exited at once");
                }
                return new RunningCopy(directory, process);
            }
            catch
            {
                TryDelete(directory);
                throw;
            }
        }

        public void Dispose()
        {
            try
            {
                _process.Kill(entireProcessTree: true);
                _process.WaitForExit(5000);
            }
            catch { }
            _process.Dispose();
            TryDelete(_directory);
        }

        private static void TryDelete(string directory)
        {
            try { Directory.Delete(directory, recursive: true); }
            catch { }
        }
    }
}
