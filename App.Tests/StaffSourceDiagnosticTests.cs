using CustomerDisplay;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests
{
    // Compiles the production MainWindow.Staff.cs. Only the OS-process boundary is a fake; the Standard-N reader is not
    // started, so a resolved cashier process reads as "database_unavailable".
    public sealed class StaffSourceDiagnosticTests
    {
        private const string Process = "42:639296064000000000";

        [Fact]
        public void TheTillHasNoSignInAndReportsOnlyFixedSourceCodes()
        {
            using var shell = new MainWindow();
            Assert.Equal("local_disabled", shell.StaffAuthenticationStatus);
            Assert.Equal("initializing", shell.StaffSourceStatus);

            shell.ObserveStaffSourceTest(() => new("no_process"));
            Assert.Equal("no_process", shell.StaffSourceStatus);
            shell.ObserveStaffSourceTest(() => new("resolved", Process));
            Assert.Equal("database_unavailable", shell.StaffSourceStatus);
            shell.ObserveStaffSourceTest(() => new("ambiguous_process", "set:synthetic"));
            Assert.Equal("ambiguous_process", shell.StaffSourceStatus);
            // 1.0.100: only the Standard-N manager program runs, ePharm stands on the manager's computer, not on a till.
            shell.ObserveStaffSourceTest(() => new("manager_only"));
            Assert.Equal("manager_only", shell.StaffSourceStatus);
            shell.ObserveStaffSourceTest(() => new("private database detail"));
            Assert.Equal("unknown", shell.StaffSourceStatus);
            shell.ObserveStaffSourceTest(() => throw new InvalidOperationException("synthetic"));
            Assert.Equal("unknown", shell.StaffSourceStatus);
            Assert.Equal("local_disabled", shell.StaffAuthenticationStatus);
        }

        [Fact]
        public void WithoutTheServerConnectionStandardNIsNeverRead()
        {
            using var shell = new MainWindow();
            var reads = 0;
            shell.StartStaffSourceDiagnosticsTest(() => { reads++; return new("resolved", Process); });
            Assert.Equal("local_disabled", shell.StaffSourceStatus);
            Assert.Equal(0, reads);
            shell.StopStaffSourceDiagnosticsTest();
        }

        [Fact]
        public void ProcessPresenceNamesOneCashierOrHashesSeveral()
        {
            Assert.Equal(new StaffProcessPresence("no_process"), StaffProcessPresence.FromInstances([]));
            Assert.Equal(new StaffProcessPresence("resolved", Process), StaffProcessPresence.FromInstances([Process, Process]));
            var several = StaffProcessPresence.FromInstances(["2:2", "1:1"]);
            Assert.Equal("ambiguous_process", several.Status);
            Assert.StartsWith("set:", several.Identity);
            Assert.Equal(several, StaffProcessPresence.FromInstances(["1:1", "2:2"]));
        }
    }
}

namespace CustomerDisplay
{
    public partial class MainWindow
    {
        internal void ObserveStaffSourceTest(Func<StaffProcessPresence> probe)
        {
            _staffProcessProbe = probe;
            ObserveStaffSource();
        }
        internal void StartStaffSourceDiagnosticsTest(Func<StaffProcessPresence> probe)
        {
            _staffProcessProbe = probe;
            StartStaffSourceDiagnostics();
        }
        internal void StopStaffSourceDiagnosticsTest() => StopStaffSourceDiagnostics();
        // The real OS reading, for the Windows process tests.
        internal static StaffProcessPresence ObserveStaffProcessTest() => ObserveStaffProcess();
    }
}
