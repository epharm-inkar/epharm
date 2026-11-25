using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Effects;
using CustomerDisplay.Services;

namespace CustomerDisplay
{
    /// <summary>
    /// A readable ePharm page (1.0.99): «Что такое ePharm» and the history of notices. Big type, short sections, one
    /// scroll; opened by the pharmacist, so it may take the focus; moves with the mouse, closes with ×, Esc or
    /// «Закрыть». Never modal: the till keeps working behind it.
    /// </summary>
    public sealed class EpharmPageWindow : Window
    {
        private readonly System.Windows.Forms.Screen? _screen;

        public EpharmPageWindow(string title, string? lead, IReadOnlyList<EpharmPageSection> sections,
            System.Windows.Forms.Screen? targetScreen = null, string? emptyText = null)
        {
            _screen = targetScreen;
            WindowStyle = WindowStyle.None;
            ResizeMode = ResizeMode.NoResize;
            Topmost = true;
            ShowInTaskbar = false;
            AllowsTransparency = true;
            Background = Brushes.Transparent;
            SizeToContent = SizeToContent.Height;
            Width = 600;
            Title = "ePharm: " + title;
            Content = BuildCard(title, lead, sections, emptyText);
            MouseLeftButtonDown += (_, e) => { if (e.ButtonState == MouseButtonState.Pressed) DragMove(); };
            KeyDown += (_, e) => { if (e.Key == Key.Escape) Close(); };
            Loaded += (_, _) => Place();
        }

        /// <summary>Next to the personal window (top left), within the screen; never taller than the screen.</summary>
        private void Place()
        {
            var area = _screen?.WorkingArea ?? System.Windows.Forms.Screen.PrimaryScreen!.WorkingArea;
            var dpi = VisualTreeHelper.GetDpi(this);
            double left = area.Left / dpi.DpiScaleX, top = area.Top / dpi.DpiScaleY;
            double width = area.Width / dpi.DpiScaleX, height = area.Height / dpi.DpiScaleY;
            MaxHeight = Math.Max(320, height - 32);
            if (_body != null) _body.MaxHeight = Math.Max(200, MaxHeight - 190);
            Left = left + Math.Max(16, Math.Min(448, width - ActualWidth - 16));
            Top = top + 16;
        }

        private ScrollViewer? _body;

        private UIElement BuildCard(string title, string? lead, IReadOnlyList<EpharmPageSection> sections, string? emptyText)
        {
            var close = new Button
            {
                Content = "✕", Width = 36, Height = 36, FontSize = 16, Background = NoticeStyle.Brush("#1E3A42"),
                BorderThickness = new Thickness(0), Foreground = Brushes.White, Cursor = Cursors.Hand,
                ToolTip = "Закрыть", VerticalAlignment = VerticalAlignment.Center,
            };
            close.Click += (_, _) => Close();
            var header = new Grid { Margin = new Thickness(22, 16, 14, 16) };
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            header.ColumnDefinitions.Add(new ColumnDefinition());
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            header.Children.Add(new Image { Source = EpharmMark.Image(38), Width = 38, Height = 38, VerticalAlignment = VerticalAlignment.Center });
            var heading = NoticeStyle.Text(title, 24, "#FFFFFF", FontWeights.Bold);
            heading.Margin = new Thickness(14, 0, 8, 0);
            heading.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(heading, 1);
            header.Children.Add(heading);
            Grid.SetColumn(close, 2);
            header.Children.Add(close);

            var stack = new StackPanel { Margin = new Thickness(26, 20, 26, 8) };
            if (!string.IsNullOrWhiteSpace(lead))
            {
                var intro = NoticeStyle.Text(lead!, 17, NoticeStyle.Ink, FontWeights.SemiBold);
                intro.LineHeight = 25;
                intro.Margin = new Thickness(0, 0, 0, 18);
                stack.Children.Add(intro);
            }
            if (sections.Count == 0 && !string.IsNullOrWhiteSpace(emptyText))
            {
                var empty = NoticeStyle.Text(emptyText!, 16, "#5E6B6D");
                empty.Margin = new Thickness(0, 6, 0, 18);
                stack.Children.Add(empty);
            }
            foreach (var section in sections) stack.Children.Add(Section(section));

            _body = new ScrollViewer
            {
                Content = stack, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
                MaxHeight = Math.Max(240, SystemParameters.WorkArea.Height - 220),
            };
            var done = EpharmGreetingWindow.PageButton("Закрыть", primary: false);
            done.Focusable = true;
            done.Height = 44;
            done.Margin = new Thickness(26, 6, 26, 22);
            done.Click += (_, _) => Close();

            var root = new StackPanel();
            root.Children.Add(new Border { CornerRadius = new CornerRadius(18, 18, 0, 0), Background = NoticeStyle.Brush(NoticeStyle.Ink), Child = header });
            root.Children.Add(_body);
            root.Children.Add(done);
            return new Border
            {
                CornerRadius = new CornerRadius(18),
                Background = Brushes.White,
                Margin = new Thickness(12),
                Effect = new DropShadowEffect { Color = Colors.Black, Opacity = 0.26, BlurRadius = 28, ShadowDepth = 5 },
                Child = root,
            };
        }

        private static UIElement Section(EpharmPageSection section)
        {
            var box = new StackPanel { Margin = new Thickness(0, 0, 0, 20) };
            var head = new Grid();
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            head.ColumnDefinitions.Add(new ColumnDefinition());
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            head.Children.Add(new Border
            {
                Width = 5, CornerRadius = new CornerRadius(2.5), Background = NoticeStyle.Brush(NoticeStyle.Teal),
                Margin = new Thickness(0, 2, 12, 2),
            });
            var title = NoticeStyle.Text(section.Title, 19, NoticeStyle.Ink, FontWeights.Bold);
            title.LineHeight = 25;
            Grid.SetColumn(title, 1);
            head.Children.Add(title);
            if (!string.IsNullOrWhiteSpace(section.Badge))
            {
                var badge = new Border
                {
                    CornerRadius = new CornerRadius(7), Padding = new Thickness(9, 2, 9, 3), Margin = new Thickness(10, 2, 0, 0),
                    Background = NoticeStyle.Brush("#E0392B"), VerticalAlignment = VerticalAlignment.Top,
                    Child = NoticeStyle.Text(section.Badge!, 12.5, "#FFFFFF", FontWeights.Bold),
                };
                Grid.SetColumn(badge, 2);
                head.Children.Add(badge);
            }
            box.Children.Add(head);
            if (!string.IsNullOrWhiteSpace(section.Subtitle))
            {
                var sub = NoticeStyle.Text(section.Subtitle!, 13, "#6B7680");
                sub.Margin = new Thickness(17, 3, 0, 0);
                box.Children.Add(sub);
            }
            foreach (var line in section.Lines)
            {
                var row = new Grid { Margin = new Thickness(17, section.Bullets ? 9 : 7, 0, 0) };
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                row.ColumnDefinitions.Add(new ColumnDefinition());
                if (section.Bullets && section.Lines.Count > 1)
                {
                    row.Children.Add(new Border
                    {
                        Width = 7, Height = 7, CornerRadius = new CornerRadius(3.5), Background = NoticeStyle.Brush(NoticeStyle.Teal),
                        Margin = new Thickness(0, 9, 12, 0), VerticalAlignment = VerticalAlignment.Top,
                    });
                }
                var text = NoticeStyle.Text(line, 16, "#2F3B45");
                text.LineHeight = 24;
                Grid.SetColumn(text, 1);
                row.Children.Add(text);
                box.Children.Add(row);
            }
            return box;
        }
    }
}
