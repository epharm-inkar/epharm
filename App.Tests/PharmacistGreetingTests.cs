using System;
using System.IO;
using System.Linq;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

/// <summary>
/// The greeting at the till (1.0.99): «Доброе утро, Имя Отчество! Желаем хорошего дня» by the time of day, once per
/// person per shift (again only after 8 hours without them at the till), also across a restart.
/// </summary>
public sealed class PharmacistGreetingTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "epharm-greeting-" + Guid.NewGuid().ToString("N"));
    private string StatePath => Path.Combine(_dir, "greeting-state.json");
    private static DateTime At(int day, int hour, int minute = 0) => new(2026, 11, day, hour, minute, 0);

    [Theory]
    [InlineData(5, 0, "Доброе утро", "Желаем хорошего дня")]
    [InlineData(8, 5, "Доброе утро", "Желаем хорошего дня")]
    [InlineData(11, 59, "Доброе утро", "Желаем хорошего дня")]
    [InlineData(12, 0, "Добрый день", "Желаем хорошего дня")]
    [InlineData(17, 59, "Добрый день", "Желаем хорошего дня")]
    [InlineData(18, 0, "Добрый вечер", "Желаем хорошего вечера")]
    [InlineData(22, 59, "Добрый вечер", "Желаем хорошего вечера")]
    [InlineData(23, 0, "Здравствуйте", "Желаем спокойной смены")]
    [InlineData(2, 30, "Здравствуйте", "Желаем спокойной смены")]
    [InlineData(4, 59, "Здравствуйте", "Желаем спокойной смены")]
    public void Words_follow_the_time_of_day(int hour, int minute, string hello, string wish)
    {
        Assert.Equal((hello, wish), PharmacistGreeting.Words(At(26, hour, minute)));
    }

    [Fact]
    public void The_title_is_the_owner_wording_with_first_name_and_patronymic()
    {
        Assert.Equal("Доброе утро, Нұрсая Серікқызы!", PharmacistGreeting.Title(At(26, 8, 5), "Әбдіқалықова Нұрсая Серікқызы"));
        Assert.Equal("Добрый день, Айгерим!", PharmacistGreeting.Title(At(26, 13), "Сапарова Айгерим"));
        Assert.Equal("Добрый вечер!", PharmacistGreeting.Title(At(26, 19), null));
        Assert.Equal("Доброе утро!", PharmacistGreeting.Title(At(26, 9), "   "));
    }

    [Theory]
    [InlineData("Иванова Айгерим Сериковна", "Айгерим Сериковна")]
    [InlineData("  Иванова   Айгерим   Сериковна  ", "Айгерим Сериковна")]
    [InlineData("Ахметова Дана Серік қызы", "Дана Серік қызы")]
    [InlineData("Тұрсынов Әлихан", "Әлихан")]
    [InlineData("Айгерим", "Айгерим")]
    [InlineData("ИВАНОВА АЙГЕРИМ СЕРИКОВНА", "Айгерим Сериковна")]
    [InlineData("ПЕТРОВА АННА-МАРИЯ", "Анна-Мария")]
    [InlineData("Иванова айгерим", "айгерим")]
    [InlineData("", "")]
    [InlineData(null, "")]
    public void First_name_and_patronymic_come_after_the_surname(string? fullName, string expected)
    {
        Assert.Equal(expected, PharmacistGreeting.FirstNameAndPatronymic(fullName));
    }

    [Fact]
    public void A_very_long_name_is_cut()
    {
        var name = PharmacistGreeting.FirstNameAndPatronymic("Фамилия " + new string('я', 80) + " Отчество");
        Assert.True(name.Length <= 48);
    }

    /// <summary>The till's status check every 15 minutes while the person stays signed in.</summary>
    private static void SeenEvery15Minutes(GreetingLog log, string user, DateTime from, DateTime to, Action<DateTime>? each = null)
    {
        for (var t = from; t <= to; t = t.AddMinutes(15))
        {
            log.Seen(user, t);
            each?.Invoke(t);
        }
    }

    [Fact]
    public void Each_person_is_greeted_once_per_shift_and_a_colleague_in_between_changes_nothing()
    {
        var log = new GreetingLog();
        log.Seen("34", At(26, 8));
        Assert.True(log.ShouldGreet("34", At(26, 8)));
        log.Remember("34", At(26, 8));
        SeenEvery15Minutes(log, "34", At(26, 8, 15), At(26, 12), t => Assert.False(log.ShouldGreet("34", t)));

        // A colleague signs in at the same till: their own greeting.
        log.Seen("35", At(26, 12, 5));
        Assert.True(log.ShouldGreet("35", At(26, 12, 5)));
        log.Remember("35", At(26, 12, 5));
        // The first person comes back within the shift: no second greeting.
        log.Seen("34", At(26, 12, 40));
        Assert.False(log.ShouldGreet("34", At(26, 12, 40)));
        Assert.False(log.ShouldGreet("35", At(26, 13)));
    }

    [Fact]
    public void A_night_shift_from_eight_to_eight_is_greeted_once()
    {
        var log = new GreetingLog();
        var greetings = 0;
        SeenEvery15Minutes(log, "34", At(26, 20), At(27, 8), t =>
        {
            if (!log.ShouldGreet("34", t)) return;
            greetings++;
            log.Remember("34", t);
        });
        Assert.Equal(1, greetings);
        // The morning words would be different, but nobody is greeted again at 05:00.
        Assert.False(log.ShouldGreet("34", At(27, 5)));
    }

    [Fact]
    public void Eight_hours_without_the_person_start_a_new_shift()
    {
        var log = new GreetingLog();
        log.Remember("34", At(26, 8));
        SeenEvery15Minutes(log, "34", At(26, 8, 15), At(26, 17));
        // Next morning: a new shift.
        Assert.True(log.ShouldGreet("34", At(27, 8)));

        var short_ = new GreetingLog();
        short_.Remember("34", At(26, 2));
        Assert.False(short_.ShouldGreet("34", At(26, 9, 59)));
        Assert.True(short_.ShouldGreet("34", At(26, 10)));
        short_.Seen("34", At(26, 10));
        Assert.True(short_.ShouldGreet("34", At(26, 10)));
        short_.Remember("34", At(26, 10));
        Assert.False(short_.ShouldGreet("34", At(26, 10, 15)));
    }

    [Fact]
    public void A_person_seen_while_the_server_was_down_is_greeted_once_it_answers()
    {
        var log = new GreetingLog();
        SeenEvery15Minutes(log, "34", At(26, 8), At(26, 8, 45));
        Assert.True(log.ShouldGreet("34", At(26, 9)));
        log.Remember("34", At(26, 9));
        Assert.False(log.ShouldGreet("34", At(26, 9, 15)));
    }

    [Fact]
    public void A_restart_keeps_the_shift()
    {
        var log = new GreetingLog();
        log.Remember("34", At(26, 20));
        SeenEvery15Minutes(log, "34", At(26, 20, 15), At(27, 3));
        log.Remember("35", At(26, 21));
        log.Save(StatePath);
        var afterRestart = GreetingLog.Load(StatePath, null, At(27, 3, 10));
        Assert.False(afterRestart.ShouldGreet("34", At(27, 3, 10)));
        afterRestart.Seen("34", At(27, 3, 10));
        Assert.False(afterRestart.ShouldGreet("34", At(27, 6)));
        Assert.False(afterRestart.ShouldGreet("35", At(27, 4)));
        Assert.True(afterRestart.ShouldGreet("36", At(27, 4)));
    }

    [Fact]
    public void The_1_0_96_file_still_counts_for_its_day()
    {
        Directory.CreateDirectory(_dir);
        var legacy = Path.Combine(_dir, "welcome-state.json");
        File.WriteAllText(legacy, """{"user":"34","day":"2026-11-26"}""");
        var today = GreetingLog.Load(StatePath, legacy, At(26, 15));
        Assert.False(today.ShouldGreet("34", At(26, 15)));
        Assert.True(today.ShouldGreet("35", At(26, 15)));
        var nextDay = GreetingLog.Load(StatePath, legacy, At(27, 8));
        Assert.True(nextDay.ShouldGreet("34", At(27, 8)));
    }

    [Fact]
    public void A_damaged_or_missing_file_greets_again_rather_than_never()
    {
        Directory.CreateDirectory(_dir);
        File.WriteAllText(StatePath, "{not json");
        Assert.True(GreetingLog.Load(StatePath, null, At(26, 8)).ShouldGreet("34", At(26, 8)));
        Assert.True(GreetingLog.Load(Path.Combine(_dir, "missing.json"), null, At(26, 8)).ShouldGreet("34", At(26, 8)));
        Assert.False(new GreetingLog().ShouldGreet(" ", At(26, 8)));
    }

    [Fact]
    public void The_log_keeps_a_week_and_at_most_two_hundred_people()
    {
        var log = new GreetingLog();
        log.Remember("old", At(1, 8));
        for (var i = 0; i < 205; i++) log.Remember("p" + i, At(26, 8).AddMinutes(i));
        log.Save(StatePath);
        var loaded = GreetingLog.Load(StatePath, null, At(26, 12));
        // The oldest are dropped first; the most recent people are still remembered.
        Assert.True(loaded.ShouldGreet("old", At(26, 12)));
        Assert.True(loaded.ShouldGreet("p0", At(26, 12)));
        Assert.False(loaded.ShouldGreet("p204", At(26, 12)));
    }

    [Fact]
    public void The_help_lines_promise_no_money_and_carry_no_numbers()
    {
        Assert.InRange(PharmacistGreeting.HelpLines.Count, 1, 2);
        foreach (var line in PharmacistGreeting.HelpLines)
        {
            Assert.DoesNotContain(line, c => char.IsDigit(c));
            Assert.DoesNotContain("₸", line);
            Assert.DoesNotContain("бонус", line, StringComparison.OrdinalIgnoreCase);
            Assert.DoesNotContain("—", line);
        }
        Assert.Contains(PharmacistGreeting.HelpLines, l => l.Contains("подскажет"));
        Assert.Contains(PharmacistGreeting.HelpLines, l => l.Contains("Курсы"));
    }

    public void Dispose()
    {
        try { Directory.Delete(_dir, recursive: true); } catch { }
    }
}
