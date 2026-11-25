using System;
using System.IO;
using System.Linq;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

/// <summary>
/// The greeting at the till (1.0.99): «Доброе утро, Имя Отчество! Желаем хорошего дня» by the time of day, once per
/// person per day (the day starts at 05:00), also across a restart.
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

    [Fact]
    public void The_greeting_day_starts_at_five_in_the_morning()
    {
        Assert.Equal(new DateTime(2026, 11, 25), PharmacistGreeting.GreetingDay(At(26, 4, 59)));
        Assert.Equal(new DateTime(2026, 11, 26), PharmacistGreeting.GreetingDay(At(26, 5, 0)));
        Assert.Equal(new DateTime(2026, 11, 26), PharmacistGreeting.GreetingDay(At(26, 23, 30)));
    }

    [Fact]
    public void Each_person_is_greeted_once_a_day()
    {
        var log = new GreetingLog();
        Assert.True(log.ShouldGreet("34", At(26, 8)));
        log.Remember("34", At(26, 8));
        Assert.False(log.ShouldGreet("34", At(26, 8, 15)));
        // A colleague signs in at the same till: their own greeting; the first person is not greeted again.
        Assert.True(log.ShouldGreet("35", At(26, 9)));
        log.Remember("35", At(26, 9));
        Assert.False(log.ShouldGreet("34", At(26, 14)));
        Assert.False(log.ShouldGreet("35", At(26, 22)));
        // The next morning both are greeted again.
        Assert.True(log.ShouldGreet("34", At(27, 7)));
        Assert.True(log.ShouldGreet("35", At(27, 7)));
    }

    [Fact]
    public void A_night_shift_crossing_midnight_is_greeted_once()
    {
        var log = new GreetingLog();
        log.Remember("34", At(26, 20));
        Assert.False(log.ShouldGreet("34", At(27, 0, 30)));
        Assert.False(log.ShouldGreet("34", At(27, 4, 59)));
        Assert.True(log.ShouldGreet("34", At(27, 5, 0)));
    }

    [Fact]
    public void A_restart_does_not_greet_again_the_same_day()
    {
        var log = new GreetingLog();
        log.Remember("34", At(26, 8));
        log.Remember("35", At(26, 9));
        log.Save(StatePath);
        var afterRestart = GreetingLog.Load(StatePath);
        Assert.False(afterRestart.ShouldGreet("34", At(26, 12)));
        Assert.False(afterRestart.ShouldGreet("35", At(26, 12)));
        Assert.True(afterRestart.ShouldGreet("36", At(26, 12)));
        Assert.True(afterRestart.ShouldGreet("34", At(27, 8)));
    }

    [Fact]
    public void The_1_0_96_file_still_counts_for_today()
    {
        Directory.CreateDirectory(_dir);
        var legacy = Path.Combine(_dir, "welcome-state.json");
        File.WriteAllText(legacy, """{"user":"34","day":"2026-11-26"}""");
        var log = GreetingLog.Load(StatePath, legacy);
        Assert.False(log.ShouldGreet("34", At(26, 15)));
        Assert.True(log.ShouldGreet("35", At(26, 15)));
        Assert.True(log.ShouldGreet("34", At(27, 8)));
    }

    [Fact]
    public void A_damaged_or_missing_file_greets_again_rather_than_never()
    {
        Directory.CreateDirectory(_dir);
        File.WriteAllText(StatePath, "{not json");
        Assert.True(GreetingLog.Load(StatePath).ShouldGreet("34", At(26, 8)));
        Assert.True(GreetingLog.Load(Path.Combine(_dir, "missing.json")).ShouldGreet("34", At(26, 8)));
        Assert.False(new GreetingLog().ShouldGreet(" ", At(26, 8)));
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
