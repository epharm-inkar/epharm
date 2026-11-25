using System;
using System.IO;
using System.Text.Json;
using System.Windows.Threading;

namespace CustomerDisplay;

/// <summary>
/// «Вход в ePharm выполнен» (1.0.96, Grigoriy 25.11): when a person signs in to Standard-N and the server knows them,
/// a card in the corner greets them by name once a day: «Доброе утро, …! Вход в ePharm выполнен». A click opens the
/// personal window; the card closes itself and never takes the focus from the cashier program. Not during the works;
/// the «снова работает» card counts as today's greeting.
/// </summary>
public partial class MainWindow
{
    private string? _welcomeStatePath;
    private string _welcomedUser = "";
    private DateTime _welcomedDay = DateTime.MinValue;
    private (string User, string Name)? _pendingWelcome;
    private EpharmToastWindow? _welcomeToast;

    /// <summary>Called after the server named the person signed in at this till.</summary>
    private void MaybeWelcome(string standardNUserId, string? fullName)
    {
        if (string.IsNullOrWhiteSpace(standardNUserId)) return;
        LoadWelcomeState();
        var today = DateTime.Today;
        if (_welcomedUser == standardNUserId && _welcomedDay == today) return;
        // The works state is not known yet (first minute after start): greet once it is.
        if (_noticeState?.Maintenance == null) { _pendingWelcome = (standardNUserId, fullName ?? ""); return; }
        if (EpharmPaused) return;
        _pendingWelcome = null;
        RememberWelcome(standardNUserId, today);
        var name = RecommendationWindow.ShortName(fullName);
        _welcomeToast?.Close();
        var toast = new EpharmToastWindow(
            Greeting(DateTime.Now) + (name.Length > 0 ? ", " + name : "") + "!",
            "Вход в ePharm выполнен. Подсказки по заменам будут появляться здесь. Нажмите, чтобы открыть свой кабинет. Хорошего дня!",
            PharmacistScreen());
        toast.Clicked += () => Dispatcher.BeginInvoke(new Action(() => _ = OpenStaffSelfAsync()));
        toast.Closed += (_, _) => { if (ReferenceEquals(_welcomeToast, toast)) _welcomeToast = null; };
        _welcomeToast = toast;
        toast.Show();
        Log("ePharm: приветствие показано (вход в ePharm выполнен)");
    }

    /// <summary>The works state became known: a greeting that waited for it goes out now.</summary>
    private void FlushPendingWelcome()
    {
        if (_pendingWelcome is { } pending && !EpharmPaused) MaybeWelcome(pending.User, pending.Name);
        else if (EpharmPaused) _pendingWelcome = null;
    }

    /// <summary>«ePharm снова работает» already greeted the person at the till today.</summary>
    private void CountLiveToastAsWelcome()
    {
        var user = CurrentSelfUserId();
        if (user.Length > 0) RememberWelcome(user, DateTime.Today);
    }

    public static string Greeting(DateTime now) => now.Hour switch
    {
        >= 5 and < 12 => "Доброе утро",
        >= 12 and < 18 => "Добрый день",
        >= 18 and < 23 => "Добрый вечер",
        _ => "Доброй ночи",
    };

    private void LoadWelcomeState()
    {
        if (_welcomeStatePath != null) return;
        _welcomeStatePath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Epharm", "welcome-state.json");
        try
        {
            if (!File.Exists(_welcomeStatePath)) return;
            using var doc = JsonDocument.Parse(File.ReadAllText(_welcomeStatePath));
            _welcomedUser = doc.RootElement.GetProperty("user").GetString() ?? "";
            _welcomedDay = DateTime.Parse(doc.RootElement.GetProperty("day").GetString() ?? "", System.Globalization.CultureInfo.InvariantCulture);
        }
        catch
        {
            _welcomedUser = "";
            _welcomedDay = DateTime.MinValue;
        }
    }

    private void RememberWelcome(string user, DateTime day)
    {
        LoadWelcomeState();
        _welcomedUser = user;
        _welcomedDay = day;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_welcomeStatePath!)!);
            File.WriteAllText(_welcomeStatePath!, JsonSerializer.Serialize(new { user, day = day.ToString("yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture) }));
        }
        catch (Exception error)
        {
            Log("ePharm: не удалось сохранить приветствие: " + error.Message);
        }
    }
}
