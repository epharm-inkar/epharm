using System;
using System.Collections.Generic;
using System.Linq;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// One block of an ePharm page: a heading, short paragraphs (as a list when <paramref name="Bullets"/>), an optional
    /// line under the heading and an optional red mark (for example «Новое»).
    /// </summary>
    public sealed record EpharmPageSection(
        string Title, IReadOnlyList<string> Lines, string? Subtitle = null, string? Badge = null, bool Bullets = true);

    /// <summary>
    /// «Что такое ePharm» (1.0.99, Grigoriy 25.11.2026, instead of the small «?»): what ePharm is, how to use it at the
    /// till, how a pharmacist earns with it and where to ask. Only what the product does today: no numbers, no promises.
    /// </summary>
    public static class EpharmHelpContent
    {
        public const string PageTitle = "Что такое ePharm";

        public const string Lead =
            "ePharm помогает фармацевту на кассе: подсказывает, что предложить покупателю, даёт курсы и показывает ваши начисления.";

        public static readonly IReadOnlyList<EpharmPageSection> Sections = new[]
        {
            new EpharmPageSection("Что это", new[]
            {
                "ePharm работает рядом со Стандарт-Н на этом компьютере. Регистрироваться не нужно: ePharm узнаёт вас по входу в Стандарт-Н.",
                "Значок ePharm у часов внизу экрана открывает ваше окно: имя, начисления, задания и уведомления.",
            }),
            new EpharmPageSection("Как пользоваться на кассе", new[]
            {
                "Сканируйте товар как обычно. Если есть товар, который стоит предложить покупателю, справа появится подсказка: что предложить и почему.",
                "Подсказка не мешает работе: Стандарт-Н остаётся активным, подсказка закрывается сама.",
                "Красная точка на значке ePharm: есть новое задание, непройденный обязательный курс или уведомление. Нажмите на значок, чтобы посмотреть.",
                "Уведомления от офиса появляются в углу экрана. Прочитайте и закройте крестиком, прочитанные хранятся в окне ePharm.",
            }),
            new EpharmPageSection("«Мой кабинет» и курсы", new[]
            {
                "Кнопка «Мой кабинет» в окне ePharm открывает кабинет в браузере, уже с входом. QR в окне открывает его на телефоне.",
                "Курсы в «Мой кабинет», раздел «Обучение». Задания от офиса со сроком видны в окне ePharm.",
                "Чтобы входить в кабинет с телефона или из дома, задайте в окне ePharm номер телефона и пароль. Адрес кабинета: epharm.inkar.kz/learn.",
            }),
            new EpharmPageSection("Как вы зарабатываете", new[]
            {
                "Если за товар из подсказки положен бонус, его сумма видна прямо в подсказке, на зелёной плашке. Подсказка без бонуса помечена «без бонуса».",
                "Бонус засчитывается за упаковку, проданную по подсказке в том же чеке. Начисленное видно в окне ePharm: «Заработано за месяц» и «Последние начисления».",
                "Если за курс положен бонус, его сумма указана в задании. Он начисляется после прохождения курса.",
                "Выплата: начисления за месяц выплачиваются по ведомости в начале следующего месяца.",
            }),
            new EpharmPageSection("Где спросить помощь", new[]
            {
                "Если ePharm не узнаёт вас, не показывает подсказки или что-то непонятно, сообщите МАС вашей аптеки: вопрос передадут в поддержку ePharm.",
                "«Не нашли вас в справочнике Стандарт-Н»: попросите МАС заполнить ИИН в вашей карточке пользователя Стандарт-Н.",
                "Жёлтая точка на значке: в ePharm идут технические работы. Работайте как обычно, касса продаёт и без ePharm.",
            }),
        };

        /// <summary>Every line a pharmacist reads on the page (for the text checks).</summary>
        public static IEnumerable<string> AllTexts() =>
            new[] { PageTitle, Lead }.Concat(Sections.SelectMany(s => new[] { s.Title }.Concat(s.Lines)));
    }
}
