using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

/// <summary>
/// A second cash desk opens the pharmacy's database on the main computer: Standard-N writes the server into
/// options.ini base (MANAGER:C:\...). Dropping it made POSM look for the database locally (database_unavailable).
/// </summary>
public sealed class StandardNConnectTargetTests
{
    [Theory]
    [InlineData(@"MANAGER:C:\Standart-N\base\ztrade.fdb", "MANAGER", null, @"C:\Standart-N\base\ztrade.fdb")]
    [InlineData(@"MANAGER/3050:C:\Standart-N\base\ztrade.fdb", "MANAGER", 3050, @"C:\Standart-N\base\ztrade.fdb")]
    [InlineData(@"192.168.1.10:D:\base\ztrade.fdb", "192.168.1.10", null, @"D:\base\ztrade.fdb")]
    [InlineData(@"localhost:C:\Standart-N\base\ztrade.fdb", "localhost", null, @"C:\Standart-N\base\ztrade.fdb")]
    [InlineData("MANAGER:ztrade", "MANAGER", null, "ztrade")]
    [InlineData("MANAGER/3051:ztrade", "MANAGER", 3051, "ztrade")]
    [InlineData(@"inet://MANAGER:3052/C:\base\ztrade.fdb", "MANAGER", 3052, @"C:\base\ztrade.fdb")]
    [InlineData("\"MANAGER:C:\\Standart-N\\base\\ztrade.fdb\"", "MANAGER", null, @"C:\Standart-N\base\ztrade.fdb")]
    public void Server_inside_the_base_value_is_kept(string value, string host, int? port, string database)
    {
        var target = StandardNConnectTarget.Parse(value);

        Assert.NotNull(target);
        Assert.Equal(host, target!.Host);
        Assert.Equal(port, target.Port);
        Assert.Equal(database, target.Database);
    }

    [Theory]
    [InlineData(@"C:\Standart-N\base\ztrade.fdb")]
    [InlineData(@"D:\data\ZTRADE.FDB")]
    [InlineData("ztrade")]
    [InlineData(@"\\MANAGER\base\ztrade.fdb")]
    public void A_local_path_alias_or_share_has_no_server(string value)
    {
        var target = StandardNConnectTarget.Parse(value);

        Assert.NotNull(target);
        Assert.Null(target!.Host);
        Assert.Null(target.Port);
        Assert.Equal(value, target.Database);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    public void Empty_value_gives_no_target(string? value) => Assert.Null(StandardNConnectTarget.Parse(value));

    [Fact]
    public void A_port_that_is_not_a_number_stays_part_of_the_host()
    {
        var target = StandardNConnectTarget.Parse(@"MANAGER/abc:C:\base\ztrade.fdb");

        Assert.Equal("MANAGER/abc", target!.Host);
        Assert.Null(target.Port);
    }
}
