using System;
using System.IO;
using System.Windows.Threading;
using CustomerDisplay.Services;

namespace CustomerDisplay;

/// <summary>
/// The greeting (1.0.99, Grigoriy 25.11.2026): when the server knows the person signed in to Standard-N at this till,
/// their first sign-in of the day (from 05:00) brings «Доброе утро, Имя Отчество! Желаем хорошего дня» with two lines on
/// how ePharm helps and «Что такое ePharm». Once per person per day, also across restarts; never during the works,
/// never over an open recommendation (it waits for the popup to close) and never takes the focus. The «снова работает»
/// card counts as today's greeting.
/// </summary>
public partial class MainWindow
{
    private string? _greetingLogPath;
    private GreetingLog? _greetingLog;
    private (string User, string Name)? _pendingWelcome;
    private EpharmGreetingWindow? _greetingWindow;

    /// <summary>Called after the server named the person signed in at this till.</summary>
    private void MaybeWelcome(string standardNUserId, string? fullName)
    {
        if (string.IsNullOrWhiteSpace(standardNUserId) || _hqMessagesDisposed) return;
        var log = GreetingLogState();
        var now = DateTime.Now;
        if (!log.ShouldGreet(standardNUserId, now)) return;
        // The works state is not known yet (first minute after start), or a recommendation is on screen: greet later.
        if (_noticeState?.Maintenance == null || _recoWindow != null) { _pendingWelcome = (standardNUserId, fullName ?? ""); return; }
        if (EpharmPaused) return;
        // Someone else signed in meanwhile: the waiting greeting is not theirs.
        if (!string.Equals(CurrentSelfUserId(), standardNUserId, StringComparison.OrdinalIgnoreCase)) { _pendingWelcome = null; return; }
        _pendingWelcome = null;
        log.Remember(standardNUserId, now);
        log.Save(_greetingLogPath!, Log);
        _greetingWindow?.Close();
        var window = new EpharmGreetingWindow(
            PharmacistGreeting.Title(now, fullName),
            PharmacistGreeting.Words(now).Wish,
            PharmacistGreeting.HelpLines,
            PharmacistScreen(),
            PharmacistGreeting.Lifetime);
        window.WhatIsEpharmRequested += () => Dispatcher.BeginInvoke(new Action(ShowWhatIsEpharm));
        window.Closed += (_, _) =>
        {
            if (!ReferenceEquals(_greetingWindow, window)) return;
            _greetingWindow = null;
            // A notice from HQ waited for the greeting to go.
            Dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(ShowNextHqMessage));
        };
        _greetingWindow = window;
        try
        {
            window.Show();
            Log("ePharm: приветствие показано");
        }
        catch (Exception error)
        {
            _greetingWindow = null;
            Log("ePharm: приветствие не удалось показать: " + error.Message);
        }
    }

    /// <summary>The works state became known or the recommendation closed: a greeting that waited goes out now.</summary>
    private void FlushPendingWelcome()
    {
        if (_pendingWelcome is { } pending && !EpharmPaused) MaybeWelcome(pending.User, pending.Name);
        else if (EpharmPaused) _pendingWelcome = null;
    }

    /// <summary>«ePharm снова работает» already greeted the person at the till today.</summary>
    private void CountLiveToastAsWelcome()
    {
        var user = CurrentSelfUserId();
        if (user.Length == 0) return;
        var log = GreetingLogState();
        log.Remember(user, DateTime.Now);
        log.Save(_greetingLogPath!, Log);
    }

    /// <summary>Kept for the 1.0.96 showcase: the hello word by the time of day.</summary>
    public static string Greeting(DateTime now) => PharmacistGreeting.Words(now).Hello;

    private GreetingLog GreetingLogState()
    {
        if (_greetingLog != null) return _greetingLog;
        var folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Epharm");
        _greetingLogPath = Path.Combine(folder, "greeting-state.json");
        _greetingLog = GreetingLog.Load(_greetingLogPath, Path.Combine(folder, "welcome-state.json"));
        return _greetingLog;
    }
}
