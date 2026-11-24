using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
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
    /// <summary>One credited recommendation in the pharmacist's history.</summary>
    public sealed record StaffSelfReward(string Kind, string Product, long BonusKzt, DateTimeOffset At);

    /// <summary>
    /// What the ePharm tray window shows about the signed-in-by-Standard-N pharmacist. No password is needed to
    /// open it: the person is already known from Standard-N. The server fills this by IIN (device-authenticated).
    /// </summary>
    public sealed class StaffSelfModel
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
        public event Action<string>? OpenCabinetRequested;
        public event Action<string?, string?>? SaveContactRequested;

        private readonly TextBox _phone = NewField("+7 ___ ___ __ __");
        private readonly PasswordBox _password = new()
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
            Title = "ePharm — личный кабинет";
            Content = BuildCard(model);
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
            root.Children.Add(BuildHeader(model));
            root.Children.Add(BuildEarnings(model));
            if (model.Recent.Count > 0) root.Children.Add(BuildHistory(model));
            root.Children.Add(BuildContact(model));
            root.Children.Add(BuildCabinet(model));
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
            if (model.Iin.Length == 12) sub.Add("ИИН " + model.Iin[..6] + "•••••" + model.Iin[^1..]);
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
            Grid.SetColumn(close, 1);

            grid.Children.Add(left);
            grid.Children.Add(close);
            header.Child = grid;
            return header;
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
                Text = complete ? "Телефон и пароль сохранены — вход в кабинет с телефона работает."
                                : "Укажите телефон и пароль, чтобы заходить в кабинет с телефона и из дома.",
                Foreground = Brush("#6B7280"), FontSize = 12, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 10),
            });

            stack.Children.Add(FieldLabel("Номер телефона"));
            _phone.Text = model.PhoneBound ? "привязан" : "";
            stack.Children.Add(_phone);
            stack.Children.Add(FieldLabel("Пароль для входа"));
            stack.Children.Add(_password);

            var save = new Button
            {
                Content = "Сохранить", Margin = new Thickness(0, 11, 0, 0), Height = 42, FontSize = 15, FontWeight = FontWeights.SemiBold,
                Foreground = Brushes.White, Background = Brush("#1F8A70"), BorderThickness = new Thickness(0), Cursor = Cursors.Hand,
            };
            save.Click += (_, _) => SaveContactRequested?.Invoke(
                _phone.Text?.Trim(), _password.Password.Length > 0 ? _password.Password : null);
            stack.Children.Add(save);
            box.Child = stack;
            return box;
        }

        private UIElement BuildCabinet(StaffSelfModel model)
        {
            var box = new StackPanel { Margin = new Thickness(16, 2, 16, 16) };
            var open = new Button
            {
                Content = "Мой кабинет — обучение и бонусы", Height = 44, FontSize = 15, FontWeight = FontWeights.SemiBold,
                Foreground = Brush("#112B33"), Background = Brush("#E7EDEF"), BorderThickness = new Thickness(0), Cursor = Cursors.Hand,
            };
            open.Click += (_, _) => { if (model.CabinetUrl is { Length: > 0 } url) OpenCabinetRequested?.Invoke(url); };
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

        private static TextBlock FieldLabel(string text) => new()
        {
            Text = text, Foreground = Brush("#70788A"), FontSize = 12, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 0, 0, 4),
        };

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
