using System;
using SD2 = System.Drawing.Drawing2D;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using SD = System.Drawing;

namespace CustomerDisplay
{
    /// <summary>
    /// The ePharm mark (1.0.95): a teal-green rounded square with a white «e» and a small pharmacy cross. The same
    /// geometry for the icon by the clock (GDI+, drawn at the tray's own size so it stays sharp) and for the windows
    /// (WPF). Status dots sit in the lower right corner: red while the profile is not filled, amber during the works.
    /// </summary>
    public static class EpharmMark
    {
        private static readonly SD.Color Top = SD.Color.FromArgb(0x1C, 0xA8, 0x8A);
        private static readonly SD.Color Bottom = SD.Color.FromArgb(0x0C, 0x63, 0x5A);

        public enum Dot { None, Alert, Paused }

        /// <summary>Icon for NotifyIcon. Caller disposes it.</summary>
        public static SD.Icon TrayIcon(Dot dot, int? pixels = null)
        {
            var size = Math.Max(16, Math.Min(256, pixels ?? System.Windows.Forms.SystemInformation.SmallIconSize.Width));
            using var bitmap = new SD.Bitmap(size, size);
            using (var g = SD.Graphics.FromImage(bitmap))
            {
                g.SmoothingMode = SD2.SmoothingMode.AntiAlias;
                g.TextRenderingHint = SD.Text.TextRenderingHint.AntiAliasGridFit;
                g.Clear(SD.Color.Transparent);
                float s = size;
                using (var path = RoundedRect(0.5f, 0.5f, s - 1, s - 1, s * 0.24f))
                using (var fill = new SD2.LinearGradientBrush(new SD.PointF(0, 0), new SD.PointF(s, s), Top, Bottom))
                    g.FillPath(fill, path);
                using (var font = new SD.Font("Segoe UI", s * 0.66f, SD.FontStyle.Bold, SD.GraphicsUnit.Pixel))
                using (var white = new SD.SolidBrush(SD.Color.White))
                using (var format = new SD.StringFormat { Alignment = SD.StringAlignment.Center, LineAlignment = SD.StringAlignment.Center })
                    g.DrawString("e", font, white, new SD.RectangleF(-s * 0.06f, s * 0.02f, s, s), format);
                using (var pen = new SD.Pen(SD.Color.White, Math.Max(1.4f, s * 0.085f)))
                {
                    var cx = s * 0.77f; var cy = s * 0.24f; var arm = s * 0.11f;
                    g.DrawLine(pen, cx - arm, cy, cx + arm, cy);
                    g.DrawLine(pen, cx, cy - arm, cx, cy + arm);
                }
                if (dot != Dot.None)
                {
                    var d = s * 0.42f;
                    using var brush = new SD.SolidBrush(dot == Dot.Alert ? SD.Color.FromArgb(0xE0, 0x39, 0x2B) : SD.Color.FromArgb(0xE8, 0xA3, 0x17));
                    using var ring = new SD.Pen(SD.Color.White, Math.Max(1f, s * 0.07f));
                    g.FillEllipse(brush, s - d - 0.5f, s - d - 0.5f, d, d);
                    g.DrawEllipse(ring, s - d - 0.5f, s - d - 0.5f, d, d);
                }
            }
            var handle = bitmap.GetHicon();
            using var temp = SD.Icon.FromHandle(handle);
            var icon = (SD.Icon)temp.Clone();
            NativeIcon.DestroyIcon(handle);
            return icon;
        }

        /// <summary>The mark for WPF windows, rendered at <paramref name="size"/> device-independent pixels.</summary>
        public static ImageSource Image(int size)
        {
            const double scale = 2; // crisp on 150-200 % screens
            var px = (int)(size * scale);
            var visual = new DrawingVisual();
            using (var dc = visual.RenderOpen())
            {
                // Drawn in device-independent units; the bitmap's DPI turns them into size × scale pixels.
                double s = size;
                var fill = new LinearGradientBrush(Color.FromRgb(Top.R, Top.G, Top.B), Color.FromRgb(Bottom.R, Bottom.G, Bottom.B), new Point(0, 0), new Point(1, 1));
                dc.DrawRoundedRectangle(fill, null, new Rect(0, 0, s, s), s * 0.24, s * 0.24);
                var text = new FormattedText("e", System.Globalization.CultureInfo.InvariantCulture, FlowDirection.LeftToRight,
                    new Typeface(new FontFamily("Segoe UI"), FontStyles.Normal, FontWeights.Bold, FontStretches.Normal), s * 0.66,
                    Brushes.White, 1.0);
                dc.DrawText(text, new Point((s - text.Width) / 2 - s * 0.06, (s - text.Height) / 2 + s * 0.02));
                var pen = new Pen(Brushes.White, Math.Max(1.4, s * 0.085));
                double cx = s * 0.77, cy = s * 0.24, arm = s * 0.11;
                dc.DrawLine(pen, new Point(cx - arm, cy), new Point(cx + arm, cy));
                dc.DrawLine(pen, new Point(cx, cy - arm), new Point(cx, cy + arm));
            }
            var bitmap = new RenderTargetBitmap(px, px, 96 * scale, 96 * scale, PixelFormats.Pbgra32);
            bitmap.Render(visual);
            bitmap.Freeze();
            return bitmap;
        }

        private static SD2.GraphicsPath RoundedRect(float x, float y, float w, float h, float r)
        {
            var path = new SD2.GraphicsPath();
            var d = r * 2;
            path.AddArc(x, y, d, d, 180, 90);
            path.AddArc(x + w - d, y, d, d, 270, 90);
            path.AddArc(x + w - d, y + h - d, d, d, 0, 90);
            path.AddArc(x, y + h - d, d, d, 90, 90);
            path.CloseFigure();
            return path;
        }

        private static class NativeIcon
        {
            [System.Runtime.InteropServices.DllImport("user32.dll", SetLastError = true)]
            [return: System.Runtime.InteropServices.MarshalAs(System.Runtime.InteropServices.UnmanagedType.Bool)]
            internal static extern bool DestroyIcon(IntPtr handle);
        }
    }
}
