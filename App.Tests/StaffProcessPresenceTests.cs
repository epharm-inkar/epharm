using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

/// <summary>
/// Which running programs are the Standard-N cashier (1.0.100, 26.11.2026). At Teriskey 2/21 the cashier program runs as
/// «zkassa_BACKUP_2_6_88», a renamed copy that Standard-N still records as «zkassa»; on tills where only «ManagerXP2»
/// runs, ePharm was installed on the manager's computer, not on the till.
/// </summary>
public sealed class StaffProcessPresenceTests
{
    private const string One = "42:639296064000000000";
    private const string Two = "43:639296064100000000";

    [Theory]
    [InlineData("zkassa")]
    [InlineData("ZKASSA")]
    [InlineData("Zkassa")]
    [InlineData("zkassa_BACKUP_2_6_88")]
    [InlineData("ZKassa_backup_2_6_88")]
    [InlineData("zkassa2")]
    [InlineData("kassir")]
    [InlineData("Kassir")]
    [InlineData(" kassir ")]
    public void CashierProgramsAreKassirOrNamesStartingWithZkassa(string name) =>
        Assert.True(StaffProcessPresence.IsCashierProcessName(name));

    [Theory]
    [InlineData("ManagerXP2")]
    [InlineData("explorer")]
    [InlineData("CustomerDisplay")]
    [InlineData("old_zkassa")]
    [InlineData("kassir2")]
    [InlineData("kass")]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData(null)]
    public void OtherProgramsAreNotTheCashier(string? name) =>
        Assert.False(StaffProcessPresence.IsCashierProcessName(name));

    [Theory]
    [InlineData("ManagerXP")]
    [InlineData("ManagerXP2")]
    [InlineData("managerxp2")]
    [InlineData("MANAGERXP3")]
    public void TheManagerProgramStartsWithManagerXp(string name) =>
        Assert.True(StaffProcessPresence.IsManagerProcessName(name));

    [Theory]
    [InlineData("Manager")]
    [InlineData("MyManagerXP2")]
    [InlineData("zkassa")]
    [InlineData("")]
    [InlineData(null)]
    public void OtherProgramsAreNotTheManager(string? name) =>
        Assert.False(StaffProcessPresence.IsManagerProcessName(name));

    [Fact]
    public void ARenamedCashierProgramResolvesLikeTheOriginal()
    {
        var expected = new StaffProcessPresence("resolved", One);
        Assert.Equal(expected, StaffProcessPresence.FromProcesses([new("explorer", "1:1"), new("zkassa_BACKUP_2_6_88", One)]));
        Assert.Equal(expected, StaffProcessPresence.FromProcesses([new("zkassa", One)]));
        Assert.Equal(expected, StaffProcessPresence.FromProcesses([new("ZKASSA", One)]));
        Assert.Equal(expected, StaffProcessPresence.FromProcesses([new("Kassir", One)]));
    }

    [Fact]
    public void OnlyTheManagerProgramMeansEPharmStandsOnTheManagersComputer()
    {
        var presence = StaffProcessPresence.FromProcesses([new("explorer", "1:1"), new("ManagerXP2", "7")]);
        Assert.Equal(new StaffProcessPresence("manager_only"), presence);
        Assert.Null(presence.Identity);
    }

    [Fact]
    public void ACashierProgramWinsOverTheManagerProgram()
    {
        Assert.Equal(new StaffProcessPresence("resolved", One),
            StaffProcessPresence.FromProcesses([new("ManagerXP2", "7"), new("zkassa", One)]));
    }

    [Fact]
    public void TwoCashierProgramsAreAmbiguousInAnyOrder()
    {
        var presence = StaffProcessPresence.FromProcesses([new("zkassa", One), new("zkassa_BACKUP_2_6_88", Two)]);
        Assert.Equal("ambiguous_process", presence.Status);
        Assert.StartsWith("set:", presence.Identity);
        Assert.Equal(presence, StaffProcessPresence.FromProcesses([new("zkassa_BACKUP_2_6_88", Two), new("zkassa", One)]));
        // The manager program changes nothing once two cashiers run.
        Assert.Equal(presence, StaffProcessPresence.FromProcesses(
            [new("ManagerXP2", "7"), new("zkassa", One), new("zkassa_BACKUP_2_6_88", Two)]));
    }

    [Fact]
    public void NeitherACashierNorTheManagerIsNoProcess()
    {
        var none = new StaffProcessPresence("no_process");
        Assert.Equal(none, StaffProcessPresence.FromProcesses([]));
        Assert.Equal(none, StaffProcessPresence.FromProcesses([new("explorer", "1:1"), new("old_zkassa", "2:2"), new("Manager", "3")]));
    }
}
