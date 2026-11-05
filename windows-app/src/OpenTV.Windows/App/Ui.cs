using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Windows.Forms;
using OpenTV.Core;

namespace OpenTV.Windows.App;

/// <summary>Colours, fonts and icon glyphs shared by the remote window: the same dark look as the Mac and iPhone apps.</summary>
internal static class Theme
{
    public static readonly Color Back = Color.FromArgb(18, 18, 18);
    public static readonly Color Surface = Color.FromArgb(44, 44, 44);
    public static readonly Color SurfaceHover = Color.FromArgb(58, 58, 58);
    public static readonly Color SurfacePressed = Color.FromArgb(78, 78, 78);
    public static readonly Color Field = Color.FromArgb(34, 34, 34);
    public static readonly Color Line = Color.FromArgb(38, 38, 38);
    public static readonly Color Text = Color.FromArgb(245, 245, 245);
    public static readonly Color Dim = Color.FromArgb(150, 150, 150);
    public static readonly Color Faint = Color.FromArgb(95, 95, 95);
    public static readonly Color Green = Color.FromArgb(48, 209, 88);
    public static readonly Color Red = Color.FromArgb(255, 69, 58);
    public static readonly Color Orange = Color.FromArgb(255, 159, 10);
    public static readonly Color Accent = Color.FromArgb(64, 170, 255);

    /// <summary>Segoe Fluent Icons on Windows 11, Segoe MDL2 Assets on Windows 10 (same code points).</summary>
    public static readonly string IconFamily = Installed("Segoe Fluent Icons") ? "Segoe Fluent Icons" : "Segoe MDL2 Assets";

    public static readonly string TextFamily = Installed("Segoe UI Variable Text") ? "Segoe UI Variable Text" : "Segoe UI";

    private static bool Installed(string family)
    {
        try
        {
            using var fonts = new InstalledFontCollection();
            return fonts.Families.Any(f => string.Equals(f.Name, family, StringComparison.OrdinalIgnoreCase));
        }
        catch (Exception)
        {
            return false;
        }
    }

    /// <summary>A font sized in points for <paramref name="dpi"/>, in pixels so custom painting needs no further scaling.</summary>
    public static Font Font(float points, int dpi, FontStyle style = FontStyle.Regular) =>
        new(TextFamily, points * dpi / 72f, style, GraphicsUnit.Pixel);

    public static Font Icons(float points, int dpi) => new(IconFamily, points * dpi / 72f, FontStyle.Regular, GraphicsUnit.Pixel);
}

/// <summary>Icon font code points (identical in Segoe MDL2 Assets and Segoe Fluent Icons).</summary>
internal static class Glyph
{
    public const string Power = "", Back = "", Home = "", Control = "";
    public const string Up = "", Down = "", Left = "", Right = "";
    public const string Play = "", Pause = "", Mute = "", Volume = "";
    public const string Previous = "", Next = "", Keyboard = "", Tv = "";
    public const string Plus = "", Minus = "", Music = "", Send = "", Cable = "";
}

internal enum ButtonShape { Circle, Pill, Rounded }

/// <summary>Base for the remote's painted controls: double-buffered, never takes keyboard focus, tracks hover and press.</summary>
internal abstract class PaintedControl : Control
{
    protected bool Hovering;
    protected bool Pressing;
    protected Point Mouse;

    protected PaintedControl()
    {
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint |
                 ControlStyles.ResizeRedraw | ControlStyles.SupportsTransparentBackColor, true);
        SetStyle(ControlStyles.Selectable, false);
        TabStop = false;
        BackColor = Theme.Back;
        ForeColor = Theme.Text;
        Cursor = Cursors.Hand;
    }

    protected override void OnMouseEnter(EventArgs e)
    {
        Hovering = true;
        Invalidate();
        base.OnMouseEnter(e);
    }

    protected override void OnMouseLeave(EventArgs e)
    {
        Hovering = false;
        Pressing = false;
        Invalidate();
        base.OnMouseLeave(e);
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        Mouse = e.Location;
        Invalidate();
        base.OnMouseMove(e);
    }

    protected override void OnMouseDown(MouseEventArgs e)
    {
        if (e.Button == MouseButtons.Left)
        {
            Pressing = true;
            Mouse = e.Location;
            Invalidate();
        }
        base.OnMouseDown(e);
    }

    protected override void OnMouseUp(MouseEventArgs e)
    {
        Pressing = false;
        Invalidate();
        base.OnMouseUp(e);
    }

    protected Color Fill(bool active) => active && Pressing ? Theme.SurfacePressed : active && Hovering ? Theme.SurfaceHover : Theme.Surface;

    protected static GraphicsPath RoundRect(RectangleF r, float radius)
    {
        var d = Math.Min(radius * 2, Math.Min(r.Width, r.Height));
        var p = new GraphicsPath();
        p.AddArc(r.X, r.Y, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }

    protected static void Smooth(Graphics g)
    {
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
        g.PixelOffsetMode = PixelOffsetMode.HighQuality;
    }

    protected static void Centered(Graphics g, string text, Font font, Color color, RectangleF r)
    {
        using var brush = new SolidBrush(color);
        using var format = new StringFormat(StringFormat.GenericTypographic)
        {
            Alignment = StringAlignment.Center,
            LineAlignment = StringAlignment.Center,
            FormatFlags = StringFormatFlags.NoWrap,
            Trimming = StringTrimming.EllipsisCharacter,
        };
        g.DrawString(text, font, brush, r, format);
    }
}

/// <summary>A round (or pill-shaped) dark button with an icon glyph and an optional label, like the Mac remote's.</summary>
internal sealed class GlyphButton : PaintedControl
{
    private Font? iconFont;
    private Font? labelFont;

    public string Glyph { get; set; } = "";
    public string Label { get; set; } = "";
    public ButtonShape Shape { get; set; } = ButtonShape.Circle;
    public Color GlyphColor { get; set; } = Theme.Text;
    public float GlyphPoints { get; set; } = 13;

    public void SetFonts(int dpi)
    {
        iconFont?.Dispose();
        labelFont?.Dispose();
        iconFont = Theme.Icons(GlyphPoints, dpi);
        labelFont = Theme.Font(9.5f, dpi, FontStyle.Bold);
        Invalidate();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        Smooth(g);
        g.Clear(BackColor);
        var r = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        if (Pressing)
        {
            var inset = Math.Min(Width, Height) * 0.035f;
            r.Inflate(-inset, -inset);
        }
        using (var brush = new SolidBrush(Enabled ? Fill(true) : Theme.Field))
        {
            if (Shape == ButtonShape.Circle) g.FillEllipse(brush, r);
            else
            {
                using var path = RoundRect(r, Shape == ButtonShape.Pill ? r.Height / 2 : r.Height * 0.28f);
                g.FillPath(brush, path);
            }
        }
        iconFont ??= Theme.Icons(GlyphPoints, DeviceDpi);
        labelFont ??= Theme.Font(9.5f, DeviceDpi, FontStyle.Bold);
        var color = Enabled ? GlyphColor : Theme.Faint;
        if (Label.Length == 0)
        {
            Centered(g, Glyph, iconFont, color, r);
            return;
        }
        // Glyph and label side by side, centred together.
        var gw = Glyph.Length == 0 ? 0 : g.MeasureString(Glyph, iconFont, PointF.Empty, StringFormat.GenericTypographic).Width;
        var lw = g.MeasureString(Label, labelFont, PointF.Empty, StringFormat.GenericTypographic).Width;
        var gap = Glyph.Length == 0 ? 0 : Height * 0.22f;
        var x = r.X + Math.Max(0, (r.Width - gw - gap - lw) / 2);
        if (gw > 0) Centered(g, Glyph, iconFont, color, new RectangleF(x, r.Y, gw, r.Height));
        Centered(g, Label, labelFont, color, new RectangleF(x + gw + gap, r.Y, Math.Min(lw + 2, r.Right - x - gw - gap), r.Height));
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            iconFont?.Dispose();
            labelFont?.Dispose();
        }
        base.Dispose(disposing);
    }
}

/// <summary>The click pad: a ring whose edges move and whose centre selects, like the Siri Remote's.</summary>
internal sealed class ClickPad : PaintedControl
{
    private Font? font;

    public event Action<RemoteKey>? Press;

    public void SetFonts(int dpi)
    {
        font?.Dispose();
        font = Theme.Icons(12, dpi);
        Invalidate();
    }

    private RemoteKey Zone(Point p)
    {
        var cx = Width / 2f;
        var cy = Height / 2f;
        var dx = p.X - cx;
        var dy = p.Y - cy;
        if (Math.Sqrt(dx * dx + dy * dy) < Width * 0.22) return RemoteKey.Select;
        if (Math.Abs(dx) > Math.Abs(dy)) return dx > 0 ? RemoteKey.Right : RemoteKey.Left;
        return dy > 0 ? RemoteKey.Down : RemoteKey.Up;
    }

    private bool Inside(Point p)
    {
        var dx = p.X - Width / 2f;
        var dy = p.Y - Height / 2f;
        return Math.Sqrt(dx * dx + dy * dy) <= Width / 2f;
    }

    protected override void OnMouseClick(MouseEventArgs e)
    {
        base.OnMouseClick(e);
        if (e.Button == MouseButtons.Left && Inside(e.Location)) Press?.Invoke(Zone(e.Location));
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        Smooth(g);
        g.Clear(BackColor);
        var r = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        using (var ring = new SolidBrush(Theme.Surface)) g.FillEllipse(ring, r);

        var active = (Hovering || Pressing) && Inside(Mouse);
        var zone = active ? Zone(Mouse) : (RemoteKey?)null;
        var cx = Width / 2f;
        var cy = Height / 2f;
        if (zone is { } z && z != RemoteKey.Select)
        {
            // Light the quarter of the ring under the pointer.
            var start = z switch { RemoteKey.Right => -45f, RemoteKey.Down => 45f, RemoteKey.Left => 135f, _ => 225f };
            using var path = new GraphicsPath();
            path.AddPie(r.X, r.Y, r.Width, r.Height, start, 90);
            using var hi = new SolidBrush(Pressing ? Theme.SurfacePressed : Theme.SurfaceHover);
            g.FillPath(hi, path);
        }
        var inner = Width * 0.44f;
        var centre = new RectangleF(cx - inner / 2, cy - inner / 2, inner, inner);
        using (var c = new SolidBrush(zone == RemoteKey.Select ? (Pressing ? Theme.SurfacePressed : Theme.SurfaceHover) : Theme.Back))
        {
            g.FillEllipse(c, centre);
        }
        using (var edge = new Pen(Theme.Line, Math.Max(1, DeviceDpi / 96f))) g.DrawEllipse(edge, centre);

        font ??= Theme.Icons(12, DeviceDpi);
        var a = Width * 0.36f;
        var s = Width * 0.2f;
        var dim = Color.FromArgb(200, Theme.Text);
        Centered(g, Glyph.Up, font, dim, new RectangleF(cx - s / 2, cy - a - s / 2, s, s));
        Centered(g, Glyph.Down, font, dim, new RectangleF(cx - s / 2, cy + a - s / 2, s, s));
        Centered(g, Glyph.Left, font, dim, new RectangleF(cx - a - s / 2, cy - s / 2, s, s));
        Centered(g, Glyph.Right, font, dim, new RectangleF(cx + a - s / 2, cy - s / 2, s, s));
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) font?.Dispose();
        base.Dispose(disposing);
    }
}

/// <summary>Volume down and up in one capsule, with a speaker in the middle.</summary>
internal sealed class VolumePill : PaintedControl
{
    private Font? font;
    private Font? small;

    public event Action<RemoteKey>? Press;

    public void SetFonts(int dpi)
    {
        font?.Dispose();
        small?.Dispose();
        font = Theme.Icons(12, dpi);
        small = Theme.Icons(10, dpi);
        Invalidate();
    }

    protected override void OnMouseClick(MouseEventArgs e)
    {
        base.OnMouseClick(e);
        if (e.Button != MouseButtons.Left) return;
        if (e.X < Width * 0.4) Press?.Invoke(RemoteKey.VolDown);
        else if (e.X > Width * 0.6) Press?.Invoke(RemoteKey.VolUp);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        Smooth(g);
        g.Clear(BackColor);
        var r = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        using (var path = RoundRect(r, r.Height / 2))
        using (var brush = new SolidBrush(Theme.Surface))
        {
            g.FillPath(brush, path);
            if (Hovering && (Mouse.X < Width * 0.4 || Mouse.X > Width * 0.6))
            {
                var left = Mouse.X < Width * 0.4;
                var half = left ? new RectangleF(r.X, r.Y, r.Width / 2, r.Height) : new RectangleF(r.X + r.Width / 2, r.Y, r.Width / 2, r.Height);
                var clip = g.Clip;
                g.SetClip(half);
                using var hi = new SolidBrush(Pressing ? Theme.SurfacePressed : Theme.SurfaceHover);
                g.FillPath(hi, path);
                g.Clip = clip;
            }
        }
        font ??= Theme.Icons(12, DeviceDpi);
        small ??= Theme.Icons(10, DeviceDpi);
        var third = r.Width / 3;
        Centered(g, Glyph.Minus, font, Theme.Text, new RectangleF(r.X, r.Y, third, r.Height));
        Centered(g, Glyph.Volume, small, Theme.Dim, new RectangleF(r.X + third, r.Y, third, r.Height));
        Centered(g, Glyph.Plus, font, Theme.Text, new RectangleF(r.X + 2 * third, r.Y, third, r.Height));
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            font?.Dispose();
            small?.Dispose();
        }
        base.Dispose(disposing);
    }
}

/// <summary>What's playing on the TV, with a play/pause button.</summary>
internal sealed class NowPlayingCard : PaintedControl
{
    private Font? title;
    private Font? artist;
    private Font? icons;
    private NowPlaying? np;

    public event Action? PlayPause;

    public NowPlaying? Value
    {
        get => np;
        set
        {
            np = value;
            Invalidate();
        }
    }

    public void SetFonts(int dpi)
    {
        title?.Dispose();
        artist?.Dispose();
        icons?.Dispose();
        title = Theme.Font(10, dpi, FontStyle.Bold);
        artist = Theme.Font(9, dpi);
        icons = Theme.Icons(13, dpi);
        Invalidate();
    }

    protected override void OnMouseClick(MouseEventArgs e)
    {
        base.OnMouseClick(e);
        if (e.Button == MouseButtons.Left && e.X > Width - Height) PlayPause?.Invoke();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        Smooth(g);
        g.Clear(BackColor);
        title ??= Theme.Font(10, DeviceDpi, FontStyle.Bold);
        artist ??= Theme.Font(9, DeviceDpi);
        icons ??= Theme.Icons(13, DeviceDpi);
        var r = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        using (var card = RoundRect(r, Height * 0.26f))
        using (var brush = new SolidBrush(Color.FromArgb(32, 32, 32)))
        {
            g.FillPath(brush, card);
        }
        var pad = Height * 0.17f;
        var art = new RectangleF(pad, pad, Height - 2 * pad, Height - 2 * pad);
        using (var path = RoundRect(art, art.Width * 0.22f))
        using (var grad = new LinearGradientBrush(art, Color.FromArgb(230, 255, 55, 120), Color.FromArgb(220, 255, 149, 0), 45f))
        {
            g.FillPath(grad, path);
        }
        Centered(g, Glyph.Music, icons, Color.White, art);
        if (np is null) return;

        var textX = art.Right + pad;
        var textW = Width - textX - Height;
        using var white = new SolidBrush(Theme.Text);
        using var dim = new SolidBrush(Theme.Dim);
        using var format = new StringFormat(StringFormat.GenericTypographic) { Trimming = StringTrimming.EllipsisCharacter, FormatFlags = StringFormatFlags.NoWrap };
        if (np.Artist.Length > 0)
        {
            g.DrawString(np.Title, title, white, new RectangleF(textX, Height * 0.2f, textW, Height * 0.32f), format);
            g.DrawString(np.Artist, artist, dim, new RectangleF(textX, Height * 0.52f, textW, Height * 0.3f), format);
        }
        else
        {
            format.LineAlignment = StringAlignment.Center;
            g.DrawString(np.Title, title, white, new RectangleF(textX, 0, textW, Height), format);
        }
        var button = new RectangleF(Width - Height, 0, Height, Height);
        if (Hovering && Mouse.X > Width - Height)
        {
            var b = button;
            b.Inflate(-Height * 0.2f, -Height * 0.2f);
            using var hi = new SolidBrush(Pressing ? Theme.SurfacePressed : Theme.SurfaceHover);
            g.FillEllipse(hi, b);
        }
        Centered(g, np.Playing ? Glyph.Pause : Glyph.Play, icons, Theme.Text, button);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            title?.Dispose();
            artist?.Dispose();
            icons?.Dispose();
        }
        base.Dispose(disposing);
    }
}

/// <summary>The TV's name and connection state; clicking it opens the TV list.</summary>
internal sealed class TvHeader : PaintedControl
{
    private Font? name;
    private Font? status;
    private Font? icons;
    private Font? chevron;

    public string TvName { get; set; } = "Choose a TV";
    public string Status { get; set; } = "";
    public Color StatusColor { get; set; } = Theme.Orange;

    public void SetFonts(int dpi)
    {
        name?.Dispose();
        status?.Dispose();
        icons?.Dispose();
        chevron?.Dispose();
        name = Theme.Font(11, dpi, FontStyle.Bold);
        status = Theme.Font(8.5f, dpi);
        icons = Theme.Icons(14, dpi);
        chevron = Theme.Icons(7, dpi);
        Invalidate();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        Smooth(g);
        g.Clear(BackColor);
        name ??= Theme.Font(11, DeviceDpi, FontStyle.Bold);
        status ??= Theme.Font(8.5f, DeviceDpi);
        icons ??= Theme.Icons(14, DeviceDpi);
        chevron ??= Theme.Icons(7, DeviceDpi);
        if (Hovering)
        {
            using var path = RoundRect(new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f), Height * 0.22f);
            using var hi = new SolidBrush(Pressing ? Theme.Surface : Color.FromArgb(30, 30, 30));
            g.FillPath(hi, path);
        }
        var iconBox = new RectangleF(0, 0, Height, Height);
        Centered(g, Glyph.Tv, icons, Theme.Text, iconBox);
        var x = Height;
        using var fmt = new StringFormat(StringFormat.GenericTypographic) { Trimming = StringTrimming.EllipsisCharacter, FormatFlags = StringFormatFlags.NoWrap };
        var nameWidth = Math.Min(g.MeasureString(TvName, name, PointF.Empty, fmt).Width + 2, Width - x - Height * 0.6f);
        using (var white = new SolidBrush(Theme.Text))
        {
            var top = Status.Length == 0 ? (Height - name.Height) / 2f : Height * 0.1f;
            g.DrawString(TvName, name, white, new RectangleF(x, top, nameWidth, name.Height + 2), fmt);
            Centered(g, Glyph.Down, chevron, Theme.Dim, new RectangleF(x + nameWidth + 2, top, Height * 0.4f, name.Height));
        }
        if (Status.Length > 0)
        {
            var dot = Height * 0.13f;
            var y = Height * 0.62f;
            using (var b = new SolidBrush(StatusColor)) g.FillEllipse(b, x + 1, y + (status.Height - dot) / 2f, dot, dot);
            using var dim = new SolidBrush(Theme.Dim);
            g.DrawString(Status, status, dim, new RectangleF(x + dot + Height * 0.1f, y, Width - x, status.Height + 2), fmt);
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            name?.Dispose();
            status?.Dispose();
            icons?.Dispose();
            chevron?.Dispose();
        }
        base.Dispose(disposing);
    }
}

/// <summary>A context menu in the remote's dark colours.</summary>
internal sealed class DarkMenuRenderer : ToolStripProfessionalRenderer
{
    public DarkMenuRenderer() : base(new Colors())
    {
        RoundedEdges = false;
    }

    protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e)
    {
        e.TextColor = e.Item.Enabled ? Theme.Text : Theme.Faint;
        base.OnRenderItemText(e);
    }

    protected override void OnRenderItemCheck(ToolStripItemImageRenderEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        var r = e.ImageRectangle;
        using var pen = new Pen(Theme.Accent, Math.Max(1.5f, r.Height / 8f));
        g.DrawLines(pen, [
            new PointF(r.Left + r.Width * 0.2f, r.Top + r.Height * 0.52f),
            new PointF(r.Left + r.Width * 0.42f, r.Top + r.Height * 0.74f),
            new PointF(r.Left + r.Width * 0.8f, r.Top + r.Height * 0.28f),
        ]);
    }

    protected override void OnRenderArrow(ToolStripArrowRenderEventArgs e)
    {
        e.ArrowColor = Theme.Dim;
        base.OnRenderArrow(e);
    }

    private sealed class Colors : ProfessionalColorTable
    {
        public override Color ToolStripDropDownBackground => Color.FromArgb(32, 32, 32);
        public override Color MenuBorder => Color.FromArgb(56, 56, 56);
        public override Color MenuItemBorder => Color.FromArgb(56, 56, 56);
        public override Color MenuItemSelected => Color.FromArgb(52, 52, 52);
        public override Color MenuItemSelectedGradientBegin => Color.FromArgb(52, 52, 52);
        public override Color MenuItemSelectedGradientEnd => Color.FromArgb(52, 52, 52);
        public override Color ImageMarginGradientBegin => Color.FromArgb(32, 32, 32);
        public override Color ImageMarginGradientMiddle => Color.FromArgb(32, 32, 32);
        public override Color ImageMarginGradientEnd => Color.FromArgb(32, 32, 32);
        public override Color SeparatorDark => Color.FromArgb(56, 56, 56);
        public override Color SeparatorLight => Color.FromArgb(32, 32, 32);
        public override Color CheckBackground => Color.FromArgb(32, 32, 32);
        public override Color CheckSelectedBackground => Color.FromArgb(52, 52, 52);
        public override Color CheckPressedBackground => Color.FromArgb(52, 52, 52);
    }
}
