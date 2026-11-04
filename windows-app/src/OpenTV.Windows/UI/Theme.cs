using System.Drawing.Drawing2D;
using System.Drawing.Text;

namespace OpenTV.Windows.UI;

/// <summary>The remote's look: near-black like the Siri Remote app, round grey buttons, white symbols.</summary>
internal static class Theme
{
    public static readonly Color Background = Color.FromArgb(18, 18, 20);
    public static readonly Color Surface = Color.FromArgb(40, 40, 43);        // white at 11% over the background
    public static readonly Color SurfaceHover = Color.FromArgb(52, 52, 56);
    public static readonly Color SurfacePressed = Color.FromArgb(76, 76, 80);
    public static readonly Color Divider = Color.FromArgb(34, 34, 37);
    public static readonly Color Text = Color.White;
    public static readonly Color Secondary = Color.FromArgb(142, 142, 147);
    public static readonly Color Tertiary = Color.FromArgb(99, 99, 102);
    public static readonly Color Green = Color.FromArgb(48, 209, 88);
    public static readonly Color Red = Color.FromArgb(255, 69, 58);
    public static readonly Color Orange = Color.FromArgb(255, 159, 10);
    public static readonly Color Blue = Color.FromArgb(10, 132, 255);

    /// <summary>Windows 11's symbol font, or Windows 10's (same code points for everything used here).</summary>
    public static readonly string SymbolFont = FontInstalled("Segoe Fluent Icons") ? "Segoe Fluent Icons" : "Segoe MDL2 Assets";
    public static readonly string TextFont = FontInstalled("Segoe UI Variable Text") ? "Segoe UI Variable Text" : "Segoe UI";

    private static bool FontInstalled(string name)
    {
        try
        {
            using var fonts = new InstalledFontCollection();
            return fonts.Families.Any(f => f.Name.Equals(name, StringComparison.OrdinalIgnoreCase));
        }
        catch (Exception)
        {
            return false;
        }
    }

    // Segoe MDL2 / Fluent Icons code points.
    public static class Glyph
    {
        public const string Power = "", Home = "", Back = "", Play = "", Pause = "",
            Mute = "", Volume = "", Keyboard = "", Previous = "", Next = "",
            ControlCenter = "", Tv = "", Up = "", Down = "", Left = "", Right = "",
            ChevronDown = "", ChevronRight = "", Check = "", Send = "", Mouse = "",
            Music = "", Plus = "", Minus = "", Usb = "", Info = "", Mirror = "";
    }

    public static Font Ui(float pt, FontStyle style = FontStyle.Regular) => new(TextFont, pt, style, GraphicsUnit.Point);
    public static Font Bold(float pt) => new(FontInstalled("Segoe UI Variable Display") ? "Segoe UI Variable Display" : "Segoe UI Semibold", pt, FontStyle.Regular, GraphicsUnit.Point);
    public static Font Symbols(float px) => new(SymbolFont, px, FontStyle.Regular, GraphicsUnit.Pixel);

    public static GraphicsPath RoundRect(RectangleF r, float radius)
    {
        var p = new GraphicsPath();
        var d = Math.Min(radius * 2, Math.Min(r.Width, r.Height));
        if (d <= 0)
        {
            p.AddRectangle(r);
            return p;
        }
        p.AddArc(r.X, r.Y, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }

    public static void Smooth(Graphics g)
    {
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
        g.PixelOffsetMode = PixelOffsetMode.HighQuality;
    }

    /// <summary>Draws text centred in a rectangle (GDI, which renders Segoe best on Windows).</summary>
    public static void Centered(Graphics g, string text, Font font, Color color, Rectangle r) =>
        TextRenderer.DrawText(g, text, font, r, color,
            TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding | TextFormatFlags.SingleLine);
}

/// <summary>Dark menus for the TV picker, the monitor picker and the tray icon.</summary>
internal sealed class DarkMenuRenderer : ToolStripProfessionalRenderer
{
    public DarkMenuRenderer() : base(new DarkColors()) => RoundedEdges = false;

    protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e)
    {
        e.TextColor = e.Item.Enabled ? Theme.Text : Theme.Tertiary;
        base.OnRenderItemText(e);
    }

    protected override void OnRenderItemCheck(ToolStripItemImageRenderEventArgs e)
    {
        using var f = Theme.Symbols(12 * e.Item.Owner!.DeviceDpi / 96f);
        Theme.Centered(e.Graphics, Theme.Glyph.Check, f, Theme.Text, e.ImageRectangle);
    }

    protected override void OnRenderArrow(ToolStripArrowRenderEventArgs e)
    {
        e.ArrowColor = Theme.Secondary;
        base.OnRenderArrow(e);
    }

    private sealed class DarkColors : ProfessionalColorTable
    {
        public override Color ToolStripDropDownBackground => Color.FromArgb(32, 32, 35);
        public override Color MenuBorder => Color.FromArgb(58, 58, 62);
        public override Color MenuItemBorder => Color.FromArgb(58, 58, 62);
        public override Color MenuItemSelected => Color.FromArgb(52, 52, 56);
        public override Color MenuItemSelectedGradientBegin => Color.FromArgb(52, 52, 56);
        public override Color MenuItemSelectedGradientEnd => Color.FromArgb(52, 52, 56);
        public override Color ImageMarginGradientBegin => Color.FromArgb(32, 32, 35);
        public override Color ImageMarginGradientMiddle => Color.FromArgb(32, 32, 35);
        public override Color ImageMarginGradientEnd => Color.FromArgb(32, 32, 35);
        public override Color SeparatorDark => Color.FromArgb(58, 58, 62);
        public override Color SeparatorLight => Color.FromArgb(32, 32, 35);
        public override Color CheckBackground => Color.Transparent;
        public override Color CheckSelectedBackground => Color.Transparent;
        public override Color CheckPressedBackground => Color.Transparent;
    }
}
