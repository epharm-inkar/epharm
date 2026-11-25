using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Effects;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using QRCoder;

namespace CustomerDisplay
{
    /// <summary>A task from HQ (a course to take): what to do, by when, what the person gets, and its status.</summary>
    public sealed record StaffSelfTask(string Title, string Instructions, long? RewardKzt, string Status, int ProgressPct, DateTimeOffset? DueAt);

    /// <summary>One credited recommendation in the pharmacist's history.</summary>
    public sealed record StaffSelfReward(string Kind, string Product, long BonusKzt, DateTimeOffset At);

    /// <summary>
    /// What the ePharm tray window shows about the signed-in-by-Standard-N pharmacist. No password is needed to
    /// open it: the person is already known from Standard-N. The server fills this by IIN (device-authenticated).
    /// </summary>
    public sealed record StaffSelfModel
    {
        public string FullName { get; init; } = "";
        public string Iin { get; init; } = "";
        public string Pharmacy { get; init; } = "";
        public long EarnedMonthKzt { get; init; }
        public long BalanceKzt { get; init; }
        public IReadOnlyList<StaffSelfReward> Recent { get; init; } = Array.Empty<StaffSelfReward>();
        public bool PhoneBound { get; init; }
        public bool PasswordSet { get; init; }
        /// <summary>One-time, device-issued cabinet link (opens /learn already signed in). Minutes-lived.</summary>
        public string? CabinetUrl { get; init; }
        /// <summary>Shown instead of the profile when the person is not known yet (no Standard-N sign-in, no IIN).</summary>
        public string? Notice { get; init; }
        /// <summary>Pharmacy tasks from the CRM (merchandising): null when this till does not receive them.</summary>
        public int? TasksCount { get; init; }
        public bool TasksOnline { get; init; } = true;
        /// <summary>Tasks from HQ (courses), newest first; done ones stay visible for two weeks.</summary>
        public IReadOnlyList<StaffSelfTask> HqTasks { get; init; } = Array.Empty<StaffSelfTask>();
    }

    /// <summary>
    /// Личное окно ePharm на кассе (значок у часов). Показывает фармацевту, что система знает, кто он, сколько он
    /// заработал и что ещё стоит заполнить (телефон и пароль — с красным огоньком, пока не привязано). Входа и
    /// пароля для самого окна не требуется: личность берётся из Стандарт-Н. Кнопка «Мой кабинет» и QR открывают
    /// веб-кабинет уже с входом. Построено в коде (без .xaml), как ConflictNotificationWindow, чтобы остаться
    /// аддитивным. Окно двигается и закрывается, кассу не блокирует.
    /// </summary>
    public sealed class StaffSelfWindow : Window
    {
        private static readonly CultureInfo Kz = CultureInfo.GetCultureInfo("ru-RU");
        /// <summary>«Мой кабинет»: the host asks the server for a fresh one-time link and opens the browser.</summary>
        public event Action? OpenCabinetRequested;
        public event Action<string?, string?>? SaveContactRequested;
        /// <summary>«Задания аптеки»: the host opens the tasks window (the former tray icon of its own).</summary>
        public event Action? OpenTasksRequested;
        private Border? _help;
        private TextBlock? _tasksText;

        private readonly TextBox _phone = NewField("+7 ___ ___ __ __");
        private readonly PasswordBox _password = NewPassword();
        private readonly PasswordBox _passwordRepeat = NewPassword();
        /// <summary>«Сменить пароль» pressed: the two fields show although a password is already set.</summary>
        private bool _changingPassword;

        private static PasswordBox NewPassword() => new()
        {
            FontSize = 15,
            Padding = new Thickness(11, 9, 11, 9),
            BorderThickness = new Thickness(1),
            BorderBrush = Brush("#D7DBE0"),
        };

        public StaffSelfWindow(StaffSelfModel model, System.Windows.Forms.Screen? targetScreen = null)
        {
            WindowStyle = WindowStyle.None;
            ResizeMode = ResizeMode.NoResize;
            Topmost = true;
            ShowInTaskbar = false;
            AllowsTransparency = true;
            Background = Brushes.Transparent;
            SizeToContent = SizeToContent.Height;
            Width = 400;
            Title = "ePharm: личный кабинет";
            Content = BuildCard(model);
            Model = model;
            MouseLeftButtonDown += (_, e) => { if (e.ButtonState == MouseButtonState.Pressed) DragMove(); };
            KeyDown += (_, e) => { if (e.Key == Key.Escape) Close(); };
            if (targetScreen != null)
            {
                Loaded += (_, _) =>
                {
                    var area = targetScreen.WorkingArea;
                    var dpi = VisualTreeHelper.GetDpi(this);
                    Left = area.Left / dpi.DpiScaleX + 24;
                    Top = area.Top / dpi.DpiScaleY + 24;
                    // Never taller than the screen: the card scrolls (the «?» text makes it long on 768-pixel tills).
                    MaxHeight = Math.Max(320, area.Height / dpi.DpiScaleY - 48);
                };
            }
        }

        public StaffSelfModel Model { get; private set; }

        /// <summary>Replaces what the window shows (after saving the profile or a periodic refresh).</summary>
        public void Update(StaffSelfModel model)
        {
            Model = model;
            Content = BuildCard(model);
        }

        /// <summary>Shows a short status line under the header (saved, error) without rebuilding the window.</summary>
        public void ShowStatus(string text, bool error)
        {
            if (_status == null) return;
            _status.Text = text;
            _status.Foreground = Brush(error ? "#B23127" : "#1F6B57");
            _status.Visibility = string.IsNullOrWhiteSpace(text) ? Visibility.Collapsed : Visibility.Visible;
        }

        private TextBlock? _status;

        private UIElement BuildCard(StaffSelfModel model)
        {
            var card = new Border
            {
                CornerRadius = new CornerRadius(16),
                Background = Brushes.White,
                Margin = new Thickness(12),
                Effect = new DropShadowEffect { Color = Colors.Black, Opacity = 0.26, BlurRadius = 26, ShadowDepth = 5 },
            };
            var root = new StackPanel();
            var body = new StackPanel();
            root.Children.Add(BuildHeader(model));
            root.Children.Add(new ScrollViewer
            {
                Content = body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
                MaxHeight = Math.Max(240, (MaxHeight is > 0 and < double.PositiveInfinity ? MaxHeight : SystemParameters.WorkArea.Height - 48) - 130),
            });
            _help = BuildHelp();
            _help.Visibility = _helpOpen ? Visibility.Visible : Visibility.Collapsed;
            body.Children.Add(_help);
            _status = new TextBlock { FontSize = 13, Margin = new Thickness(16, 10, 16, 0), TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
            body.Children.Add(_status);
            if (!string.IsNullOrWhiteSpace(model.Notice) && model.FullName.Length == 0)
            {
                body.Children.Add(new Border
                {
                    Margin = new Thickness(16, 14, 16, 16), CornerRadius = new CornerRadius(12), Padding = new Thickness(14, 12, 14, 13),
                    Background = Brush("#F4F6F7"),
                    Child = new TextBlock { Text = model.Notice, Foreground = Brush("#374151"), FontSize = 14, TextWrapping = TextWrapping.Wrap },
                });
                if (model.TasksCount != null) body.Children.Add(BuildTasks(model));
                card.Child = root;
                return card;
            }
            body.Children.Add(BuildEarnings(model));
            if (model.HqTasks.Count > 0) body.Children.Add(BuildHqTasks(model));
            if (model.TasksCount != null) body.Children.Add(BuildTasks(model));
            if (model.Recent.Count > 0) body.Children.Add(BuildHistory(model));
            body.Children.Add(BuildContact(model));
            body.Children.Add(BuildCabinet(model));
            card.Child = root;
            return card;
        }

        private UIElement BuildHeader(StaffSelfModel model)
        {
            var header = new Border
            {
                Background = Brush("#112B33"),
                CornerRadius = new CornerRadius(16, 16, 0, 0),
                Padding = new Thickness(18, 15, 12, 15),
            };
            var grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var left = new StackPanel();
            left.Children.Add(new TextBlock { Text = "ePharm", Foreground = Brushes.White, FontSize = 13, FontWeight = FontWeights.Bold, Opacity = 0.7 });
            left.Children.Add(new TextBlock
            {
                Text = RecommendationWindow.ShortName(model.FullName) is { Length: > 0 } n ? n : "Сотрудник аптеки",
                Foreground = Brushes.White, FontSize = 22, FontWeight = FontWeights.Bold, Margin = new Thickness(0, 2, 0, 0), TextWrapping = TextWrapping.Wrap,
            });
            var sub = new List<string>();
            if (model.Iin.Length > 0)
                sub.Add("ИИН " + (model.Iin.Contains('•') || model.Iin.Length != 12 ? model.Iin : model.Iin[..6] + "•••••" + model.Iin[^1..]));
            if (model.Pharmacy.Length > 0) sub.Add(model.Pharmacy);
            if (sub.Count > 0)
                left.Children.Add(new TextBlock { Text = string.Join("  ·  ", sub), Foreground = Brush("#8FB3AE"), FontSize = 12, Margin = new Thickness(0, 4, 0, 0), TextWrapping = TextWrapping.Wrap });
            Grid.SetColumn(left, 0);

            var close = new Button
            {
                Content = "×", Width = 34, Height = 34, FontSize = 22, FontWeight = FontWeights.Light,
                Foreground = Brushes.White, Background = Brush("#1E3A42"), BorderThickness = new Thickness(0),
                Cursor = Cursors.Hand, VerticalAlignment = VerticalAlignment.Top,
            };
            close.Click += (_, _) => Close();
            var help = new Button
            {
                Content = "?", Width = 34, Height = 34, FontSize = 18, FontWeight = FontWeights.Bold, Margin = new Thickness(0, 0, 8, 0),
                Foreground = Brushes.White, Background = Brush("#1E3A42"), BorderThickness = new Thickness(0),
                Cursor = Cursors.Hand, VerticalAlignment = VerticalAlignment.Top, ToolTip = "Что такое ePharm и как начисляются деньги",
            };
            help.Click += (_, _) => ToggleHelp();
            var buttons = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Top };
            buttons.Children.Add(help);
            buttons.Children.Add(close);
            Grid.SetColumn(buttons, 1);

            grid.Children.Add(left);
            grid.Children.Add(buttons);
            header.Child = grid;
            return header;
        }

        /// <summary>Opens or closes the «?» panel; the window keeps its place.</summary>
        public void ToggleHelp()
        {
            _helpOpen = !_helpOpen;
            if (_help != null) _help.Visibility = _helpOpen ? Visibility.Visible : Visibility.Collapsed;
        }

        private bool _helpOpen;

        private static Border BuildHelp()
        {
            var stack = new StackPanel();
            stack.Children.Add(new TextBlock
            {
                Text = "Что такое ePharm", Foreground = Brush("#112B33"), FontSize = 15, FontWeight = FontWeights.Bold, Margin = new Thickness(0, 0, 0, 8),
            });
            foreach (var line in HelpLines)
            {
                var row = new Grid { Margin = new Thickness(0, 0, 0, 7) };
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                row.Children.Add(new TextBlock { Text = "•", Foreground = Brush("#1F8A70"), FontSize = 14, FontWeight = FontWeights.Bold });
                var text = new TextBlock
                {
                    Text = line, Foreground = Brush("#374151"), FontSize = 13, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(8, 0, 0, 0),
                };
                Grid.SetColumn(text, 1);
                row.Children.Add(text);
                stack.Children.Add(row);
            }
            return new Border
            {
                Margin = new Thickness(16, 12, 16, 0), CornerRadius = new CornerRadius(12), Padding = new Thickness(14, 12, 14, 6),
                Background = Brush("#EEF7F3"), BorderBrush = Brush("#BFE3D6"), BorderThickness = new Thickness(1),
                Child = stack, Visibility = Visibility.Collapsed,
            };
        }

        /// <summary>The «?» text. Plain facts only: what shows up, who gets the money and when.</summary>
        internal static readonly string[] HelpLines =
        {
            "ePharm подсказывает на кассе, на какой товар партнёра можно заменить то, что просит покупатель. Подсказка появляется сама, когда вы сканируете товар.",
            "Регистрироваться не нужно: ePharm узнаёт вас по входу в Стандарт-Н.",
            "За каждую упаковку, проданную по подсказке, вам начисляется бонус. Сумма видна в самой подсказке.",
            "Когда начисляется: касса отправляет чек на сервер сразу после продажи, бонус появляется здесь через несколько минут. Если продажу нужно проверить, бонус появится после проверки.",
            "Выплата: начисления копятся за месяц («Заработано за месяц»), в начале следующего месяца по ним составляется ведомость и выплачиваются деньги.",
            "Задания: просьбы от HQ, например пройти курс. Новое задание отмечается красной точкой на значке ePharm.",
            "Курсы и все начисления: кнопка «Мой кабинет» или QR для телефона.",
        };

        private UIElement BuildTasks(StaffSelfModel model)
        {
            var count = model.TasksCount ?? 0;
            var row = new Grid { Margin = new Thickness(16, 10, 16, 0) };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            text.Children.Add(new TextBlock { Text = model.HqTasks.Count > 0 ? "Задания по выкладке" : "Задания", Foreground = Brush("#6B7280"), FontSize = 12 });
            _tasksText = new TextBlock { FontSize = 15, FontWeight = FontWeights.SemiBold };
            ShowTasks(count, model.TasksOnline);
            text.Children.Add(_tasksText);
            row.Children.Add(text);
            var open = new Button
            {
                Content = "Открыть", Padding = new Thickness(14, 6, 14, 6), FontSize = 13, FontWeight = FontWeights.SemiBold,
                Foreground = Brush("#112B33"), Background = Brush("#E7EDEF"), BorderThickness = new Thickness(0), Cursor = Cursors.Hand,
                VerticalAlignment = VerticalAlignment.Center,
            };
            open.Click += (_, _) => OpenTasksRequested?.Invoke();
            Grid.SetColumn(open, 1);
            row.Children.Add(open);
            return new Border
            {
                Margin = new Thickness(0), Padding = new Thickness(0), Child = row,
            };
        }

        private UIElement BuildHqTasks(StaffSelfModel model)
        {
            var stack = new StackPanel { Margin = new Thickness(16, 10, 16, 0) };
            stack.Children.Add(new TextBlock { Text = "МОИ ЗАДАНИЯ", Foreground = Brush("#9AA2AE"), FontSize = 11, FontWeight = FontWeights.Bold, Margin = new Thickness(0, 0, 0, 6) });
            foreach (var task in model.HqTasks.Take(4))
            {
                var box = new Border
                {
                    CornerRadius = new CornerRadius(10), Padding = new Thickness(11, 8, 11, 9), Margin = new Thickness(0, 0, 0, 6),
                    Background = Brush(task.Status == "done" ? "#F4F6F7" : "#FFFFFF"), BorderBrush = Brush("#DDE3E6"), BorderThickness = new Thickness(1),
                };
                var inner = new StackPanel();
                var head = new Grid();
                head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                head.Children.Add(new TextBlock { Text = task.Title, Foreground = Brush("#171A2F"), FontSize = 14, FontWeight = FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 8, 0) });
                var (label, fg, bg) = TaskChip(task);
                var chip = new Border { CornerRadius = new CornerRadius(6), Padding = new Thickness(7, 1, 7, 2), Background = Brush(bg), VerticalAlignment = VerticalAlignment.Top, Child = new TextBlock { Text = label, Foreground = Brush(fg), FontSize = 11.5, FontWeight = FontWeights.Bold } };
                Grid.SetColumn(chip, 1);
                head.Children.Add(chip);
                inner.Children.Add(head);
                if (!string.IsNullOrWhiteSpace(task.Instructions) && task.Status != "done")
                    inner.Children.Add(new TextBlock { Text = task.Instructions, Foreground = Brush("#5E6B6D"), FontSize = 12.5, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 3, 0, 0) });
                var facts = new List<string>();
                if (task.DueAt is { } due && task.Status != "done") facts.Add("до " + due.ToLocalTime().ToString("dd.MM", Kz));
                if (task.RewardKzt is > 0) facts.Add("+" + Tenge(task.RewardKzt.Value) + " за выполнение");
                if (facts.Count > 0)
                    inner.Children.Add(new TextBlock { Text = string.Join("  ·  ", facts), Foreground = Brush("#1F6B57"), FontSize = 12, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 3, 0, 0) });
                box.Child = inner;
                stack.Children.Add(box);
            }
            if (model.HqTasks.Count > 4)
                stack.Children.Add(new TextBlock { Text = "Ещё " + (model.HqTasks.Count - 4) + " в кабинете", Foreground = Brush("#6B7280"), FontSize = 12, Margin = new Thickness(2, 0, 0, 4) });
            if (model.HqTasks.Any(t => t.Status != "done"))
            {
                var open = new Button
                {
                    Content = "Пройти в кабинете", Height = 36, FontSize = 13.5, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 2, 0, 0),
                    Foreground = Brushes.White, Background = Brush("#1F8A70"), BorderThickness = new Thickness(0), Cursor = Cursors.Hand,
                };
                open.Click += (_, _) => OpenCabinetRequested?.Invoke();
                stack.Children.Add(open);
            }
            return stack;
        }

        internal static (string Label, string Foreground, string Background) TaskChip(StaffSelfTask task) => task.Status switch
        {
            "done" => ("Выполнено ✓", "#1F6B57", "#DFF3EA"),
            "overdue" => ("Просрочено", "#FFFFFF", "#B23127"),
            "in_progress" => (task.ProgressPct > 0 ? $"В процессе · {task.ProgressPct}%" : "В процессе", "#7A4B00", "#FFF1D6"),
            _ => ("Новое", "#FFFFFF", "#E0392B"),
        };

        /// <summary>The task feed changed: only the tasks line changes, typed phone or password stay as they are.</summary>
        public void UpdateTasks(int? count, bool online)
        {
            Model = Model with { TasksCount = count, TasksOnline = online };
            if (count != null) ShowTasks(count.Value, online);
        }

        private void ShowTasks(int count, bool online)
        {
            if (_tasksText == null) return;
            _tasksText.Text = !online ? "Нет связи, повторим позже" : count == 0 ? "Новых заданий нет" : count + " " + Plural(count, "задание", "задания", "заданий");
            _tasksText.Foreground = Brush(count > 0 && online ? "#B23127" : "#171A2F");
        }

        /// <summary>After a successful save: the typed values are on the server, the inputs start empty.</summary>
        public void ClearContactInputs()
        {
            _phone.Text = "";
            _password.Password = "";
            _passwordRepeat.Password = "";
            _changingPassword = false;
        }

        private static string Plural(int n, string one, string few, string many)
        {
            var mod100 = n % 100; var mod10 = n % 10;
            if (mod100 is >= 11 and <= 14) return many;
            return mod10 switch { 1 => one, >= 2 and <= 4 => few, _ => many };
        }

        private UIElement BuildEarnings(StaffSelfModel model)
        {
            var row = new Grid { Margin = new Thickness(16, 14, 16, 4) };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.Children.Add(Metric("Заработано за месяц", Tenge(model.EarnedMonthKzt), "#1F8A70", 0));
            row.Children.Add(Metric("На балансе", Tenge(model.BalanceKzt), "#171A2F", 1));
            return row;
        }

        private static UIElement Metric(string label, string value, string valueHex, int column)
        {
            var box = new Border
            {
                Background = Brush("#F4F6F7"), CornerRadius = new CornerRadius(12), Padding = new Thickness(14, 11, 14, 12),
                Margin = new Thickness(column == 0 ? 0 : 5, 0, column == 0 ? 5 : 0, 0),
            };
            var stack = new StackPanel();
            stack.Children.Add(new TextBlock { Text = label, Foreground = Brush("#70788A"), FontSize = 12 });
            stack.Children.Add(new TextBlock { Text = value, Foreground = Brush(valueHex), FontSize = 22, FontWeight = FontWeights.Bold, Margin = new Thickness(0, 3, 0, 0) });
            box.Child = stack;
            Grid.SetColumn(box, column);
            return box;
        }

        private UIElement BuildHistory(StaffSelfModel model)
        {
            var stack = new StackPanel { Margin = new Thickness(16, 10, 16, 2) };
            stack.Children.Add(new TextBlock { Text = "ПОСЛЕДНИЕ НАЧИСЛЕНИЯ", Foreground = Brush("#9AA2AE"), FontSize = 11, FontWeight = FontWeights.Bold, Margin = new Thickness(0, 0, 0, 6) });
            foreach (var r in model.Recent)
            {
                var g = new Grid { Margin = new Thickness(0, 3, 0, 3) };
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var left = new StackPanel();
                left.Children.Add(new TextBlock { Text = r.Product, Foreground = Brush("#171A2F"), FontSize = 14, TextWrapping = TextWrapping.Wrap });
                left.Children.Add(new TextBlock
                {
                    Text = (r.Kind == "substitution" ? "Замена" : "Допродажа") + "  ·  " + r.At.ToString("dd.MM", Kz),
                    Foreground = Brush("#8A909C"), FontSize = 12, Margin = new Thickness(0, 1, 0, 0),
                });
                Grid.SetColumn(left, 0);
                var amount = new TextBlock
                {
                    Text = "+" + Tenge(r.BonusKzt), Foreground = Brush("#1F8A70"), FontSize = 15, FontWeight = FontWeights.Bold,
                    VerticalAlignment = VerticalAlignment.Center,
                };
                Grid.SetColumn(amount, 1);
                g.Children.Add(left);
                g.Children.Add(amount);
                stack.Children.Add(g);
            }
            return stack;
        }

        private UIElement BuildContact(StaffSelfModel model)
        {
            var complete = model.PhoneBound && model.PasswordSet;
            var box = new Border
            {
                Margin = new Thickness(16, 12, 16, 6), CornerRadius = new CornerRadius(12), Padding = new Thickness(14, 12, 14, 13),
                Background = Brush(complete ? "#EEF7F3" : "#FFF6F5"),
                BorderBrush = Brush(complete ? "#BFE3D6" : "#F6C9C4"), BorderThickness = new Thickness(1),
            };
            var stack = new StackPanel();
            var head = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 9) };
            head.Children.Add(new Ellipse { Width = 9, Height = 9, Fill = Brush(complete ? "#1F8A70" : "#E0392B"), VerticalAlignment = VerticalAlignment.Center });
            head.Children.Add(new TextBlock
            {
                Text = complete ? "Профиль заполнен" : "Заполните профиль",
                Foreground = Brush(complete ? "#1F6B57" : "#B23127"), FontSize = 14, FontWeight = FontWeights.Bold,
                Margin = new Thickness(8, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center,
            });
            stack.Children.Add(head);
            stack.Children.Add(new TextBlock
            {
                Text = complete ? "С этим номером и паролем можно войти в кабинет с телефона или из дома: epharm.inkar.kz/learn"
                                : "Укажите телефон и пароль: с ними можно войти в кабинет с телефона или из дома.",
                Foreground = Brush("#6B7280"), FontSize = 12, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 10),
            });

            // The inputs live across rebuilds (Update): take them out of the previous tree first.
            Detach(_phone);
            Detach(_password);
            Detach(_passwordRepeat);
            if (model.PhoneBound) stack.Children.Add(DoneLine("Номер телефона привязан"));
            else
            {
                stack.Children.Add(FieldLabel("Номер телефона"));
                stack.Children.Add(_phone);
            }
            var askPassword = !model.PasswordSet || _changingPassword;
            if (model.PasswordSet)
            {
                var line = new Grid();
                line.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                line.Children.Add(DoneLine("Пароль задан"));
                if (!_changingPassword)
                {
                    var change = new Button
                    {
                        Content = "Сменить пароль", Padding = new Thickness(10, 3, 10, 4), FontSize = 12.5, FontWeight = FontWeights.SemiBold,
                        Foreground = Brush("#112B33"), Background = Brush("#E7EDEF"), BorderThickness = new Thickness(0),
                        Cursor = Cursors.Hand, VerticalAlignment = VerticalAlignment.Top,
                    };
                    change.Click += (_, _) => BeginPasswordChange();
                    Grid.SetColumn(change, 1);
                    line.Children.Add(change);
                }
                stack.Children.Add(line);
            }
            if (askPassword)
            {
                stack.Children.Add(FieldLabel(model.PasswordSet ? "Новый пароль" : "Пароль для входа"));
                stack.Children.Add(_password);
                var repeat = FieldLabel("Повторите пароль");
                repeat.Margin = new Thickness(0, 8, 0, 4);
                stack.Children.Add(repeat);
                stack.Children.Add(_passwordRepeat);
            }
            if (model.PhoneBound && !askPassword)
            {
                box.Child = stack;
                return box;
            }

            var save = new Button
            {
                Content = "Сохранить", Margin = new Thickness(0, 11, 0, 0), Height = 42, FontSize = 15, FontWeight = FontWeights.SemiBold,
                Foreground = Brushes.White, Background = Brush("#1F8A70"), BorderThickness = new Thickness(0), Cursor = Cursors.Hand,
            };
            save.Click += (_, _) =>
            {
                string? password = null;
                if (askPassword && (_password.Password.Length > 0 || _passwordRepeat.Password.Length > 0))
                {
                    if (_password.Password != _passwordRepeat.Password) { ShowStatus("Пароли не совпадают. Введите одинаковый пароль два раза.", error: true); return; }
                    if (_password.Password.Length < MinPasswordLength) { ShowStatus($"Пароль слишком короткий: нужно хотя бы {MinPasswordLength} знака.", error: true); return; }
                    password = _password.Password;
                }
                SaveContactRequested?.Invoke(model.PhoneBound ? null : _phone.Text?.Trim(), password);
            };
            stack.Children.Add(save);
            box.Child = stack;
            return box;
        }

        private UIElement BuildCabinet(StaffSelfModel model)
        {
            var box = new StackPanel { Margin = new Thickness(16, 2, 16, 16) };
            var open = new Button
            {
                Content = "Мой кабинет: обучение и бонусы", Height = 44, FontSize = 15, FontWeight = FontWeights.SemiBold,
                Foreground = Brush("#112B33"), Background = Brush("#E7EDEF"), BorderThickness = new Thickness(0), Cursor = Cursors.Hand,
            };
            open.Click += (_, _) => OpenCabinetRequested?.Invoke();
            box.Children.Add(open);

            if (model.CabinetUrl is { Length: > 0 } cabinet)
            {
                var row = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 12, 0, 0) };
                row.Children.Add(new Image { Source = Qr(cabinet), Width = 92, Height = 92, VerticalAlignment = VerticalAlignment.Center });
                row.Children.Add(new TextBlock
                {
                    Text = "Наведите телефон на QR,\nчтобы открыть кабинет\nи курсы на телефоне.",
                    Foreground = Brush("#6B7280"), FontSize = 13, Margin = new Thickness(12, 0, 0, 0),
                    VerticalAlignment = VerticalAlignment.Center, TextWrapping = TextWrapping.Wrap,
                });
                box.Children.Add(row);
            }
            return box;
        }

        /// <summary>«Сменить пароль»: two fields for the new password (the server replaces the old one, 1.0.96).</summary>
        public void BeginPasswordChange()
        {
            _changingPassword = true;
            Update(Model);
        }

        /// <summary>Same minimum as the server and the cabinet (four characters, #148).</summary>
        internal const int MinPasswordLength = 4;

        private static TextBlock FieldLabel(string text) => new()
        {
            Text = text, Foreground = Brush("#70788A"), FontSize = 12, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 0, 0, 4),
        };

        private static void Detach(FrameworkElement element)
        {
            if (element.Parent is Panel panel) panel.Children.Remove(element);
        }

        /** A detail the person already has: shown with a green tick, not editable from the till. */
        private static UIElement DoneLine(string text)
        {
            var row = new Grid { Margin = new Thickness(0, 2, 0, 8) };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var tick = new TextBlock
            {
                Text = "✓", Foreground = Brush("#1F8A70"), FontSize = 15, FontWeight = FontWeights.Bold,
                VerticalAlignment = VerticalAlignment.Top,
            };
            var label = new TextBlock
            {
                Text = text, Foreground = Brush("#1F6B57"), FontSize = 14, Margin = new Thickness(8, 0, 0, 0),
                VerticalAlignment = VerticalAlignment.Top, TextWrapping = TextWrapping.Wrap,
            };
            Grid.SetColumn(label, 1);
            row.Children.Add(tick);
            row.Children.Add(label);
            return row;
        }

        private static TextBox NewField(string placeholder) => new()
        {
            FontSize = 15, Padding = new Thickness(11, 9, 11, 9), BorderThickness = new Thickness(1), BorderBrush = Brush("#D7DBE0"), Tag = placeholder,
        };

        private static ImageSource Qr(string payload)
        {
            using var generator = new QRCodeGenerator();
            using var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.Q);
            using var png = new PngByteQRCode(data);
            using var stream = new MemoryStream(png.GetGraphic(6));
            var bitmap = new BitmapImage();
            bitmap.BeginInit();
            bitmap.CacheOption = BitmapCacheOption.OnLoad;
            bitmap.StreamSource = stream;
            bitmap.EndInit();
            bitmap.Freeze();
            return bitmap;
        }

        private static string Tenge(long value) => value.ToString("#,0", Kz).Replace(',', ' ') + " ₸";
        private static Brush Brush(string hex) => (Brush)new BrushConverter().ConvertFrom(hex)!;
    }
}
