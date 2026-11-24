using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Effects;
using CustomerDisplay.Services;

namespace CustomerDisplay
{
    /// <summary>
    /// «Состояние ePharm»: one screen that tells the pharmacist whether ePharm works on this till, what is wrong
    /// and what to do, in Russian or Kazakh, plus the latest notice from HQ. Opened from the ePharm tray icon
    /// (and once for every new HQ notice). Never blocks the till: no modal state, Escape or «Закрыть» closes it.
    /// Built in code like the other till windows so the XAML build stays untouched.
    /// </summary>
    public sealed class TillStatusWindow : Window
    {
        private readonly Func<TillStatusSnapshot> _snapshot;
        private readonly System.Windows.Forms.Screen? _targetScreen;
        private readonly StackPanel _body = new();
        private bool _kazakh;

        public event Action<bool>? LanguageChanged;
        public bool IsKazakh => _kazakh;

        public TillStatusWindow(Func<TillStatusSnapshot> snapshot, bool kazakh = false, System.Windows.Forms.Screen? targetScreen = null)
        {
            _snapshot = snapshot ?? throw new ArgumentNullException(nameof(snapshot));
            _targetScreen = targetScreen;
            _kazakh = kazakh;

            WindowStyle = WindowStyle.None;
            ResizeMode = ResizeMode.NoResize;
            AllowsTransparency = true;
            Background = Brushes.Transparent;
            Topmost = true;
            ShowInTaskbar = true;
            SizeToContent = SizeToContent.Height;
            Width = 560;
            Title = "Состояние ePharm";

            Content = new Border
            {
                CornerRadius = new CornerRadius(14),
                Background = Brushes.White,
                Margin = new Thickness(12),
                BorderBrush = Brush("#D0D7DE"),
                BorderThickness = new Thickness(1),
                Effect = new DropShadowEffect { Color = Colors.Black, Opacity = 0.22, BlurRadius = 24, ShadowDepth = 4 },
                Child = _body,
            };
            KeyDown += (_, e) => { if (e.Key == Key.Escape) Close(); };
            MouseLeftButtonDown += (_, e) => { if (e.ButtonState == MouseButtonState.Pressed) try { DragMove(); } catch (InvalidOperationException) { } };
            Loaded += (_, _) => PlaceOnScreen();
            Refresh();
        }

        /// <summary>Re-reads the till state (MainWindow calls this every few seconds while the window is open).</summary>
        public void Refresh()
        {
            var snapshot = _snapshot();
            var rows = TillStatusText.Rows(snapshot);
            var overall = TillStatusText.Overall(rows);
            _body.Children.Clear();
            _body.Children.Add(Header(snapshot));
            _body.Children.Add(Headline(overall));
            if (snapshot.Notice is { IsValid: true } notice) _body.Children.Add(NoticeBanner(notice));
            var list = new StackPanel { Margin = new Thickness(18, 4, 18, 6) };
            foreach (var row in rows) list.Children.Add(Row(row));
            _body.Children.Add(list);
            _body.Children.Add(Footer(snapshot));
        }

        private string T(string ru, string kk) => _kazakh ? kk : ru;

        private UIElement Header(TillStatusSnapshot snapshot)
        {
            var grid = new Grid { Margin = new Thickness(18, 14, 12, 4) };
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var titles = new StackPanel();
            titles.Children.Add(Text(T("Состояние ePharm", "ePharm күйі"), 20, "#1F2328", FontWeights.SemiBold));
            var place = string.IsNullOrWhiteSpace(snapshot.PharmacyName) ? null : snapshot.PharmacyName!.Trim();
            var till = string.IsNullOrWhiteSpace(snapshot.DeviceId) ? null : snapshot.DeviceId!.Trim();
            var where = string.Join(" · ", new[] { place, till == null ? null : T($"касса {till}", $"касса {till}") }.WhereNotNull());
            if (where.Length > 0) titles.Children.Add(Text(where, 13, "#656D76"));
            grid.Children.Add(titles);

            var actions = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Top };
            actions.Children.Add(LanguageButton("Рус", false));
            actions.Children.Add(LanguageButton("Қаз", true));
            var close = new Button
            {
                Content = "✕", Width = 34, Height = 30, Margin = new Thickness(8, 0, 0, 0), FontSize = 15,
                Background = Brushes.Transparent, BorderThickness = new Thickness(0), Cursor = Cursors.Hand,
                Foreground = Brush("#656D76"),
            };
            AutomationProperties.SetName(close, T("Закрыть", "Жабу"));
            close.Click += (_, _) => Close();
            actions.Children.Add(close);
            Grid.SetColumn(actions, 1);
            grid.Children.Add(actions);
            return grid;
        }

        private Button LanguageButton(string caption, bool kazakh)
        {
            var active = _kazakh == kazakh;
            var button = new Button
            {
                Content = caption, MinWidth = 44, Height = 28, Margin = new Thickness(4, 0, 0, 0), FontSize = 13,
                Background = active ? Brush("#0B6BCB") : Brushes.White, Foreground = active ? Brushes.White : Brush("#1F2328"),
                BorderBrush = Brush("#D0D7DE"), BorderThickness = new Thickness(1), Cursor = Cursors.Hand,
            };
            AutomationProperties.SetName(button, kazakh ? "Қазақ тілі" : "Русский язык");
            button.Click += (_, _) =>
            {
                if (_kazakh == kazakh) return;
                _kazakh = kazakh;
                Title = T("Состояние ePharm", "ePharm күйі");
                Refresh();
                LanguageChanged?.Invoke(kazakh);
            };
            return button;
        }

        private UIElement Headline(TillStatusLevel overall)
        {
            var (fill, ink) = Palette(overall);
            var border = new Border
            {
                Background = Brush(fill), CornerRadius = new CornerRadius(10), Margin = new Thickness(18, 8, 18, 6),
                Padding = new Thickness(14, 10, 14, 10),
            };
            var panel = new StackPanel { Orientation = Orientation.Horizontal };
            panel.Children.Add(Dot(overall <= TillStatusLevel.Info ? TillStatusLevel.Ok : overall, 12));
            panel.Children.Add(Text(TillStatusText.Headline(overall, _kazakh), 15, ink, FontWeights.SemiBold, new Thickness(10, 0, 0, 0)));
            border.Child = panel;
            return border;
        }

        private UIElement NoticeBanner(TillNotice notice)
        {
            var border = new Border
            {
                Background = Brush(notice.IsWarning ? "#FFF8C5" : "#DDF4FF"),
                BorderBrush = Brush(notice.IsWarning ? "#D4A72C" : "#54AEFF"),
                BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(10),
                Margin = new Thickness(18, 2, 18, 6), Padding = new Thickness(14, 10, 14, 10),
            };
            var panel = new StackPanel();
            panel.Children.Add(Text(T("Сообщение от ePharm", "ePharm хабарламасы"), 12, "#656D76", FontWeights.SemiBold));
            panel.Children.Add(Text(notice.Text(_kazakh), 15, "#1F2328", null, new Thickness(0, 2, 0, 0)));
            border.Child = panel;
            return border;
        }

        private UIElement Row(TillStatusRow row)
        {
            var grid = new Grid { Margin = new Thickness(0, 8, 0, 8) };
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(26) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var dot = Dot(row.Level, 12);
            dot.VerticalAlignment = VerticalAlignment.Top;
            dot.Margin = new Thickness(2, 5, 0, 0);
            grid.Children.Add(dot);
            var text = new StackPanel();
            text.Children.Add(Text(T(row.TitleRu, row.TitleKk), 14, "#1F2328", FontWeights.SemiBold));
            text.Children.Add(Text(T(row.StateRu, row.StateKk), 14, row.Level == TillStatusLevel.Problem ? "#B42318" : "#1F2328"));
            var action = T(row.ActionRu ?? "", row.ActionKk ?? row.ActionRu ?? "");
            if (!string.IsNullOrWhiteSpace(action))
                text.Children.Add(Text(T("Что делать: ", "Не істеу керек: ") + action, 13, "#57606A", null, new Thickness(0, 3, 0, 0)));
            Grid.SetColumn(text, 1);
            grid.Children.Add(text);
            return grid;
        }

        private UIElement Footer(TillStatusSnapshot snapshot)
        {
            var code = TillStatusText.SupportCode(snapshot);
            var border = new Border
            {
                Background = Brush("#F6F8FA"), CornerRadius = new CornerRadius(0, 0, 14, 14),
                BorderBrush = Brush("#D0D7DE"), BorderThickness = new Thickness(0, 1, 0, 0), Padding = new Thickness(18, 12, 18, 14),
            };
            var grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var help = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            help.Children.Add(Text(T("Не получилось? Сообщите МАС вашей аптеки и назовите код кассы:",
                "Шешілмеді ме? Дәріханаңыздың МАС-ына хабарлап, касса кодын айтыңыз:"), 13, "#57606A"));
            help.Children.Add(Text(code, 15, "#1F2328", FontWeights.SemiBold, new Thickness(0, 2, 0, 0)));
            grid.Children.Add(help);
            var buttons = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            var copy = FooterButton(T("Скопировать код", "Кодты көшіру"), primary: false);
            copy.Click += (_, _) => { try { Clipboard.SetText(code); copy.Content = T("Скопировано", "Көшірілді"); } catch (Exception) { } };
            var ok = FooterButton(T("Закрыть", "Жабу"), primary: true);
            ok.Click += (_, _) => Close();
            buttons.Children.Add(copy);
            buttons.Children.Add(ok);
            Grid.SetColumn(buttons, 1);
            grid.Children.Add(buttons);
            border.Child = grid;
            return border;
        }

        private static Button FooterButton(string caption, bool primary)
        {
            var button = new Button
            {
                Content = caption, MinWidth = 110, Height = 34, Margin = new Thickness(8, 0, 0, 0), FontSize = 14,
                Padding = new Thickness(12, 0, 12, 0), Cursor = Cursors.Hand,
                Background = primary ? Brush("#0B6BCB") : Brushes.White, Foreground = primary ? Brushes.White : Brush("#1F2328"),
                BorderBrush = primary ? Brush("#0B6BCB") : Brush("#D0D7DE"), BorderThickness = new Thickness(1),
            };
            AutomationProperties.SetName(button, caption);
            return button;
        }

        private static (string Fill, string Ink) Palette(TillStatusLevel level) => level switch
        {
            TillStatusLevel.Problem => ("#FFEBE9", "#B42318"),
            TillStatusLevel.Warning => ("#FFF8C5", "#7D4E00"),
            _ => ("#DAFBE1", "#116329"),
        };

        private static FrameworkElement Dot(TillStatusLevel level, double size) => new System.Windows.Shapes.Ellipse
        {
            Width = size, Height = size,
            Fill = Brush(level switch
            {
                TillStatusLevel.Ok => "#1A7F37",
                TillStatusLevel.Warning => "#BF8700",
                TillStatusLevel.Problem => "#CF222E",
                _ => "#8C959F",
            }),
        };

        private static TextBlock Text(string value, double size, string color, FontWeight? weight = null, Thickness? margin = null) => new()
        {
            Text = value, FontSize = size, Foreground = Brush(color), FontWeight = weight ?? FontWeights.Normal,
            TextWrapping = TextWrapping.Wrap, Margin = margin ?? new Thickness(0),
        };

        private static SolidColorBrush Brush(string hex)
        {
            var brush = new SolidColorBrush((Color)ColorConverter.ConvertFromString(hex));
            brush.Freeze();
            return brush;
        }

        private void PlaceOnScreen()
        {
            var area = (_targetScreen ?? System.Windows.Forms.Screen.PrimaryScreen)?.WorkingArea;
            if (area is not { } a) return;
            var dpi = VisualTreeHelper.GetDpi(this);
            var left = a.Left / dpi.DpiScaleX;
            var top = a.Top / dpi.DpiScaleY;
            var width = a.Width / dpi.DpiScaleX;
            var height = a.Height / dpi.DpiScaleY;
            Left = left + Math.Max(0, (width - ActualWidth) / 2);
            Top = top + Math.Max(0, (height - ActualHeight) / 3);
        }
    }

    internal static class TillStatusWindowExtensions
    {
        public static IEnumerable<string> WhereNotNull(this IEnumerable<string?> values)
        {
            foreach (var value in values) if (!string.IsNullOrWhiteSpace(value)) yield return value!;
        }
    }
}
