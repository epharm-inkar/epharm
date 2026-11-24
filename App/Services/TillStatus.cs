using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;

namespace CustomerDisplay.Services
{
    public enum TillStatusLevel { Ok, Info, Warning, Problem }

    /// <summary>One line of the «Состояние ePharm» window: what works, what does not and what the pharmacist does.</summary>
    public sealed record TillStatusRow(
        string Key,
        TillStatusLevel Level,
        string TitleRu, string TitleKk,
        string StateRu, string StateKk,
        string? ActionRu = null, string? ActionKk = null);

    /// <summary>Notice from HQ delivered with the heartbeat answer (for example, maintenance).</summary>
    public sealed record TillNotice(string Id, string Ru, string Kk, string Level = "info")
    {
        public bool IsValid => !string.IsNullOrWhiteSpace(Id) && !string.IsNullOrWhiteSpace(Ru);
        public string Text(bool kazakh) => kazakh && !string.IsNullOrWhiteSpace(Kk) ? Kk : Ru;
        public bool IsWarning => string.Equals(Level, "warning", StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>What the till knows about itself at one moment; filled by MainWindow, read by the window.</summary>
    public sealed record TillStatusSnapshot(
        DateTimeOffset Now,
        string Version,
        string? PharmacyName,
        string? PharmacyId,
        string? DeviceId,
        DateTimeOffset? ServerAcceptedAt,
        DateTimeOffset? ServerFailingSince,
        bool StandardNEnabled,
        DateTimeOffset? StandardNReadAt,
        DateTimeOffset? StandardNFailingSince,
        string? SellerName,
        bool? StaffLoginOpen,
        bool StaffAuthenticated,
        string? StaffName,
        TillNotice? Notice,
        TimeSpan LocalOffset);

    /// <summary>
    /// Plain-language texts for the till status window (Russian and Kazakh). Pure: the same snapshot always
    /// gives the same rows, so every state is unit-tested and rendered for review without a real till.
    /// </summary>
    public static class TillStatusText
    {
        /// <summary>Heartbeat runs every 60 s: three missed beats mean the link is down.</summary>
        public static readonly TimeSpan ServerStaleAfter = TimeSpan.FromMinutes(3);

        /// <summary>The Standard-N reader runs every few seconds; five minutes without a read is a real failure.</summary>
        public static readonly TimeSpan StandardNStaleAfter = TimeSpan.FromMinutes(5);

        /// <summary>How long reads must keep failing before the window calls it a problem.</summary>
        public static readonly TimeSpan StandardNFailGrace = TimeSpan.FromMinutes(1);

        public static IReadOnlyList<TillStatusRow> Rows(TillStatusSnapshot s)
        {
            ArgumentNullException.ThrowIfNull(s);
            return new[] { Server(s), StandardN(s), Login(s), Version(s) };
        }

        public static TillStatusLevel Overall(IReadOnlyList<TillStatusRow> rows) =>
            rows.Count == 0 ? TillStatusLevel.Info : rows.Max(r => r.Level);

        public static string Headline(TillStatusLevel level, bool kazakh) => level switch
        {
            TillStatusLevel.Problem => kazakh ? "Назар аударыңыз: төмендегі ескертуді қараңыз" : "Есть проблема: посмотрите, что делать, ниже",
            TillStatusLevel.Warning => kazakh ? "ePharm жұмыс істеп тұр, бірақ бір нәрсені тексеру керек" : "ePharm работает, но кое-что нужно проверить",
            _ => kazakh ? "ePharm осы кассада жұмыс істеп тұр" : "ePharm на этой кассе работает",
        };

        /// <summary>Code the pharmacist reads out to support: pharmacy and till, nothing secret.</summary>
        public static string SupportCode(TillStatusSnapshot s)
        {
            var pharmacy = string.IsNullOrWhiteSpace(s.PharmacyId) ? "?" : s.PharmacyId!.Trim();
            var device = string.IsNullOrWhiteSpace(s.DeviceId) ? "?" : s.DeviceId!.Trim();
            var shortPharmacy = pharmacy.Length > 8 ? pharmacy[^8..] : pharmacy;
            return $"{shortPharmacy}-{device}".ToUpperInvariant();
        }

        private static TillStatusRow Server(TillStatusSnapshot s)
        {
            const string ru = "Связь с ePharm", kk = "ePharm-мен байланыс";
            if (s.ServerAcceptedAt is { } ok && s.Now - ok <= ServerStaleAfter)
                return new("server", TillStatusLevel.Ok, ru, kk,
                    $"Есть, последний обмен в {Time(ok, s.LocalOffset)}", $"Бар, соңғы алмасу {Time(ok, s.LocalOffset)}");
            if (s.ServerAcceptedAt == null && s.ServerFailingSince == null)
                return new("server", TillStatusLevel.Info, ru, kk,
                    "Проверяется, подождите минуту", "Тексерілуде, бір минут күтіңіз");
            var since = s.ServerAcceptedAt ?? s.ServerFailingSince!.Value;
            return new("server", TillStatusLevel.Problem, ru, kk,
                $"Нет связи с {Time(since, s.LocalOffset)}", $"{Time(since, s.LocalOffset)} бастап байланыс жоқ",
                "Проверьте интернет на этом компьютере. Продавайте как обычно: ePharm отправит всё сам, когда связь вернётся. Если связи нет больше часа, сообщите МАС.",
                "Осы компьютердегі интернетті тексеріңіз. Әдеттегідей сата беріңіз: байланыс қалпына келгенде ePharm бәрін өзі жібереді. Байланыс бір сағаттан астам болмаса, МАС-қа хабарлаңыз.");
        }

        private static TillStatusRow StandardN(TillStatusSnapshot s)
        {
            const string ru = "База Стандарт-Н", kk = "Стандарт-Н базасы";
            if (!s.StandardNEnabled)
                return new("standardn", TillStatusLevel.Info, ru, kk,
                    "Не используется на этой кассе", "Бұл кассада қолданылмайды");
            // One failed read is often a busy Firebird; only a failure that lasts a minute is shown as a problem.
            var failingLong = s.StandardNFailingSince is { } since && s.Now - since >= StandardNFailGrace;
            if (!failingLong && s.StandardNReadAt is { } read && s.Now - read <= StandardNStaleAfter)
            {
                var seller = string.IsNullOrWhiteSpace(s.SellerName) ? null : s.SellerName!.Trim();
                return new("standardn", TillStatusLevel.Ok, ru, kk,
                    seller == null ? "Касса видит базу" : $"Касса видит базу, продаёт: {seller}",
                    seller == null ? "Касса базаны көреді" : $"Касса базаны көреді, сатушы: {seller}");
            }
            if (failingLong && s.StandardNFailingSince is { } failing)
                return new("standardn", TillStatusLevel.Problem, ru, kk,
                    $"Касса не видит базу с {Time(failing, s.LocalOffset)}", $"{Time(failing, s.LocalOffset)} бастап касса базаны көрмейді",
                    "Если это вторая касса, проверьте, что главный компьютер аптеки включён и на нём открыт Стандарт-Н. Перезапустите Стандарт-Н на этой кассе. Если не помогло, сообщите МАС: нужен ИТ.",
                    "Егер бұл екінші касса болса, дәріхананың негізгі компьютері қосулы және онда Стандарт-Н ашық екенін тексеріңіз. Осы кассада Стандарт-Н-ды қайта іске қосыңыз. Көмектеспесе, МАС-қа хабарлаңыз: IT маманы керек.");
            return new("standardn", TillStatusLevel.Info, ru, kk,
                "Проверяется, откройте Стандарт-Н", "Тексерілуде, Стандарт-Н-ды ашыңыз");
        }

        private static TillStatusRow Login(TillStatusSnapshot s)
        {
            const string ru = "Вход фармацевта", kk = "Фармацевттің кіруі";
            if (s.StaffLoginOpen == false)
                return new("login", TillStatusLevel.Info, ru, kk,
                    "Регистрация и вход временно закрыты", "Тіркелу мен кіру уақытша жабық",
                    "Ничего делать не нужно, работайте как обычно. Когда вход откроется, касса сама покажет окно входа.",
                    "Ештеңе істеудің қажеті жоқ, әдеттегідей жұмыс істей беріңіз. Кіру ашылғанда, касса кіру терезесін өзі көрсетеді.");
            if (s.StaffLoginOpen == null)
                return new("login", TillStatusLevel.Info, ru, kk,
                    "Станет известно, когда появится связь", "Байланыс болғанда белгілі болады");
            if (s.StaffAuthenticated)
            {
                var name = string.IsNullOrWhiteSpace(s.StaffName) ? null : s.StaffName!.Trim();
                return new("login", TillStatusLevel.Ok, ru, kk,
                    name == null ? "Вы вошли" : $"Вы вошли: {name}", name == null ? "Сіз кірдіңіз" : $"Сіз кірдіңіз: {name}");
            }
            return new("login", TillStatusLevel.Warning, ru, kk,
                "Вы не вошли в ePharm", "Сіз ePharm-ға кірмедіңіз",
                "Войдите в окне входа ePharm: оно открывается само в начале смены. Если вы ещё не зарегистрированы, зарегистрируйтесь там же.",
                "Ауысым басында өзі ашылатын ePharm кіру терезесінде кіріңіз. Әлі тіркелмеген болсаңыз, сол жерде тіркеліңіз.");
        }

        private static TillStatusRow Version(TillStatusSnapshot s) =>
            new("version", TillStatusLevel.Ok, "Версия ePharm", "ePharm нұсқасы",
                $"{s.Version}, обновляется сама", $"{s.Version}, өзі жаңартылады");

        // Times are shown on the till's own clock, the one the pharmacist sees on the taskbar.
        private static string Time(DateTimeOffset at, TimeSpan offset) =>
            at.ToOffset(offset).ToString("HH:mm", CultureInfo.InvariantCulture);
    }
}
