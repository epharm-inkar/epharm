using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Threading;

namespace CustomerDisplay
{
    /// <summary>Windows that must never take the keyboard (and the scanner) from the cashier program.</summary>
    internal static class NoActivate
    {
        private const int GwlExStyle = -20;
        private const long WsExNoActivate = 0x08000000;

        /// <summary>WS_EX_NOACTIVATE: clicks still work, but the window never becomes the active one.</summary>
        public static void Apply(Window window) => window.SourceInitialized += (_, _) =>
        {
            var hwnd = new WindowInteropHelper(window).Handle;
            if (hwnd == IntPtr.Zero) return;
            var style = GetWindowLongPtr(hwnd, GwlExStyle);
            SetWindowLongPtr(hwnd, GwlExStyle, new IntPtr(style.ToInt64() | WsExNoActivate));
        };

        [System.Runtime.InteropServices.DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW")]
        private static extern IntPtr GetWindowLongPtr(IntPtr hwnd, int index);

        [System.Runtime.InteropServices.DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW")]
        private static extern IntPtr SetWindowLongPtr(IntPtr hwnd, int index, IntPtr value);
    }

    /// <summary>
    /// The greeting at the first Standard-N sign-in of the day (1.0.99, Grigoriy 25.11.2026): «Доброе утро, Имя Отчество!
    /// Желаем хорошего дня», two short lines on how ePharm helps and «Что такое ePharm». In the lower right corner of the
    /// pharmacist screen; never takes the focus, never blocks a sale. Closes with «Закрыть», × or by itself (the countdown
    /// waits while the mouse is over it).
    /// </summary>
    public sealed class EpharmGreetingWindow : Window
    {
        /// <summary>«Что такое ePharm»: the host opens the page; the greeting closes.</summary>
        public event Action? WhatIsEpharmRequested;

        private readonly DispatcherTimer _tick = new() { Interval = TimeSpan.FromMilliseconds(200) };
        private readonly TimeSpan _lifetime;
        private readonly ScaleTransform _progressScale = new(1, 1);
        private TimeSpan _left;
        private bool _hover;
        private DateTime _lastTick;

        public EpharmGreetingWindow(string title, string wish, IReadOnlyList<string> lines,
            System.Windows.Forms.Screen? targetScreen = null, TimeSpan? lifetime = null)
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
            Width = 440;
            Title = "ePharm: приветствие";
            Opacity = 0;
            _lifetime = lifetime ?? TimeSpan.FromSeconds(45);
            _left = _lifetime;
            NoActivate.Apply(this);

            Content = BuildCard(title, wish, lines);
            MouseEnter += (_, _) => _hover = true;
            MouseLeave += (_, _) => _hover = false;
            _tick.Tick += (_, _) => Tick();
            Loaded += (_, _) =>
            {
                var area = targetScreen?.WorkingArea ?? System.Windows.Forms.Screen.PrimaryScreen!.WorkingArea;
                var dpi = VisualTreeHelper.GetDpi(this);
                Left = area.Right / dpi.DpiScaleX - ActualWidth - 8;
                Top = area.Bottom / dpi.DpiScaleY - ActualHeight - 8;
                BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(220)));
                _lastTick = DateTime.UtcNow;
                _tick.Start();
            };
            Closed += (_, _) => _tick.Stop();
        }

        private void Tick()
        {
            var now = DateTime.UtcNow;
            var elapsed = now - _lastTick;
            _lastTick = now;
            if (_hover) return;
            _left -= elapsed;
            _progressScale.ScaleX = Math.Max(0, _left.TotalMilliseconds / _lifetime.TotalMilliseconds);
            if (_left <= TimeSpan.Zero) Close();
        }

        private UIElement BuildCard(string title, string wish, IReadOnlyList<string> lines)
        {
            var close = new Button
            {
                Content = "✕", Width = 30, Height = 30, FontSize = 14, Background = Brushes.Transparent,
                BorderThickness = new Thickness(0), Foreground = NoticeStyle.Brush("#B9C7CC"), Cursor = Cursors.Hand,
                Focusable = false, ToolTip = "Закрыть", VerticalAlignment = VerticalAlignment.Center,
            };
            close.Click += (_, _) => Close();
            var header = new Grid { Margin = new Thickness(18, 10, 10, 10) };
            header.ColumnDefinitions.Add(new ColumnDefinition());
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var brand = new StackPanel { Orientation = Orientation.Horizontal };
            brand.Children.Add(new Image { Source = EpharmMark.Image(24), Width = 24, Height = 24 });
            var name = NoticeStyle.Text("ePharm", 15, "#FFFFFF", FontWeights.SemiBold);
            name.Margin = new Thickness(9, 0, 0, 0);
            name.VerticalAlignment = VerticalAlignment.Center;
            brand.Children.Add(name);
            header.Children.Add(brand);
            Grid.SetColumn(close, 1);
            header.Children.Add(close);

            var body = new StackPanel { Margin = new Thickness(22, 18, 22, 20) };
            var hello = NoticeStyle.Text(title, 23, NoticeStyle.Ink, FontWeights.Bold);
            hello.LineHeight = 29;
            body.Children.Add(hello);
            var wishText = NoticeStyle.Text(wish, 17, NoticeStyle.Teal, FontWeights.SemiBold);
            wishText.Margin = new Thickness(0, 4, 0, 14);
            body.Children.Add(wishText);
            foreach (var line in lines)
            {
                var row = new Grid { Margin = new Thickness(0, 0, 0, 8) };
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                row.ColumnDefinitions.Add(new ColumnDefinition());
                var check = new TextBlock
                {
                    Text = NoticeStyle.GlyphCheck, FontFamily = new FontFamily("Segoe MDL2 Assets"), FontSize = 13,
                    Foreground = NoticeStyle.Brush(NoticeStyle.Teal), Margin = new Thickness(0, 3, 10, 0),
                };
                row.Children.Add(check);
                var text = NoticeStyle.Text(line, 15, "#3A4752");
                text.LineHeight = 21;
                Grid.SetColumn(text, 1);
                row.Children.Add(text);
                body.Children.Add(row);
            }

            var buttons = new Grid { Margin = new Thickness(0, 10, 0, 0) };
            buttons.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.4, GridUnitType.Star) });
            buttons.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(10) });
            buttons.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var about = PageButton("Что такое ePharm", primary: true);
            about.Click += (_, _) => { WhatIsEpharmRequested?.Invoke(); Close(); };
            buttons.Children.Add(about);
            var later = PageButton("Закрыть", primary: false);
            later.Click += (_, _) => Close();
            Grid.SetColumn(later, 2);
            buttons.Children.Add(later);
            body.Children.Add(buttons);

            // A slim line that shrinks while the window waits: it closes by itself (paused under the mouse).
            var progress = new Grid { Height = 3, Margin = new Thickness(0, 16, 0, 0), ToolTip = "Окно закроется само" };
            progress.Children.Add(new Border { CornerRadius = new CornerRadius(1.5), Background = NoticeStyle.Brush("#E3E9EB") });
            progress.Children.Add(new Border
            {
                CornerRadius = new CornerRadius(1.5), Background = NoticeStyle.Brush(NoticeStyle.Teal),
                RenderTransform = _progressScale, RenderTransformOrigin = new Point(0, 0.5),
            });
            body.Children.Add(progress);

            var root = new StackPanel();
            root.Children.Add(new Border { CornerRadius = new CornerRadius(16, 16, 0, 0), Background = NoticeStyle.Brush(NoticeStyle.Ink), Child = header });
            root.Children.Add(body);
            return new Border
            {
                CornerRadius = new CornerRadius(16),
                Background = Brushes.White,
                Margin = new Thickness(12),
                Effect = new DropShadowEffect { Color = Colors.Black, Opacity = 0.28, BlurRadius = 26, ShadowDepth = 5 },
                Child = root,
            };
        }

        /// <summary>Big, flat, never focusable (the cashier keeps the keyboard).</summary>
        internal static Button PageButton(string text, bool primary) => new()
        {
            Content = text, Height = 42, FontSize = 15, FontWeight = FontWeights.SemiBold, Focusable = false,
            Foreground = primary ? Brushes.White : NoticeStyle.Brush(NoticeStyle.Ink),
            Background = NoticeStyle.Brush(primary ? NoticeStyle.Teal : "#E7EDEF"),
            BorderThickness = new Thickness(0), Cursor = Cursors.Hand,
        };
    }
}
