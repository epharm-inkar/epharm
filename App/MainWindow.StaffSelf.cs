using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;

namespace CustomerDisplay;

/// <summary>
/// The ePharm icon by the clock opens the pharmacist's personal window (no login): the person is the Standard-N
/// user signed in at this till. A red dot on the icon reminds them to add phone and password until both are set,
/// and (1.0.99) stays while a required course is not finished or a notice from HQ is not closed.
/// </summary>
public partial class MainWindow
{
    private System.Windows.Forms.NotifyIcon? _selfTray;
    private StaffSelfWindow? _selfWindow;
    private readonly DispatcherTimer _selfStatusTimer = new() { Interval = TimeSpan.FromMinutes(15) };
    private readonly DispatcherTimer _selfWindowRefresh = new() { Interval = TimeSpan.FromMinutes(8) };
    private bool? _selfProfileComplete;
    private string _selfStatusUser = "";
    private System.Drawing.Icon? _selfIconPlain, _selfIconAlert, _selfIconPaused;

    /// <summary>Called once the POSM client is up (InitPosm). Does nothing when the integration is off.</summary>
    private void InitStaffSelfTray()
    {
        if (_epharm == null || _posmConfig == null || !_posmConfig.Enabled || _selfTray != null) return;
        _selfIconPlain = EpharmMark.TrayIcon(EpharmMark.Dot.None);
        _selfIconAlert = EpharmMark.TrayIcon(EpharmMark.Dot.Alert);
        _selfIconPaused = EpharmMark.TrayIcon(EpharmMark.Dot.Paused);
        _selfTray = new System.Windows.Forms.NotifyIcon
        {
            Icon = _selfIconPlain,
            Text = "ePharm: мой кабинет",
            Visible = true,
        };
        _selfTray.MouseClick += (_, e) =>
        {
            if (e.Button == System.Windows.Forms.MouseButtons.Left)
                Dispatcher.BeginInvoke(new Action(() => _ = OpenStaffSelfAsync()));
        };
        var menu = new System.Windows.Forms.ContextMenuStrip();
        menu.Items.Add("Открыть ePharm", null, (_, _) => Dispatcher.BeginInvoke(new Action(() => _ = OpenStaffSelfAsync())));
        menu.Items.Add("Мой кабинет в браузере", null, (_, _) => Dispatcher.BeginInvoke(new Action(() => _ = OpenCabinetAsync())));
        menu.Items.Add("Задания аптеки", null, (_, _) => Dispatcher.BeginInvoke(new Action(() => OpenTaskKiosk(activate: true))));
        menu.Items.Add("Подсказки по чеку", null, (_, _) => Dispatcher.BeginInvoke(new Action(ReopenReceiptRecommendations)));
        menu.Items.Add("Уведомления", null, (_, _) => Dispatcher.BeginInvoke(new Action(ShowHqMessagesPage)));
        menu.Items.Add("Что такое ePharm", null, (_, _) => Dispatcher.BeginInvoke(new Action(ShowWhatIsEpharm)));
        _selfTray.ContextMenuStrip = menu;

        _selfStatusTimer.Tick += async (_, _) => await RefreshSelfStatusAsync();
        _selfStatusTimer.Start();
        _selfWindowRefresh.Tick += async (_, _) => await RefreshOpenSelfWindowAsync();
        Closed += (_, _) => DisposeStaffSelfTray();
        Dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(() => _ = RefreshSelfStatusAsync()));
        // The tasks icon started first: one icon is enough.
        if (_taskKioskTray != null) { _taskKioskTray.Visible = false; _taskKioskTray.Dispose(); _taskKioskTray = null; }
        InitEpharmNotice();
        InitRemoteCommands();
    }

    private void DisposeStaffSelfTray()
    {
        _selfStatusTimer.Stop();
        _selfWindowRefresh.Stop();
        _selfWindow?.Close();
        DisposeHqMessages();
        if (_selfTray != null) { _selfTray.Visible = false; _selfTray.Dispose(); _selfTray = null; }
        _selfIconPlain?.Dispose();
        _selfIconAlert?.Dispose();
        _selfIconPaused?.Dispose();
    }

    /// <summary>A new cashier signed in at this till: refresh the red dot for them.</summary>
    partial void OnCashierChanged() =>
        Dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(() =>
        {
            // A notice on screen belongs to the previous person: it goes at once, without a report (1.0.99).
            ShowNextHqMessage();
            _ = RefreshSelfStatusAsync();
        }));

    /// <summary>Standard-N user id of the cashier currently signed in at this till (from the receipt reader).</summary>
    private string CurrentSelfUserId() => (_currentPharmacistId ?? "").Trim();

    private PosmSelfRequestDto? SelfRequest(bool link = true, string? phone = null, string? password = null)
    {
        var user = CurrentSelfUserId();
        var pharmacy = _posmConfig?.PharmacyId?.Trim() ?? "";
        if (user.Length == 0 || pharmacy.Length == 0) return null;
        return new PosmSelfRequestDto(pharmacy, user, phone, password, link);
    }

    /// <summary>Background check for the red dot. Also called when the cashier at the till changes.</summary>
    private async Task RefreshSelfStatusAsync()
    {
        if (_epharm == null || _selfTray == null) return;
        var request = SelfRequest(link: false);
        if (request == null)
        {
            _newHqTasks = 0;
            _requiredCourses = 0;
            _hqTasksUser = "";
            CloseHqMessageQuietly();
            SetSelfTrayState(null, null);
            return;
        }
        NoteSignedInForGreeting(request.StandardNUserId);
        var result = await _epharm.GetSelfAsync(request);
        if (!result.Ok) return;
        _selfStatusUser = request.StandardNUserId;
        SetSelfTrayState(result.Value!.PhoneBound && result.Value.PasswordSet, result.Value.FullName);
        await LoadHqTasksAsync(request);
        MaybeWelcome(request.StandardNUserId, result.Value.FullName);
        await RefreshHqMessagesAsync();
    }

    private void SetSelfTrayState(bool? complete, string? name)
    {
        if (_selfTray == null) return;
        _selfProfileComplete = complete;
        _selfTrayName = RecommendationWindow.ShortName(name);
        ApplyNoticeToTray();
    }

    /// <summary>
    /// Icon and tooltip: amber dot during the works (the red dot waits); red dot while something waits for the
    /// person: the profile is not filled or there is a task (Grigoriy, 25.11: «уведомление или задание новое»).
    /// </summary>
    private void ApplyNoticeToTray()
    {
        if (_selfTray == null) return;
        _selfTray.Icon = EpharmPaused ? _selfIconPaused : NeedsAttention() ? _selfIconAlert : _selfIconPlain;
        UpdateSelfTrayText();
    }

    private bool NeedsAttention() => EpharmAttention.NeedsDot(
        _selfProfileComplete, TasksCountForSelf() ?? 0, _newHqTasks, _requiredCourses, PendingHqMessages());

    private string _selfTrayName = "";

    /// <summary>Tooltip of the one ePharm icon: who is known, the profile, notices, required courses, open tasks.</summary>
    private void UpdateSelfTrayText()
    {
        if (_selfTray == null) return;
        _selfTray.Text = EpharmAttention.TrayText(
            EpharmPaused, _selfProfileComplete, _selfTrayName, TasksCountForSelf() ?? 0, _requiredCourses, PendingHqMessages());
    }

    private async Task OpenStaffSelfAsync()
    {
        if (_epharm == null) return;
        if (EpharmPaused) { ShowMaintenanceWindow(); return; }
        var model = await LoadSelfModelAsync();
        if (_selfWindow != null && _selfWindow.IsLoaded)
        {
            _selfWindow.Update(model);
            _selfWindow.Activate();
            return;
        }
        var window = new StaffSelfWindow(model, PharmacistScreen());
        window.OpenCabinetRequested += () => _ = OpenCabinetAsync();
        window.OpenTasksRequested += () => OpenTaskKiosk(activate: true);
        window.WhatIsEpharmRequested += ShowWhatIsEpharm;
        window.OpenMessagesRequested += ShowHqMessagesPage;
        window.SaveContactRequested += (phone, password) => _ = SaveSelfContactAsync(phone, password);
        window.Closed += (_, _) => { _selfWindowRefresh.Stop(); if (ReferenceEquals(_selfWindow, window)) _selfWindow = null; };
        _selfWindow = window;
        window.Show();
        _selfWindowRefresh.Start();
    }

    private async Task<StaffSelfModel> LoadSelfModelAsync()
    {
        var request = SelfRequest(link: true);
        if (request == null)
            return new StaffSelfModel
            {
                Notice = "Войдите в Стандарт-Н под своим пользователем: ePharm узнает вас автоматически, отдельный вход не нужен.",
                TasksCount = TasksCountForSelf(), TasksOnline = _taskKioskOnline,
            };
        var result = await _epharm!.GetSelfAsync(request);
        if (!result.Ok) return new StaffSelfModel { Notice = result.Error, TasksCount = TasksCountForSelf(), TasksOnline = _taskKioskOnline };
        var self = result.Value!;
        SetSelfTrayState(self.PhoneBound && self.PasswordSet, self.FullName);
        var hqTasks = await LoadHqTasksAsync(request);
        await RefreshHqMessagesAsync();
        var history = _messageInbox?.History(request.StandardNUserId).Count ?? 0;
        return new StaffSelfModel
        {
            FullName = self.FullName,
            Iin = self.IinMasked,
            Pharmacy = self.Pharmacy,
            EarnedMonthKzt = self.EarnedMonthKzt,
            BalanceKzt = self.BalanceKzt,
            Recent = (self.Recent ?? new()).Select(r => new StaffSelfReward(r.Kind, r.Label, r.BonusKzt, r.At)).ToList(),
            PhoneBound = self.PhoneBound,
            PasswordSet = self.PasswordSet,
            CabinetUrl = self.CabinetUrl,
            TasksCount = TasksCountForSelf(),
            TasksOnline = _taskKioskOnline,
            HqTasks = hqTasks,
            MessagesPending = _messagesFeedKnown ? PendingHqMessages() : null,
            MessagesTotal = history,
        };
    }

    private int _newHqTasks;
    /// <summary>Required courses not finished by the person at this till (1.0.99): the red dot stays while above 0.</summary>
    private int _requiredCourses;
    private string _hqTasksUser = "";

    /// <summary>
    /// HQ tasks for the person (1.0.96); an older server without the feed gives none. A failed request keeps the last
    /// counts of the same person, so the red dot does not blink with the network.
    /// </summary>
    private async Task<IReadOnlyList<StaffSelfTask>> LoadHqTasksAsync(PosmSelfRequestDto request)
    {
        var result = await _epharm!.GetSelfTasksAsync(request with { Link = false });
        if (!result.Ok)
        {
            if (!string.Equals(_hqTasksUser, request.StandardNUserId, StringComparison.OrdinalIgnoreCase))
            {
                _newHqTasks = 0;
                _requiredCourses = 0;
            }
            ApplyNoticeToTray();
            return Array.Empty<StaffSelfTask>();
        }
        _hqTasksUser = request.StandardNUserId;
        _newHqTasks = EpharmAttention.NewOrOverdue(result.Value);
        _requiredCourses = EpharmAttention.RequiredUnfinished(result.Value);
        ApplyNoticeToTray();
        return (result.Value!.Tasks ?? new()).Select(t => new StaffSelfTask(
            t.Title, t.Instructions ?? "", t.RewardKzt, t.Status, t.ProgressPct, t.DueAt, t.Required)).ToList();
    }

    /// <summary>Pharmacy tasks shown in the personal window; null when this till has no task feed.</summary>
    private int? TasksCountForSelf() => _taskKiosk == null ? null : _taskKioskItems.Count;

    /// <summary>One ePharm icon by the clock: the personal icon replaces the separate «Задания аптеки» icon.</summary>
    internal bool PersonalTrayReplacesTasksTray => _selfTray != null;

    /// <summary>The task feed changed: the tooltip and an open personal window show the new count.</summary>
    private void OnTasksChangedForSelf()
    {
        ApplyNoticeToTray();
        if (_selfWindow != null && _selfWindow.IsLoaded) _selfWindow.UpdateTasks(TasksCountForSelf(), _taskKioskOnline);
    }

    private async Task RefreshOpenSelfWindowAsync()
    {
        // The QR carries a minutes-lived one-time link: keep it fresh while the window stays open.
        if (_selfWindow == null || !_selfWindow.IsLoaded) { _selfWindowRefresh.Stop(); return; }
        _selfWindow.Update(await LoadSelfModelAsync());
    }

    private async Task SaveSelfContactAsync(string? phone, string? password)
    {
        if (_epharm == null || _selfWindow == null) return;
        var cleanPhone = string.IsNullOrWhiteSpace(phone) ? null : phone.Trim();
        if (cleanPhone == null && string.IsNullOrEmpty(password))
        {
            _selfWindow.ShowStatus("Введите номер телефона или пароль.", error: true);
            return;
        }
        var request = SelfRequest(link: true, cleanPhone, password);
        if (request == null) { _selfWindow.ShowStatus("Войдите в Стандарт-Н, чтобы сохранить профиль.", error: true); return; }
        var result = await _epharm.SaveSelfContactAsync(request);
        if (!result.Ok) { _selfWindow.ShowStatus(result.Error ?? "Не удалось сохранить.", error: true); return; }
        _selfWindow.ClearContactInputs();
        _selfWindow.Update(await LoadSelfModelAsync());
        _selfWindow.ShowStatus("Сохранено.", error: false);
        Log("ePharm: профиль фармацевта обновлён с кассы (" + (cleanPhone != null ? "телефон" : "") +
            (cleanPhone != null && !string.IsNullOrEmpty(password) ? " и " : "") + (!string.IsNullOrEmpty(password) ? "пароль" : "") + ").");
    }

    private async Task OpenCabinetAsync()
    {
        if (_epharm == null) return;
        if (EpharmPaused) { ShowMaintenanceWindow(); return; }
        var request = SelfRequest(link: true);
        if (request == null) { _selfWindow?.ShowStatus("Войдите в Стандарт-Н, чтобы открыть кабинет.", error: true); return; }
        var result = await _epharm.GetCabinetLinkAsync(request);
        if (!result.Ok) { _selfWindow?.ShowStatus(result.Error ?? "Не удалось открыть кабинет.", error: true); return; }
        try
        {
            Process.Start(new ProcessStartInfo(result.Value!.Url) { UseShellExecute = true });
        }
        catch (Exception error)
        {
            Log("ePharm: не удалось открыть браузер: " + error.Message);
            _selfWindow?.ShowStatus("Не удалось открыть браузер на этом компьютере.", error: true);
        }
    }
}
