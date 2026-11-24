using System;
using System.Linq;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

/// <summary>«Состояние ePharm»: every state gives the pharmacist a plain answer and, when needed, what to do.</summary>
public sealed class TillStatusTextTests
{
    private static readonly DateTimeOffset Now = new(2026, 11, 24, 10, 30, 0, TimeSpan.Zero);

    private static TillStatusSnapshot Healthy() => new(
        Now: Now, Version: "1.0.92", PharmacyName: "Аспект-траст, Бузурбаева 23", PharmacyId: "sloc_01KSAHYDSE7XBSG1YKFJQHX8F1",
        DeviceId: "KASSA2", ServerAcceptedAt: Now.AddSeconds(-40), ServerFailingSince: null, StandardNEnabled: true,
        StandardNReadAt: Now.AddSeconds(-5), StandardNFailingSince: null, SellerName: "Иванова А.", StaffLoginOpen: true,
        StaffAuthenticated: true, StaffName: null, Notice: null, LocalOffset: TimeSpan.FromHours(5));

    private static TillStatusRow Row(TillStatusSnapshot s, string key) => TillStatusText.Rows(s).Single(r => r.Key == key);

    [Fact]
    public void Healthy_till_is_all_green_and_says_so()
    {
        var rows = TillStatusText.Rows(Healthy());

        Assert.Equal(new[] { "server", "standardn", "login", "version" }, rows.Select(r => r.Key));
        Assert.Equal(TillStatusLevel.Ok, TillStatusText.Overall(rows));
        Assert.Equal("Есть, последний обмен в 15:29", Row(Healthy(), "server").StateRu);
        Assert.Contains("Иванова А.", Row(Healthy(), "standardn").StateRu);
        Assert.Equal("ePharm на этой кассе работает", TillStatusText.Headline(TillStatusLevel.Ok, kazakh: false));
        Assert.All(rows, r => Assert.Null(r.ActionRu));
    }

    [Fact]
    public void Lost_server_link_is_a_problem_with_the_time_and_what_to_do()
    {
        var s = Healthy() with { ServerAcceptedAt = Now.AddMinutes(-12), ServerFailingSince = Now.AddMinutes(-11) };

        var row = Row(s, "server");

        Assert.Equal(TillStatusLevel.Problem, row.Level);
        Assert.Equal("Нет связи с 15:18", row.StateRu);
        Assert.Contains("интернет", row.ActionRu);
        Assert.Contains("интернетті", row.ActionKk);
        Assert.Equal(TillStatusLevel.Problem, TillStatusText.Overall(TillStatusText.Rows(s)));
    }

    [Fact]
    public void A_till_that_never_reached_the_server_waits_instead_of_alarming()
    {
        var s = Healthy() with { ServerAcceptedAt = null, ServerFailingSince = null };

        Assert.Equal(TillStatusLevel.Info, Row(s, "server").Level);
    }

    [Fact]
    public void A_second_till_without_the_database_is_told_to_check_the_main_computer()
    {
        var s = Healthy() with { StandardNReadAt = Now.AddMinutes(-20), StandardNFailingSince = Now.AddMinutes(-19) };

        var row = Row(s, "standardn");

        Assert.Equal(TillStatusLevel.Problem, row.Level);
        Assert.Equal("Касса не видит базу с 15:11", row.StateRu);
        Assert.Contains("главный компьютер", row.ActionRu);
        Assert.Contains("негізгі компьютері", row.ActionKk);
    }

    [Fact]
    public void One_failed_read_is_not_an_alarm_but_a_minute_of_failures_is()
    {
        var blip = Healthy() with { StandardNReadAt = Now.AddSeconds(-30), StandardNFailingSince = Now.AddSeconds(-10) };
        var lasting = Healthy() with { StandardNReadAt = Now.AddSeconds(-90), StandardNFailingSince = Now.AddSeconds(-70) };
        var starting = Healthy() with { StandardNReadAt = null, StandardNFailingSince = Now.AddSeconds(-20) };

        Assert.Equal(TillStatusLevel.Ok, Row(blip, "standardn").Level);
        Assert.Equal(TillStatusLevel.Problem, Row(lasting, "standardn").Level);
        Assert.Equal(TillStatusLevel.Info, Row(starting, "standardn").Level);
    }

    [Fact]
    public void Closed_registration_tells_people_to_do_nothing()
    {
        var s = Healthy() with { StaffLoginOpen = false, StaffAuthenticated = false };

        var row = Row(s, "login");

        Assert.Equal(TillStatusLevel.Info, row.Level);
        Assert.Equal("Регистрация и вход временно закрыты", row.StateRu);
        Assert.StartsWith("Ничего делать не нужно", row.ActionRu);
        var overall = TillStatusText.Overall(TillStatusText.Rows(s));
        Assert.True(overall < TillStatusLevel.Warning, "closed registration must not look like a fault");
        Assert.Equal("ePharm на этой кассе работает", TillStatusText.Headline(overall, kazakh: false));
    }

    [Fact]
    public void Open_login_without_a_session_asks_to_sign_in()
    {
        var s = Healthy() with { StaffAuthenticated = false };

        var row = Row(s, "login");

        Assert.Equal(TillStatusLevel.Warning, row.Level);
        Assert.Contains("зарегистрируйтесь", row.ActionRu);
    }

    [Fact]
    public void Kazakh_text_exists_for_every_row_and_action()
    {
        var states = new[]
        {
            Healthy(),
            Healthy() with { ServerAcceptedAt = Now.AddMinutes(-12), ServerFailingSince = Now.AddMinutes(-11) },
            Healthy() with { StandardNReadAt = null, StandardNFailingSince = Now.AddMinutes(-3) },
            Healthy() with { StaffLoginOpen = false },
            Healthy() with { StaffAuthenticated = false },
        };
        foreach (var row in states.SelectMany(TillStatusText.Rows))
        {
            Assert.False(string.IsNullOrWhiteSpace(row.TitleKk));
            Assert.False(string.IsNullOrWhiteSpace(row.StateKk));
            Assert.Equal(row.ActionRu == null, row.ActionKk == null);
        }
    }

    [Fact]
    public void Support_code_is_short_and_contains_no_key()
    {
        Assert.Equal("80VCAMMT-KASSA2", TillStatusText.SupportCode(Healthy() with { PharmacyId = "sloc_01KSAHYDSM0F0EGW9580VCAMMT" }));
        Assert.Equal("?-?", TillStatusText.SupportCode(Healthy() with { PharmacyId = null, DeviceId = " " }));
    }

    [Theory]
    [InlineData(null, "Текст", false)]
    [InlineData("n1", null, false)]
    [InlineData("n1", "  ", false)]
    [InlineData("n1", "Текст", true)]
    public void Notice_needs_an_id_and_russian_text(string? id, string? ru, bool accepted)
    {
        var notice = EpharmApiClient.ToTillNotice(new TillNoticeDto { Id = id, Ru = ru, Kk = "Мәтін", Level = "WARNING" });

        Assert.Equal(accepted, notice != null);
        if (notice != null)
        {
            Assert.True(notice.IsWarning);
            Assert.Equal("Мәтін", notice.Text(kazakh: true));
        }
    }

    [Fact]
    public void Notice_falls_back_to_russian_and_is_trimmed_to_a_screen()
    {
        var notice = EpharmApiClient.ToTillNotice(new TillNoticeDto { Id = "n2", Ru = new string('а', 900), Level = "loud" })!;

        Assert.Equal(600, notice.Ru.Length);
        Assert.Equal(notice.Ru, notice.Text(kazakh: true));
        Assert.False(notice.IsWarning);
        Assert.Null(EpharmApiClient.ToTillNotice(null));
    }
}
