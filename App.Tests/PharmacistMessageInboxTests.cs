using System;
using System.IO;
using System.Linq;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

/// <summary>
/// Notices from HQ on the till (1.0.99): a notice stays until the person closes it with ×, the close survives a lost
/// report and a restart, reports go until the server takes them, and each person sees only their own notices.
/// </summary>
public sealed class PharmacistMessageInboxTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "epharm-messages-" + Guid.NewGuid().ToString("N"));
    private string StatePath => Path.Combine(_dir, "messages-state.json");
    private static readonly DateTimeOffset Now = new(2026, 11, 26, 9, 0, 0, TimeSpan.FromHours(5));

    private static TillMessage Message(string id, int hoursAgo, bool pending = true, DateTimeOffset? closedAt = null) =>
        new(id, "Заголовок " + id, "Текст " + id, Now.AddHours(-hoursAgo), closedAt, pending);

    [Fact]
    public void Open_notices_show_oldest_first_and_stay_until_closed()
    {
        var inbox = new PharmacistMessageInbox();
        inbox.Apply("34", new[] { Message("b", 1), Message("a", 5), Message("old", 30, pending: false, closedAt: Now.AddHours(-29)) }, Now);
        Assert.Equal(new[] { "a", "b" }, inbox.Pending("34").Select(m => m.Id));
        Assert.Equal("a", inbox.NextToShow("34")!.Id);

        // Shown is not closed: it stays until the person presses ×.
        inbox.MarkShown("34", "a", Now);
        Assert.Equal("a", inbox.NextToShow("34")!.Id);

        inbox.MarkClosed("34", "a", Now.AddMinutes(1));
        Assert.Equal(new[] { "b" }, inbox.Pending("34").Select(m => m.Id));
        // The server has not taken the close yet and still lists it as open: it does not come back.
        inbox.Apply("34", new[] { Message("b", 1), Message("a", 5) }, Now.AddMinutes(2));
        Assert.Equal(new[] { "b" }, inbox.Pending("34").Select(m => m.Id));
        Assert.Equal(1, inbox.PendingCount("34"));
    }

    [Fact]
    public void Reports_are_owed_until_the_server_takes_them()
    {
        var inbox = new PharmacistMessageInbox();
        inbox.Apply("34", new[] { Message("a", 2), Message("b", 1) }, Now);
        inbox.MarkShown("34", "a", Now);
        inbox.MarkShown("34", "b", Now);
        inbox.MarkClosed("34", "a", Now.AddMinutes(1));

        var owed = inbox.AcksToSend();
        Assert.Contains(new TillMessageAck("34", "a", TillMessageAckKind.Closed), owed);
        Assert.Contains(new TillMessageAck("34", "b", TillMessageAckKind.Shown), owed);
        // A close also records the show: no separate «shown» for a closed notice.
        Assert.DoesNotContain(new TillMessageAck("34", "a", TillMessageAckKind.Shown), owed);

        inbox.AckDone(new TillMessageAck("34", "b", TillMessageAckKind.Shown));
        Assert.Equal(new[] { new TillMessageAck("34", "a", TillMessageAckKind.Closed) }, inbox.AcksToSend());
        inbox.AckDone(new TillMessageAck("34", "a", TillMessageAckKind.Closed));
        Assert.Empty(inbox.AcksToSend());

        // Closing b later owes a close, even though its show was reported.
        inbox.MarkClosed("34", "b", Now.AddMinutes(5));
        Assert.Equal(new[] { new TillMessageAck("34", "b", TillMessageAckKind.Closed) }, inbox.AcksToSend());
    }

    [Fact]
    public void A_notice_on_screen_leaves_once_withdrawn_expired_or_closed_elsewhere_without_a_report()
    {
        var inbox = new PharmacistMessageInbox();
        inbox.Apply("34", new[] { Message("a", 3), Message("b", 2), Message("c", 1) }, Now);
        inbox.MarkShown("34", "a", Now);
        inbox.AckDone(new TillMessageAck("34", "a", TillMessageAckKind.Shown));
        Assert.True(inbox.IsPending("34", "a"));

        // HQ withdrew «a» while it is on screen: the server no longer lists it, the card goes and «b» is next.
        inbox.Apply("34", new[] { Message("b", 2), Message("c", 1) }, Now.AddMinutes(1));
        Assert.False(inbox.IsPending("34", "a"));
        Assert.Equal("b", inbox.NextToShow("34")!.Id);

        // «b» passed its last day: listed, but no longer open.
        inbox.Apply("34", new[] { Message("b", 2, pending: false), Message("c", 1) }, Now.AddMinutes(2));
        Assert.False(inbox.IsPending("34", "b"));

        // «c» was closed on another till.
        inbox.Apply("34", new[] { Message("c", 1, pending: false, closedAt: Now.AddMinutes(2)) }, Now.AddMinutes(3));
        Assert.False(inbox.IsPending("34", "c"));
        Assert.Null(inbox.NextToShow("34"));

        // Leaving the screen this way owes the server nothing, and it is never «closed here».
        Assert.Empty(inbox.AcksToSend());
        Assert.DoesNotContain(inbox.History("34"), m => m.Id == "a");
    }

    [Fact]
    public void A_notice_stays_while_it_is_still_open_for_this_person_only()
    {
        var inbox = new PharmacistMessageInbox();
        inbox.Apply("34", new[] { Message("a", 1) }, Now);
        Assert.True(inbox.IsPending("34", "a"));
        Assert.True(inbox.IsPending(" 34 ", " a "));
        Assert.False(inbox.IsPending("35", "a"));
        Assert.False(inbox.IsPending("34", "unknown"));
        // A failed refresh does not call Apply: the card keeps its place.
        Assert.True(inbox.IsPending("34", "a"));
        inbox.MarkClosed("34", "a", Now);
        Assert.False(inbox.IsPending("34", "a"));
    }

    [Fact]
    public void A_notice_closed_on_another_till_leaves_this_one_without_a_report()
    {
        var inbox = new PharmacistMessageInbox();
        inbox.Apply("34", new[] { Message("a", 2) }, Now);
        inbox.MarkShown("34", "a", Now);
        inbox.Apply("34", new[] { Message("a", 2, pending: false, closedAt: Now.AddMinutes(3)) }, Now.AddMinutes(4));
        Assert.Empty(inbox.Pending("34"));
        Assert.Empty(inbox.AcksToSend());
    }

    [Fact]
    public void Each_person_sees_only_their_own_notices_and_reports_keep_the_person()
    {
        var inbox = new PharmacistMessageInbox();
        inbox.Apply("34", new[] { Message("a", 2) }, Now);
        inbox.Apply("35", new[] { Message("c", 1) }, Now);
        Assert.Equal("a", Assert.Single(inbox.Pending("34")).Id);
        Assert.Equal("c", Assert.Single(inbox.Pending("35")).Id);
        Assert.Empty(inbox.Pending("36"));
        Assert.Empty(inbox.Pending(""));

        inbox.MarkClosed("34", "a", Now);
        // The cashier changed before the report went: it is still sent in the name of the person who closed it.
        inbox.ForgetServerList("34");
        Assert.Equal(new[] { new TillMessageAck("34", "a", TillMessageAckKind.Closed) }, inbox.AcksToSend());
        Assert.Equal("c", Assert.Single(inbox.Pending("35")).Id);
    }

    [Fact]
    public void A_close_survives_a_restart_and_its_report_is_sent_after_it()
    {
        var inbox = new PharmacistMessageInbox();
        inbox.Apply("34", new[] { Message("a", 2), Message("b", 1) }, Now);
        inbox.MarkClosed("34", "a", Now);
        inbox.Save(StatePath);

        var afterRestart = PharmacistMessageInbox.Load(StatePath);
        Assert.Equal(new[] { new TillMessageAck("34", "a", TillMessageAckKind.Closed) }, afterRestart.AcksToSend());
        afterRestart.Apply("34", new[] { Message("a", 2), Message("b", 1) }, Now.AddMinutes(10));
        Assert.Equal(new[] { "b" }, afterRestart.Pending("34").Select(m => m.Id));
    }

    [Fact]
    public void A_damaged_file_starts_empty()
    {
        Directory.CreateDirectory(_dir);
        File.WriteAllText(StatePath, "{broken");
        var inbox = PharmacistMessageInbox.Load(StatePath);
        Assert.Empty(inbox.AcksToSend());
        Assert.Empty(PharmacistMessageInbox.Load(Path.Combine(_dir, "missing.json")).AcksToSend());
    }

    [Fact]
    public void The_history_is_newest_first_with_local_closes()
    {
        var inbox = new PharmacistMessageInbox();
        inbox.Apply("34", new[]
        {
            Message("a", 30, pending: false, closedAt: Now.AddHours(-20)),
            Message("b", 2),
            Message("c", 1),
        }, Now);
        inbox.MarkClosed("34", "b", Now);
        var history = inbox.History("34");
        Assert.Equal(new[] { "c", "b", "a" }, history.Select(m => m.Id));
        Assert.True(history[0].Pending);
        Assert.False(history[1].Pending);
        Assert.Equal(Now, history[1].ClosedAt);
    }

    [Fact]
    public void Old_entries_are_dropped_and_the_store_stays_bounded()
    {
        var inbox = new PharmacistMessageInbox();
        for (var i = 0; i < PharmacistMessageInbox.MaxEntries + 50; i++)
        {
            inbox.MarkClosed("34", "m" + i, Now.AddMinutes(i));
            inbox.AckDone(new TillMessageAck("34", "m" + i, TillMessageAckKind.Closed));
        }
        inbox.MarkClosed("34", "owed", Now.AddDays(1));
        inbox.Save(StatePath);
        var loaded = PharmacistMessageInbox.Load(StatePath);
        // The owed report survives; settled ones went first.
        Assert.Equal(new[] { new TillMessageAck("34", "owed", TillMessageAckKind.Closed) }, loaded.AcksToSend());

        var stale = new PharmacistMessageInbox();
        stale.MarkClosed("34", "x", Now.AddDays(-60));
        stale.Apply("34", Array.Empty<TillMessage>(), Now);
        Assert.Empty(stale.AcksToSend());
    }

    [Fact]
    public void Ack_outcomes_by_status()
    {
        Assert.Equal(MessageAckResult.Done, MessageAckResults.ForStatus(200));
        foreach (var gone in new[] { 400, 403, 404, 409, 410 }) Assert.Equal(MessageAckResult.Gone, MessageAckResults.ForStatus(gone));
        foreach (var retry in new[] { 401, 429, 500, 502, 503 }) Assert.Equal(MessageAckResult.Retry, MessageAckResults.ForStatus(retry));
    }

    [Fact]
    public void Times_read_like_people_say_them()
    {
        Assert.Equal("сегодня в 08:30", TillMessageFormat.Sent(Now.AddMinutes(-30), Now));
        Assert.Equal("вчера в 17:45", TillMessageFormat.Sent(new DateTimeOffset(2026, 11, 25, 17, 45, 0, TimeSpan.FromHours(5)), Now));
        Assert.Equal("3 ноября в 09:05", TillMessageFormat.Sent(new DateTimeOffset(2026, 11, 3, 9, 5, 0, TimeSpan.FromHours(5)), Now));
        Assert.Equal("30 декабря 2025 в 12:00", TillMessageFormat.Sent(new DateTimeOffset(2025, 12, 30, 12, 0, 0, TimeSpan.FromHours(5)), Now));
        // The server sends UTC; the till shows its own clock.
        Assert.Equal("сегодня в 08:00", TillMessageFormat.Sent(new DateTimeOffset(2026, 11, 26, 3, 0, 0, TimeSpan.Zero), Now));
    }

    [Fact]
    public void The_history_page_has_one_section_per_notice_with_new_ones_marked()
    {
        var sections = TillMessageFormat.HistorySections(new[]
        {
            new TillMessage("b", "Инвентаризация", "Коллеги!\n\nЗавтра в 9:00.", Now.AddMinutes(-30), null, true),
            new TillMessage("a", "Курс", "Пройдите курс до пятницы.", Now.AddDays(-1), Now.AddHours(-20), false),
        }, Now);
        Assert.Equal(2, sections.Count);
        Assert.Equal("Инвентаризация", sections[0].Title);
        Assert.Equal(new[] { "Коллеги!", "Завтра в 9:00." }, sections[0].Lines);
        Assert.Equal("Новое", sections[0].Badge);
        Assert.Equal("Отправлено сегодня в 08:30", sections[0].Subtitle);
        Assert.False(sections[0].Bullets);
        Assert.Null(sections[1].Badge);
        Assert.Equal("Отправлено вчера в 09:00, прочитано вчера в 13:00", sections[1].Subtitle);
    }

    public void Dispose()
    {
        try { Directory.Delete(_dir, recursive: true); } catch { }
    }
}
