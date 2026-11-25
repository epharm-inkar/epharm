using System.Linq;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

/// <summary>
/// A second cash desk reads the Standard-N database on the pharmacy's main computer. The encrypted options.ini
/// password never opens Firebird, so the posm.json login must also be tried on that computer, not only on
/// localhost (92 tills without the database on 25.11.2026, all of them second cash desks).
/// </summary>
public sealed class StandardNDbCandidatesTests
{
    private const string Encrypted = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

    [Fact]
    public void Second_cash_desk_tries_the_main_computer_with_the_posm_login_before_localhost()
    {
        var list = StandardNDbCandidates.Build(true, "MANAGER", null, "SYSDBA", Encrypted,
            "localhost", 3050, "SYSDBA", "config-login", "KASSA2");

        Assert.Equal(
            new[] { StandardNDbCandidates.OptionsIni, StandardNDbCandidates.OptionsServerWithConfigLogin, StandardNDbCandidates.ConfigFallback },
            list.Select(c => c.Source).ToArray());
        Assert.Equal(new[] { "MANAGER", "MANAGER", "localhost" }, list.Select(c => c.Host).ToArray());
        Assert.Equal(Encrypted, list[0].Password);
        Assert.Equal("config-login", list[1].Password);
        Assert.All(list, c => Assert.Equal(3050, c.Port));
    }

    [Theory]
    [InlineData("KASSA1", "KASSA1")]
    [InlineData("kassa1", "KASSA1")]
    [InlineData("localhost", "KASSA1")]
    [InlineData("127.0.0.1", "KASSA1")]
    [InlineData(null, "KASSA1")]
    [InlineData("", "KASSA1")]
    public void Database_on_this_computer_keeps_the_old_order(string? server, string machine)
    {
        var list = StandardNDbCandidates.Build(true, server, null, "SYSDBA", Encrypted,
            "localhost", 3050, "SYSDBA", "config-login", machine);

        Assert.Equal(
            new[] { StandardNDbCandidates.OptionsIni, StandardNDbCandidates.ConfigFallback },
            list.Select(c => c.Source).ToArray());
        Assert.Equal("localhost", list[^1].Host);
    }

    [Fact]
    public void Port_from_options_ini_is_kept_for_the_main_computer()
    {
        var list = StandardNDbCandidates.Build(true, "MANAGER", 3051, null, Encrypted,
            "localhost", 3050, "SYSDBA", "config-login", "KASSA2");

        Assert.Equal(new[] { 3051, 3051, 3050 }, list.Select(c => c.Port).ToArray());
        Assert.Equal("SYSDBA", list[0].User);
    }

    [Fact]
    public void Without_options_ini_only_the_posm_login_is_tried()
    {
        var list = StandardNDbCandidates.Build(false, "MANAGER", null, "SYSDBA", Encrypted,
            "localhost", 3050, "SYSDBA", "config-login", "KASSA2");

        var only = Assert.Single(list);
        Assert.Equal(StandardNDbCandidates.ConfigFallback, only.Source);
        Assert.Equal("localhost", only.Host);
    }

    [Fact]
    public void Same_login_twice_is_tried_once()
    {
        var list = StandardNDbCandidates.Build(true, "MANAGER", null, "sysdba", "config-login",
            "manager", 3050, "SYSDBA", "config-login", "KASSA2");

        Assert.Single(list);
        Assert.Equal("MANAGER", list[0].Host);
    }

    [Fact]
    public void Passwords_that_differ_only_in_case_are_both_tried()
    {
        var list = StandardNDbCandidates.Build(true, "MANAGER", null, "SYSDBA", "Config-Login",
            "localhost", 3050, "SYSDBA", "config-login", "KASSA2");

        Assert.Equal(3, list.Count);
    }

    [Fact]
    public void A_candidate_never_prints_its_password()
    {
        var list = StandardNDbCandidates.Build(true, "MANAGER", null, "SYSDBA", Encrypted,
            "localhost", 3050, "SYSDBA", "config-login", "KASSA2");

        Assert.All(list, c => Assert.DoesNotContain(c.Password, c.ToString()));
        Assert.Contains("MANAGER:3050", list[1].ToString());
    }
}
