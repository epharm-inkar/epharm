using System;
using System.IO;
using CustomerDisplay.Services;
using Xunit;

/// <summary>«ePharm снова работает» appears once, only after works this till saw, also across a restart.</summary>
public sealed class EpharmNoticeStateTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "epharm-notice-" + Guid.NewGuid().ToString("N"));
    private string StatePath => Path.Combine(_dir, "notice-state.json");

    [Fact]
    public void Works_then_live_announces_once()
    {
        var state = new EpharmNoticeState();
        Assert.False(state.Apply(true));
        Assert.True(state.Apply(false));
        Assert.False(state.Apply(false));
    }

    [Fact]
    public void A_till_that_never_saw_the_works_announces_nothing()
    {
        var state = new EpharmNoticeState();
        Assert.False(state.Apply(false));
        Assert.False(state.Maintenance);
    }

    [Fact]
    public void A_till_off_while_the_works_ended_announces_after_restart()
    {
        var before = new EpharmNoticeState();
        before.Apply(true);
        before.Save(StatePath);
        var afterRestart = EpharmNoticeState.Load(StatePath);
        Assert.True(afterRestart.Maintenance);
        Assert.True(afterRestart.Apply(false));
        afterRestart.Save(StatePath);
        Assert.False(EpharmNoticeState.Load(StatePath).Apply(false));
    }

    [Fact]
    public void A_damaged_or_missing_file_starts_unknown()
    {
        Directory.CreateDirectory(_dir);
        File.WriteAllText(StatePath, "{not json");
        Assert.Null(EpharmNoticeState.Load(StatePath).Maintenance);
        Assert.Null(EpharmNoticeState.Load(Path.Combine(_dir, "missing.json")).Maintenance);
    }

    [Fact]
    public void Unknown_state_is_not_written()
    {
        new EpharmNoticeState().Save(StatePath);
        Assert.False(File.Exists(StatePath));
    }

    public void Dispose()
    {
        try { Directory.Delete(_dir, recursive: true); } catch { }
    }
}
