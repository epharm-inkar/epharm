using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Threading;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;

namespace CustomerDisplay;

/// <summary>
/// Notices from HQ (1.0.99, Grigoriy 25.11.2026). The till asks for the notices of the person signed in to Standard-N
/// with the 15-minute status check, on a cashier change and at once when the minute check of /api/posm/notice says HQ
/// sent or withdrew one. An open notice stays in the corner until the person closes it with ×; one at a time, after the
/// greeting, hidden while a recommendation popup is open and during the works. Closes are kept on disk and reported
/// until the server takes them. «Что такое ePharm» and the history of notices open as pages.
/// </summary>
public partial class MainWindow
{
    private PharmacistMessageInbox? _messageInbox;
    private string? _messageInboxPath;
    private EpharmMessageWindow? _messageWindow;
    private string _messageWindowUser = "";
    private bool _messageHiddenForRecommendation;
    private int _messageSeriesClosed;
    private EpharmPageWindow? _helpPage;
    private EpharmPageWindow? _messagesPage;
    private DateTimeOffset? _messagesStampSeen;
    private DateTime _messagesFetchedAt = DateTime.MinValue;
    private string _messagesFetchedUser = "";
    private bool _messagesFetching;
    private bool _messageAcksSending;

    private PharmacistMessageInbox MessageInbox()
    {
        if (_messageInbox != null) return _messageInbox;
        _messageInboxPath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Epharm", "messages-state.json");
        _messageInbox = PharmacistMessageInbox.Load(_messageInboxPath);
        return _messageInbox;
    }

    /// <summary>Notices waiting for the person at this till (red dot, tooltip, personal window).</summary>
    private int PendingHqMessages()
    {
        var user = CurrentSelfUserId();
        return user.Length == 0 || _messageInbox == null ? 0 : _messageInbox.PendingCount(user);
    }

    /// <summary>Asks the server for the person's notices, sends owed reports and shows the next open notice.</summary>
    private async Task RefreshHqMessagesAsync(bool force = false)
    {
        if (_epharm == null || _hqMessagesDisposed) return;
        if (_messagesFetching)
        {
            // HQ changed something while a request is under way: ask again right after it.
            if (force) _messagesRefetchQueued = true;
            return;
        }
        var request = SelfRequest(link: false);
        if (request == null)
        {
            CloseHqMessageQuietly();
            return;
        }
        var user = request.StandardNUserId;
        if (!force && string.Equals(_messagesFetchedUser, user, StringComparison.OrdinalIgnoreCase) &&
            DateTime.UtcNow - _messagesFetchedAt < TimeSpan.FromSeconds(20)) return;
        _messagesFetching = true;
        try
        {
            var result = await _epharm.GetSelfMessagesAsync(request);
            if (result.Ok)
            {
                MessageInbox().Apply(user, (result.Value!.Messages ?? new List<PosmSelfMessageDto>()).Select(ToTillMessage), DateTimeOffset.Now);
                _messagesFetchedUser = user;
                _messagesFetchedAt = DateTime.UtcNow;
                _messagesFeedKnown = true;
            }
        }
        finally
        {
            _messagesFetching = false;
        }
        await SendHqMessageAcksAsync();
        if (_messagesRefetchQueued)
        {
            _messagesRefetchQueued = false;
            await RefreshHqMessagesAsync(force: true);
            return;
        }
        if (!string.Equals(CurrentSelfUserId(), user, StringComparison.OrdinalIgnoreCase)) return;
        OnHqMessagesChanged();
        ShowNextHqMessage();
    }

    /// <summary>true once this server answered the notices request (an older server has none: the row stays hidden).</summary>
    private bool _messagesFeedKnown;
    private bool _messagesRefetchQueued;
    /// <summary>The program is closing: no notice, greeting or page opens any more.</summary>
    private bool _hqMessagesDisposed;

    private static TillMessage ToTillMessage(PosmSelfMessageDto dto) => new(
        dto.Id, dto.Title?.Trim() ?? "", dto.Text?.Trim() ?? "", dto.SentAt, dto.ClosedAt, dto.Pending ?? dto.ClosedAt == null);

    /// <summary>Shows the oldest open notice of the person, unless something else has the corner now.</summary>
    private void ShowNextHqMessage()
    {
        var user = CurrentSelfUserId();
        if (_messageWindow != null && !string.Equals(_messageWindowUser, user, StringComparison.OrdinalIgnoreCase)) CloseHqMessageQuietly();
        if (_hqMessagesDisposed || _messageWindow != null || user.Length == 0 || _messageInbox == null) return;
        if (EpharmPaused || _recoWindow != null || _greetingWindow != null) return;
        var pending = _messageInbox.Pending(user);
        if (pending.Count == 0)
        {
            _messageSeriesClosed = 0;
            return;
        }
        var message = pending[0];
        var window = new EpharmMessageWindow(message, _messageSeriesClosed + 1, _messageSeriesClosed + pending.Count, DateTimeOffset.Now, PharmacistScreen());
        window.Displayed += shown => OnHqMessageDisplayed(user, shown);
        window.Dismissed += closed => OnHqMessageDismissed(user, closed);
        window.Closed += (_, _) =>
        {
            if (!ReferenceEquals(_messageWindow, window)) return;
            _messageWindow = null;
            _messageWindowUser = "";
            _messageHiddenForRecommendation = false;
        };
        _messageWindow = window;
        _messageWindowUser = user;
        try
        {
            window.Show();
            Log("ePharm: показано уведомление HQ " + message.Id);
        }
        catch (Exception error)
        {
            _messageWindow = null;
            _messageWindowUser = "";
            Log("ePharm: уведомление HQ не удалось показать: " + error.Message);
        }
    }

    private void OnHqMessageDisplayed(string user, TillMessage message)
    {
        var inbox = MessageInbox();
        inbox.MarkShown(user, message.Id, DateTimeOffset.Now);
        inbox.Save(_messageInboxPath!, Log);
        _ = SendHqMessageAcksAsync();
    }

    private void OnHqMessageDismissed(string user, TillMessage message)
    {
        var inbox = MessageInbox();
        inbox.MarkClosed(user, message.Id, DateTimeOffset.Now);
        inbox.Save(_messageInboxPath!, Log);
        _messageSeriesClosed++;
        Log("ePharm: уведомление HQ закрыто " + message.Id);
        OnHqMessagesChanged();
        _ = SendHqMessageAcksAsync();
        // The window is closing now; the next one opens after it.
        Dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(ShowNextHqMessage));
    }

    /// <summary>Sends owed «shown» and «closed» reports, oldest first; stops at the first network failure.</summary>
    private async Task SendHqMessageAcksAsync()
    {
        if (_epharm == null || _messageAcksSending || _messageInbox == null) return;
        var pharmacy = _posmConfig?.PharmacyId?.Trim() ?? "";
        if (pharmacy.Length == 0) return;
        _messageAcksSending = true;
        try
        {
            foreach (var ack in _messageInbox.AcksToSend().Take(20))
            {
                var result = await _epharm.AckSelfMessageAsync(
                    new PosmSelfRequestDto(pharmacy, ack.User, Link: false), ack.MessageId, ack.Kind == TillMessageAckKind.Closed);
                if (result == MessageAckResult.Retry) break;
                _messageInbox.AckDone(ack);
                if (result == MessageAckResult.Gone) Log("ePharm: сервер не принял отметку уведомления " + ack.MessageId + " (снято или не для этого сотрудника)");
            }
            _messageInbox.Save(_messageInboxPath!, Log);
        }
        finally
        {
            _messageAcksSending = false;
        }
    }

    /// <summary>The red dot, the tooltip and an open personal window follow the notices.</summary>
    private void OnHqMessagesChanged()
    {
        ApplyNoticeToTray();
        if (_selfWindow != null && _selfWindow.IsLoaded && _messagesFeedKnown)
        {
            var user = CurrentSelfUserId();
            _selfWindow.UpdateMessages(PendingHqMessages(), user.Length == 0 ? 0 : MessageInbox().History(user).Count);
        }
    }

    /// <summary>The notice belongs to someone else now (cashier change) or the works began: it goes without a report.</summary>
    private void CloseHqMessageQuietly()
    {
        var window = _messageWindow;
        _messageWindow = null;
        _messageWindowUser = "";
        _messageHiddenForRecommendation = false;
        _messageSeriesClosed = 0;
        window?.Close();
    }

    /// <summary>A recommendation popup has the screen: the open notice steps aside until it closes.</summary>
    private void PauseHqMessageForRecommendation()
    {
        if (_messageWindow == null || !_messageWindow.IsVisible) return;
        _messageWindow.Hide();
        _messageHiddenForRecommendation = true;
    }

    private void ResumeHqMessageAfterRecommendation()
    {
        // Another scan opened a new popup meanwhile: wait for that one.
        if (_recoWindow != null) return;
        if (_messageWindow != null && _messageHiddenForRecommendation)
        {
            _messageHiddenForRecommendation = false;
            if (string.Equals(_messageWindowUser, CurrentSelfUserId(), StringComparison.OrdinalIgnoreCase) && !EpharmPaused)
            {
                try { _messageWindow.Show(); }
                catch (Exception error) { Log("ePharm: уведомление HQ не вернулось на экран: " + error.Message); }
            }
            else CloseHqMessageQuietly();
        }
        ShowNextHqMessage();
        FlushPendingWelcome();
    }

    /// <summary>The minute check of /api/posm/notice: HQ sent or withdrew a notice since the last one.</summary>
    private void OnMessagesStamp(DateTimeOffset? stamp)
    {
        if (stamp == null || stamp == _messagesStampSeen) return;
        var first = _messagesStampSeen == null;
        _messagesStampSeen = stamp;
        _ = RefreshHqMessagesAsync(force: !first);
    }

    /// <summary>«Что такое ePharm»: one page, brought forward when it is already open.</summary>
    private void ShowWhatIsEpharm()
    {
        if (_hqMessagesDisposed) return;
        if (_helpPage != null && _helpPage.IsLoaded)
        {
            _helpPage.Activate();
            return;
        }
        var page = new EpharmPageWindow(EpharmHelpContent.PageTitle, EpharmHelpContent.Lead, EpharmHelpContent.Sections, PharmacistScreen());
        page.Closed += (_, _) => { if (ReferenceEquals(_helpPage, page)) _helpPage = null; };
        _helpPage = page;
        page.Show();
        Log("ePharm: открыта страница «Что такое ePharm»");
    }

    /// <summary>«Уведомления»: the person's notices of the last 30 days, newest first, open ones marked «Новое».</summary>
    private void ShowHqMessagesPage()
    {
        if (_hqMessagesDisposed) return;
        var user = CurrentSelfUserId();
        var history = user.Length == 0 || _messageInbox == null ? Array.Empty<TillMessage>() : _messageInbox.History(user);
        _messagesPage?.Close();
        var page = new EpharmPageWindow(
            "Уведомления",
            history.Count > 0 ? "Уведомления от офиса за последние 30 дней. Новые также появляются в углу экрана." : null,
            TillMessageFormat.HistorySections(history, DateTimeOffset.Now),
            PharmacistScreen(),
            user.Length == 0
                ? "Войдите в Стандарт-Н под своим пользователем, чтобы увидеть свои уведомления."
                : "За последние 30 дней уведомлений не было.");
        page.Closed += (_, _) => { if (ReferenceEquals(_messagesPage, page)) _messagesPage = null; };
        _messagesPage = page;
        page.Show();
    }

    private void DisposeHqMessages()
    {
        _hqMessagesDisposed = true;
        _messageWindow?.Close();
        _helpPage?.Close();
        _messagesPage?.Close();
        _greetingWindow?.Close();
    }
}
