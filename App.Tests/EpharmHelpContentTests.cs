using System;
using System.Linq;
using System.Text.Json;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

/// <summary>
/// «Что такое ePharm» (1.0.99) and the texts of the greeting and the notices: the four parts the owner asked for, plain
/// short lines, no long dashes, no numbers or promises of money; and the notice data the till reads from the server.
/// </summary>
public sealed class EpharmHelpContentTests
{
    [Fact]
    public void The_page_explains_what_it_is_how_to_use_it_how_to_earn_and_where_to_ask()
    {
        Assert.Equal("Что такое ePharm", EpharmHelpContent.PageTitle);
        var titles = EpharmHelpContent.Sections.Select(s => s.Title).ToList();
        Assert.Contains("Что это", titles);
        Assert.Contains("Как пользоваться на кассе", titles);
        Assert.Contains("«Мой кабинет» и курсы", titles);
        Assert.Contains("Как вы зарабатываете", titles);
        Assert.Contains("Где спросить помощь", titles);
        var all = string.Join(" ", EpharmHelpContent.AllTexts());
        Assert.Contains("подсказк", all);
        Assert.Contains("«Мой кабинет»", all);
        Assert.Contains("Обучение", all);
        Assert.Contains("МАС", all);
    }

    [Fact]
    public void Lines_stay_short_enough_to_read_at_the_till()
    {
        foreach (var section in EpharmHelpContent.Sections)
        {
            Assert.InRange(section.Lines.Count, 1, 5);
            Assert.All(section.Lines, line => Assert.InRange(line.Length, 10, 220));
        }
    }

    [Fact]
    public void No_long_dashes_and_no_numbers_in_what_the_pharmacist_reads()
    {
        var texts = EpharmHelpContent.AllTexts()
            .Concat(PharmacistGreeting.HelpLines)
            .Concat(Enumerable.Range(0, 24).SelectMany(h =>
            {
                var (hello, wish) = PharmacistGreeting.Words(new DateTime(2026, 11, 26, h, 0, 0));
                return new[] { hello, wish };
            }))
            .ToList();
        foreach (var text in texts)
        {
            Assert.DoesNotContain("—", text);
            Assert.DoesNotContain("–", text);
            Assert.DoesNotContain(text, c => char.IsDigit(c));
            Assert.DoesNotContain("%", text);
        }
    }

    [Fact]
    public void The_till_reads_notices_and_the_time_they_last_changed()
    {
        const string messages = """
            {"messages":[{"id":"6f1c2e1a-0000-4000-8000-000000000001","title":"Инвентаризация","text":"Завтра в 9:00.",
              "sentAt":"2026-11-26T03:30:00Z","closedAt":null,"pending":true}],"pendingCount":1}
            """;
        var dto = JsonSerializer.Deserialize<PosmSelfMessagesDto>(messages, EpharmJson.Options)!;
        var message = Assert.Single(dto.Messages!);
        Assert.Equal("Инвентаризация", message.Title);
        Assert.True(message.Pending);
        Assert.Null(message.ClosedAt);
        Assert.Equal(1, dto.PendingCount);

        const string notice = """{"maintenance":false,"title":"t","message":"m","changedAt":null,"messagesAt":"2026-11-26T03:30:00Z"}""";
        Assert.Equal(DateTimeOffset.Parse("2026-11-26T03:30:00Z"), JsonSerializer.Deserialize<PosmNoticeDto>(notice, EpharmJson.Options)!.MessagesAt);
        const string olderNotice = """{"maintenance":true,"title":"t","message":"m","changedAt":null}""";
        Assert.Null(JsonSerializer.Deserialize<PosmNoticeDto>(olderNotice, EpharmJson.Options)!.MessagesAt);
    }

    [Fact]
    public void Reports_carry_the_person_and_never_a_password()
    {
        var body = JsonSerializer.Serialize(new PosmSelfRequestDto("pharmacy-1", "34", "+77010000000", "secret-1", Link: true)
            with { Link = false, Phone = null, Password = null }, EpharmJson.Options);
        using var parsed = JsonDocument.Parse(body);
        Assert.Equal("34", parsed.RootElement.GetProperty("standardNUserId").GetString());
        Assert.Equal(JsonValueKind.Null, parsed.RootElement.GetProperty("password").ValueKind);
        Assert.Equal(JsonValueKind.Null, parsed.RootElement.GetProperty("phone").ValueKind);
        Assert.False(parsed.RootElement.GetProperty("link").GetBoolean());
    }
}
