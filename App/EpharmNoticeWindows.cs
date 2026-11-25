using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Threading;

namespace CustomerDisplay
{
    internal static class NoticeStyle
    {
        public const string Ink = "#112B33";
        public const string Teal = "#14866F";
        public const string Amber = "#E8A317";

        public static SolidColorBrush Brush(string hex)
        {
            var brush = (SolidColorBrush)new BrushConverter().ConvertFrom(hex)!;
            brush.Freeze();
            return brush;
        }

        public static TextBlock Text(string text, double size, string color, FontWeight? weight = null) => new()
        {
            Text = text,
            FontSize = size,
            Foreground = Brush(color),
            FontWeight = weight ?? FontWeights.Normal,
            TextWrapping = TextWrapping.Wrap,
            FontFamily = new FontFamily("Segoe UI"),
        };

        /// <summary>Round badge with a Segoe MDL2 glyph (wrench, check).</summary>
        public static Border Badge(string glyph, string background, double size) => new()
        {
            Width = size,
            Height = size,
            CornerRadius = new CornerRadius(size / 2),
            Background = Brush(background),
            Child = new TextBlock
            {
                Text = glyph,
                FontFamily = new FontFamily("Segoe MDL2 Assets"),
                FontSize = size * 0.46,
                Foreground = Brushes.White,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            },
        };

        public const string GlyphRepair = "";
        public const string GlyphCheck = "";
    }

    /// <summary>
    /// «ePharm на технических работах»: opens from the ePharm icon while HQ keeps the works on. Text comes from HQ.
    /// Movable, closes with «Понятно», Esc or ×; never blocks the till.
    /// </summary>
    public sealed class EpharmMaintenanceWindow : Window
    {
        private readonly TextBlock _title;
        private readonly TextBlock _message;

        public EpharmMaintenanceWindow(string? title, string? message, System.Windows.Forms.Screen? targetScreen = null)
        {
            WindowStyle = WindowStyle.None;
            ResizeMode = ResizeMode.NoResize;
            Topmost = true;
            ShowInTaskbar = false;
            AllowsTransparency = true;
            Background = Brushes.Transparent;
            SizeToContent = SizeToContent.Height;
            Width = 400;
            Title = "ePharm: технические работы";

            _title = NoticeStyle.Text("", 19, NoticeStyle.Ink, FontWeights.Bold);
            _message = NoticeStyle.Text("", 14.5, "#3A4752");
            _message.Margin = new Thickness(0, 8, 0, 0);
            _message.LineHeight = 21;
            Update(title, message);

            var close = new Button
            {
                Content = "✕", Width = 30, Height = 30, FontSize = 14, Background = Brushes.Transparent,
                BorderThickness = new Thickness(0), Foreground = Brushes.White, Cursor = Cursors.Hand,
                Focusable = false, ToolTip = "Закрыть",
            };
            close.Click += (_, _) => Close();
            var header = new Grid();
            header.ColumnDefinitions.Add(new ColumnDefinition());
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var brand = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(18, 12, 0, 12) };
            brand.Children.Add(new Image { Source = EpharmMark.Image(22), Width = 22, Height = 22 });
            var name = NoticeStyle.Text("ePharm", 15, "#FFFFFF", FontWeights.SemiBold);
            name.Margin = new Thickness(9, 0, 0, 0);
            name.VerticalAlignment = VerticalAlignment.Center;
            brand.Children.Add(name);
            header.Children.Add(brand);
            Grid.SetColumn(close, 1);
            close.Margin = new Thickness(0, 0, 10, 0);
            header.Children.Add(close);

            var badge = NoticeStyle.Badge(NoticeStyle.GlyphRepair, NoticeStyle.Amber, 46);
            badge.HorizontalAlignment = HorizontalAlignment.Left;
            badge.Margin = new Thickness(0, 0, 0, 14);
            var ok = new Button
            {
                Content = "Понятно", Height = 42, Margin = new Thickness(0, 20, 0, 0), FontSize = 15,
                FontWeight = FontWeights.SemiBold, Foreground = Brushes.White, Background = NoticeStyle.Brush(NoticeStyle.Teal),
                BorderThickness = new Thickness(0), Cursor = Cursors.Hand,
            };
            ok.Click += (_, _) => Close();
            var body = new StackPanel { Margin = new Thickness(22, 20, 22, 22) };
            body.Children.Add(badge);
            body.Children.Add(_title);
            body.Children.Add(_message);
            body.Children.Add(ok);

            var root = new StackPanel();
            root.Children.Add(new Border { CornerRadius = new CornerRadius(16, 16, 0, 0), Background = NoticeStyle.Brush(NoticeStyle.Ink), Child = header });
            root.Children.Add(body);
            Content = new Border
            {
                CornerRadius = new CornerRadius(16),
                Background = Brushes.White,
                Margin = new Thickness(12),
                Effect = new DropShadowEffect { Color = Colors.Black, Opacity = 0.26, BlurRadius = 26, ShadowDepth = 5 },
                Child = root,
            };
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
                };
            }
        }

        /// <summary>HQ changed the text while the window is open.</summary>
        public void Update(string? title, string? message)
        {
            _title.Text = string.IsNullOrWhiteSpace(title) ? "ePharm на технических работах" : title.Trim();
            _message.Text = string.IsNullOrWhiteSpace(message)
                ? "Сейчас ePharm недоступен: идут технические работы. Пожалуйста, подождите. Когда ePharm снова заработает, на этом компьютере появится уведомление."
                : message.Trim();
        }
    }

    /// <summary>
    /// Small card in the corner of the pharmacist's screen: «ePharm снова работает». Does not take the focus from the
    /// cashier program, closes itself after a while; a click opens the personal window.
    /// </summary>
    public sealed class EpharmToastWindow : Window
    {
        public event Action? Clicked;
        private readonly DispatcherTimer _life;

        public EpharmToastWindow(string title, string text, System.Windows.Forms.Screen? targetScreen = null, TimeSpan? lifetime = null)
        {
            WindowStyle = WindowStyle.None;
            ResizeMode = ResizeMode.NoResize;
            Topmost = true;
            ShowInTaskbar = false;
            ShowActivated = false;
            Focusable = false;
            AllowsTransparency = true;
            Background = Brushes.Transparent;
            SizeToContent = SizeToContent.Height;
            Width = 380;
            Title = "ePharm";
            Opacity = 0;

            var close = new Button
            {
                Content = "✕", Width = 26, Height = 26, FontSize = 12, Background = Brushes.Transparent,
                BorderThickness = new Thickness(0), Foreground = NoticeStyle.Brush("#B9C7CC"), Cursor = Cursors.Hand,
                Focusable = false, VerticalAlignment = VerticalAlignment.Top, ToolTip = "Закрыть",
            };
            close.Click += (_, e) => { e.Handled = true; Close(); };
            var grid = new Grid { Margin = new Thickness(16, 14, 8, 16) };
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            grid.ColumnDefinitions.Add(new ColumnDefinition());
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var mark = new Image { Source = EpharmMark.Image(40), Width = 40, Height = 40, VerticalAlignment = VerticalAlignment.Top };
            grid.Children.Add(mark);
            var texts = new StackPanel { Margin = new Thickness(13, 1, 6, 0) };
            texts.Children.Add(NoticeStyle.Text(title, 16, "#FFFFFF", FontWeights.Bold));
            var line = NoticeStyle.Text(text, 13.5, "#D3E3E6");
            line.Margin = new Thickness(0, 4, 0, 0);
            texts.Children.Add(line);
            Grid.SetColumn(texts, 1);
            grid.Children.Add(texts);
            Grid.SetColumn(close, 2);
            grid.Children.Add(close);
            var card = new Border
            {
                CornerRadius = new CornerRadius(14),
                Background = NoticeStyle.Brush(NoticeStyle.Ink),
                BorderBrush = NoticeStyle.Brush(NoticeStyle.Teal),
                BorderThickness = new Thickness(0, 0, 0, 4),
                Margin = new Thickness(12),
                Cursor = Cursors.Hand,
                Effect = new DropShadowEffect { Color = Colors.Black, Opacity = 0.32, BlurRadius = 24, ShadowDepth = 4 },
                Child = grid,
            };
            card.MouseLeftButtonUp += (_, _) => { Clicked?.Invoke(); Close(); };
            Content = card;

            _life = new DispatcherTimer { Interval = lifetime ?? TimeSpan.FromSeconds(30) };
            _life.Tick += (_, _) => { _life.Stop(); Close(); };
            Loaded += (_, _) =>
            {
                var area = targetScreen?.WorkingArea ?? System.Windows.Forms.Screen.PrimaryScreen!.WorkingArea;
                var dpi = VisualTreeHelper.GetDpi(this);
                Left = area.Right / dpi.DpiScaleX - ActualWidth - 8;
                Top = area.Bottom / dpi.DpiScaleY - ActualHeight - 8;
                BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(220)));
                _life.Start();
            };
            Closed += (_, _) => _life.Stop();
        }
    }
}
