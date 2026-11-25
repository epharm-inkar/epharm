using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

/// <summary>
/// The red dot on the ePharm icon (1.0.99): it stays while a required course is not finished and goes once the server
/// says it is done; notices from HQ and the older reasons (profile, new or overdue tasks) keep it too.
/// </summary>
public sealed class EpharmAttentionTests
{
    private static PosmSelfTaskDto Task(string status, bool required = true) =>
        new(Guid.NewGuid().ToString(), "course", "Курс", null, null, required, status, 0, null, null);

    [Fact]
    public void The_server_count_of_unfinished_required_courses_lights_the_dot()
    {
        var feed = new PosmSelfTasksDto(new List<PosmSelfTaskDto> { Task("in_progress") }, NewCount: 0, OpenCount: 1, RequiredOpenCount: 1);
        Assert.Equal(1, EpharmAttention.RequiredUnfinished(feed));
        Assert.True(EpharmAttention.NeedsDot(true, 0, EpharmAttention.NewOrOverdue(feed), EpharmAttention.RequiredUnfinished(feed), 0));

        // The course is completed in the cabinet: the next answer says 0 and the dot goes.
        var done = new PosmSelfTasksDto(new List<PosmSelfTaskDto> { Task("done") }, 0, 0, RequiredOpenCount: 0);
        Assert.Equal(0, EpharmAttention.RequiredUnfinished(done));
        Assert.False(EpharmAttention.NeedsDot(true, 0, EpharmAttention.NewOrOverdue(done), EpharmAttention.RequiredUnfinished(done), 0));
    }

    [Fact]
    public void The_server_count_wins_over_the_list()
    {
        // A paused required course is in the list but HQ paused it: the server does not count it.
        var feed = new PosmSelfTasksDto(new List<PosmSelfTaskDto> { Task("in_progress") }, 0, 1, RequiredOpenCount: 0);
        Assert.Equal(0, EpharmAttention.RequiredUnfinished(feed));
    }

    [Fact]
    public void An_older_server_without_the_count_falls_back_to_the_list()
    {
        var feed = new PosmSelfTasksDto(new List<PosmSelfTaskDto>
        {
            Task("new"), Task("overdue"), Task("in_progress", required: false), Task("done"),
        }, NewCount: 1, OpenCount: 3);
        Assert.Equal(2, EpharmAttention.RequiredUnfinished(feed));
        Assert.Equal(2, EpharmAttention.NewOrOverdue(feed));
        Assert.Equal(0, EpharmAttention.RequiredUnfinished(null));
        Assert.Equal(0, EpharmAttention.RequiredUnfinished(new PosmSelfTasksDto(null, 0, 0)));
    }

    [Fact]
    public void The_count_is_read_from_the_server_json_and_absent_from_an_older_one()
    {
        const string current = """{"tasks":[],"newCount":0,"openCount":2,"requiredOpenCount":2}""";
        const string older = """{"tasks":[],"newCount":0,"openCount":2}""";
        Assert.Equal(2, JsonSerializer.Deserialize<PosmSelfTasksDto>(current, EpharmJson.Options)!.RequiredOpenCount);
        Assert.Null(JsonSerializer.Deserialize<PosmSelfTasksDto>(older, EpharmJson.Options)!.RequiredOpenCount);
    }

    [Theory]
    [InlineData(false, 0, 0, 0, 0, true)]
    [InlineData(true, 1, 0, 0, 0, true)]
    [InlineData(true, 0, 1, 0, 0, true)]
    [InlineData(true, 0, 0, 1, 0, true)]
    [InlineData(true, 0, 0, 0, 1, true)]
    [InlineData(true, 0, 0, 0, 0, false)]
    [InlineData(null, 0, 0, 0, 0, false)]
    public void Every_reason_lights_the_dot(bool? profile, int merch, int newTasks, int required, int messages, bool dot)
    {
        Assert.Equal(dot, EpharmAttention.NeedsDot(profile, merch, newTasks, required, messages));
    }

    [Fact]
    public void The_tooltip_names_what_waits_and_fits_windows()
    {
        Assert.Equal("ePharm: технические работы", EpharmAttention.TrayText(true, false, "Айгерим С.", 3, 2, 1));
        Assert.Equal("ePharm: Айгерим С.", EpharmAttention.TrayText(false, true, "Айгерим С.", 0, 0, 0));
        Assert.Equal("ePharm: Айгерим С. · уведомлений: 1 · курсов: 2", EpharmAttention.TrayText(false, true, "Айгерим С.", 0, 2, 1));
        Assert.Equal("ePharm: заполните профиль", EpharmAttention.TrayText(false, false, "Айгерим С.", 0, 0, 0));
        Assert.Equal("ePharm: мой кабинет", EpharmAttention.TrayText(false, null, "", 0, 0, 0));
        var crowded = EpharmAttention.TrayText(false, true, new string('Я', 40), 12, 3, 4);
        Assert.True(crowded.Length <= 63);
    }
}
