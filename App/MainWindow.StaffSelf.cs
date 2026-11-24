using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay;

/// <summary>
/// The ePharm icon by the clock opens the pharmacist's personal window (no login): the person is the Standard-N
/// user signed in at this till. A red dot on the icon reminds them to add phone and password until both are set.
/// </summary>
public partial class MainWindow
{
    private System.Windows.Forms.NotifyIcon? _selfTray;
    private StaffSelfWindow? _selfWindow;
    private readonly DispatcherTimer _selfStatusTimer = new() { Interval = TimeSpan.FromMinutes(15) };
    private readonly DispatcherTimer _selfWindowRefresh = new() { Interval = TimeSpan.FromMinutes(8) };
    private bool? _selfProfileComplete;
    private string _selfStatusUser = "";
    private System.Drawing.Icon? _selfIconPlain, _selfIconAlert;

    /// <summary>Called once the POSM client is up (InitPosm). Does nothing when the integration is off.</summary>
    private void InitStaffSelfTray()
    {
        if (_epharm == null || _posmConfig == null || !_posmConfig.Enabled || _selfTray != null) return;
        _selfIconPlain = SelfIcon(alert: false);
        _selfIconAlert = SelfIcon(alert: true);
        _selfTray = new System.Windows.Forms.NotifyIcon
        {
            Icon = _selfIconPlain,
            Text = "ePharm — мой кабинет",
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
        _selfTray.ContextMenuStrip = menu;

        _selfStatusTimer.Tick += async (_, _) => await RefreshSelfStatusAsync();
        _selfStatusTimer.Start();
        _selfWindowRefresh.Tick += async (_, _) => await RefreshOpenSelfWindowAsync();
        Closed += (_, _) => DisposeStaffSelfTray();
        Dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(() => _ = RefreshSelfStatusAsync()));
    }

    private void DisposeStaffSelfTray()
    {
        _selfStatusTimer.Stop();
        _selfWindowRefresh.Stop();
        _selfWindow?.Close();
        if (_selfTray != null) { _selfTray.Visible = false; _selfTray.Dispose(); _selfTray = null; }
        _selfIconPlain?.Dispose();
        _selfIconAlert?.Dispose();
    }

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
        if (request == null) { SetSelfTrayState(null, null); return; }
        var result = await _epharm.GetSelfAsync(request);
        if (!result.Ok) return;
        _selfStatusUser = request.StandardNUserId;
        SetSelfTrayState(result.Value!.PhoneBound && result.Value.PasswordSet, result.Value.FullName);
    }

    private void SetSelfTrayState(bool? complete, string? name)
    {
        if (_selfTray == null) return;
        _selfProfileComplete = complete;
        _selfTray.Icon = complete == false ? _selfIconAlert : _selfIconPlain;
        var shortName = RecommendationWindow.ShortName(name);
        var text = complete == false ? "ePharm: заполните профиль" : shortName.Length > 0 ? "ePharm: " + shortName : "ePharm — мой кабинет";
        _selfTray.Text = text.Length <= 63 ? text : text[..63];
    }

    private async Task OpenStaffSelfAsync()
    {
        if (_epharm == null) return;
        var model = await LoadSelfModelAsync();
        if (_selfWindow != null && _selfWindow.IsLoaded)
        {
            _selfWindow.Update(model);
            _selfWindow.Activate();
            return;
        }
        var window = new StaffSelfWindow(model, PharmacistScreen());
        window.OpenCabinetRequested += () => _ = OpenCabinetAsync();
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
            return new StaffSelfModel { Notice = "Войдите в Стандарт-Н под своим пользователем: ePharm узнает вас автоматически, отдельный вход не нужен." };
        var result = await _epharm!.GetSelfAsync(request);
        if (!result.Ok) return new StaffSelfModel { Notice = result.Error };
        var self = result.Value!;
        SetSelfTrayState(self.PhoneBound && self.PasswordSet, self.FullName);
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
        };
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
        var cleanPhone = string.IsNullOrWhiteSpace(phone) || phone.Trim() == "привязан" ? null : phone.Trim();
        if (cleanPhone == null && string.IsNullOrEmpty(password))
        {
            _selfWindow.ShowStatus("Введите номер телефона или пароль.", error: true);
            return;
        }
        var request = SelfRequest(link: true, cleanPhone, password);
        if (request == null) { _selfWindow.ShowStatus("Войдите в Стандарт-Н, чтобы сохранить профиль.", error: true); return; }
        var result = await _epharm.SaveSelfContactAsync(request);
        if (!result.Ok) { _selfWindow.ShowStatus(result.Error ?? "Не удалось сохранить.", error: true); return; }
        _selfWindow.Update(await LoadSelfModelAsync());
        _selfWindow.ShowStatus("Сохранено.", error: false);
        Log("ePharm: профиль фармацевта обновлён с кассы (" + (cleanPhone != null ? "телефон" : "") +
            (cleanPhone != null && !string.IsNullOrEmpty(password) ? " и " : "") + (!string.IsNullOrEmpty(password) ? "пароль" : "") + ").");
    }

    private async Task OpenCabinetAsync()
    {
        if (_epharm == null) return;
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

    /// <summary>Teal round ePharm mark; with a red dot in the corner while the profile is not filled.</summary>
    private static System.Drawing.Icon SelfIcon(bool alert)
    {
        using var bitmap = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bitmap))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);
            using var teal = new SolidBrush(Color.FromArgb(0x11, 0x2B, 0x33));
            g.FillEllipse(teal, 1, 1, 30, 30);
            using var font = new Font("Segoe UI", 15, System.Drawing.FontStyle.Bold, GraphicsUnit.Pixel);
            using var white = new SolidBrush(Color.White);
            var size = g.MeasureString("e", font);
            g.DrawString("e", font, white, (32 - size.Width) / 2, (32 - size.Height) / 2 - 1);
            if (alert)
            {
                using var red = new SolidBrush(Color.FromArgb(0xE0, 0x39, 0x2B));
                using var ring = new Pen(Color.White, 2);
                g.FillEllipse(red, 19, 1, 12, 12);
                g.DrawEllipse(ring, 19, 1, 12, 12);
            }
        }
        var handle = bitmap.GetHicon();
        using var temp = System.Drawing.Icon.FromHandle(handle);
        var icon = (System.Drawing.Icon)temp.Clone();
        SelfNative.DestroyIcon(handle);
        return icon;
    }

    private static class SelfNative
    {
        [System.Runtime.InteropServices.DllImport("user32.dll", SetLastError = true)]
        [return: System.Runtime.InteropServices.MarshalAs(System.Runtime.InteropServices.UnmanagedType.Bool)]
        internal static extern bool DestroyIcon(IntPtr handle);
    }
}
