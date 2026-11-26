using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class StaffAuthDiagnosticsTests
{
    [Theory]
    [InlineData("resolved", "resolved")]
    [InlineData("no_process", "no_process")]
    [InlineData("manager_only", "manager_only")]
    [InlineData("MANAGER_ONLY", "unknown")]
    [InlineData("unsupported_session_time", "unsupported_session_time")]
    [InlineData("session_predates_process", "session_predates_process")]
    [InlineData("authenticated", "unknown")]
    [InlineData("iin=000000000001", "unknown")]
    [InlineData(null, "unknown")]
    public void SourceStatusIsIndependentAndOnlyFixedCodesCanLeaveTheClient(string? input, string expected) =>
        Assert.Equal(expected, StaffAuthDiagnostics.NormalizeSource(input));

    [Theory]
    [InlineData("local_disabled", true)]
    [InlineData("server_disabled", true)]
    [InlineData("authenticated", true)]
    [InlineData("iin=000000000000 password=secret", false)]
    [InlineData("https://user:password@example.invalid", false)]
    [InlineData("AUTHENTICATED", false)]
    [InlineData(null, false)]
    public void OnlyAllowlistedCodesMayEnterTheHeartbeat(string? input, bool expected) =>
        Assert.Equal(expected, StaffAuthDiagnostics.IsKnown(input));
}
