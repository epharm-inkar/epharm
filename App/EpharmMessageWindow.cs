using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using CustomerDisplay.Services;

namespace CustomerDisplay
{
    /// <summary>
    /// A notice from HQ (1.0.99): title, when it was sent, the text and «Понятно, закрыть». It stays in the lower right
    /// corner of the pharmacist screen until the person closes it (× or the button); it never takes the focus from the
    /// cashier program and is hidden while a recommendation popup is open. Several notices come one after another.
    /// </summary>
    public sealed class EpharmMessageWindow : Window
    {
        /// <summary>The person closed it: the host records that and shows the next one.</summary>
        public event Action<TillMessage>? Dismissed;

        /// <summary>The window is on screen (rendered): the host reports «shown».</summary>
        public event Action<TillMessage>? Displayed;

        public TillMessage Message { get; }

        private readonly System.Windows.Forms.Screen? _screen;
        private bool _dismissed;

        public EpharmMessageWindow(TillMessage message, int position, int total, DateTimeOffset localNow,
            System.Windows.Forms.Screen? targetScreen = null)
        {
            Message = message;
            _screen = targetScreen;
            WindowStyle = WindowStyle.None;
            ResizeMode = ResizeMode.NoResize;
            Topmost = true;
            ShowInTaskbar = false;
            ShowActivated = false;
            Focusable = false;
            AllowsTransparency = true;
            Background = Brushes.Transparent;
            SizeToContent = SizeToContent.Height;
            Width = 460;
            Title = "ePharm: уведомление";
            Opacity = 0;
            NoActivate.Apply(this);
            Content = BuildCard(message, position, total, localNow);
            Loaded += (_, _) =>
            {
                PlaceInCorner();
                BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(200)));
            };
            ContentRendered += (_, _) => Displayed?.Invoke(Message);
            SizeChanged += (_, _) => { if (IsLoaded) PlaceInCorner(); };
        }

        private void PlaceInCorner()
        {
            var area = _screen?.WorkingArea ?? System.Windows.Forms.Screen.PrimaryScreen!.WorkingArea;
            var dpi = VisualTreeHelper.GetDpi(this);
            MaxHeight = Math.Max(260, area.Height / dpi.DpiScaleY - 24);
            Left = area.Right / dpi.DpiScaleX - ActualWidth - 8;
            Top = Math.Max(area.Top / dpi.DpiScaleY + 8, area.Bottom / dpi.DpiScaleY - ActualHeight - 8);
        }

        private void Dismiss()
        {
            if (_dismissed) return;
            _dismissed = true;
            Dismissed?.Invoke(Message);
            Close();
        }

        private UIElement BuildCard(TillMessage message, int position, int total, DateTimeOffset localNow)
        {
            var close = new Button
            {
                Content = "✕", Width = 32, Height = 32, FontSize = 15, Background = Brushes.Transparent,
                BorderThickness = new Thickness(0), Foreground = NoticeStyle.Brush("#D3E3E6"), Cursor = Cursors.Hand,
                Focusable = false, ToolTip = "Закрыть уведомление", VerticalAlignment = VerticalAlignment.Center,
            };
            close.Click += (_, _) => Dismiss();
            var header = new Grid { Margin = new Thickness(18, 10, 10, 10) };
            header.ColumnDefinitions.Add(new ColumnDefinition());
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var brand = new StackPanel { Orientation = Orientation.Horizontal };
            brand.Children.Add(new Image { Source = EpharmMark.Image(24), Width = 24, Height = 24 });
            var name = NoticeStyle.Text("Уведомление ePharm", 15, "#FFFFFF", FontWeights.SemiBold);
            name.Margin = new Thickness(9, 0, 0, 0);
            name.VerticalAlignment = VerticalAlignment.Center;
            brand.Children.Add(name);
            header.Children.Add(brand);
            if (total > 1)
            {
                var counter = NoticeStyle.Text(position + " из " + total, 13, "#B9C7CC", FontWeights.SemiBold);
                counter.VerticalAlignment = VerticalAlignment.Center;
                counter.Margin = new Thickness(0, 0, 8, 0);
                Grid.SetColumn(counter, 1);
                header.Children.Add(counter);
            }
            Grid.SetColumn(close, 2);
            header.Children.Add(close);

            var body = new StackPanel { Margin = new Thickness(22, 18, 22, 20) };
            var title = NoticeStyle.Text(message.Title, 20, NoticeStyle.Ink, FontWeights.Bold);
            title.LineHeight = 26;
            body.Children.Add(title);
            var sent = NoticeStyle.Text("Отправлено " + TillMessageFormat.Sent(message.SentAt, localNow), 13, "#6B7680");
            sent.Margin = new Thickness(0, 4, 0, 12);
            body.Children.Add(sent);
            var text = NoticeStyle.Text(message.Text, 15.5, "#2F3B45");
            text.LineHeight = 23;
            body.Children.Add(new ScrollViewer
            {
                Content = text, MaxHeight = 320, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, Focusable = false,
            });
            var ok = EpharmGreetingWindow.PageButton(total > 1 && position < total ? "Понятно, следующее" : "Понятно, закрыть", primary: true);
            ok.Height = 44;
            ok.Margin = new Thickness(0, 18, 0, 0);
            ok.Click += (_, _) => Dismiss();
            body.Children.Add(ok);

            var root = new StackPanel();
            root.Children.Add(new Border { CornerRadius = new CornerRadius(16, 16, 0, 0), Background = NoticeStyle.Brush(NoticeStyle.Ink), Child = header });
            root.Children.Add(body);
            return new Border
            {
                CornerRadius = new CornerRadius(16),
                Background = Brushes.White,
                BorderBrush = NoticeStyle.Brush(NoticeStyle.Teal),
                BorderThickness = new Thickness(0, 0, 0, 4),
                Margin = new Thickness(12),
                Effect = new DropShadowEffect { Color = Colors.Black, Opacity = 0.3, BlurRadius = 26, ShadowDepth = 5 },
                Child = root,
            };
        }
    }
}
