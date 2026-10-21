using System.Drawing;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using OpenTV.Core;
using OpenTV.Windows.Display;
using OpenTV.Windows.Native;

namespace OpenTV.Windows.App;

/// <summary>
/// The remote: a dark pop-up by the notification area, like OpenTV Companion's on the Mac. It finds and pairs a
/// TV, then shows the click pad, buttons and volume, with TV as Display, the pointer bridge and Send to TV
/// underneath. While it's open the keyboard works as the remote (arrows, Enter, Esc, Space, H, C, M, + and -).
/// Clicking anywhere else hides it.
/// </summary>
internal sealed class RemoteWindow : Form
{
    private const int LogicalWidth = 300;

    private readonly AppServices app;
    private readonly Action quit;
    private readonly ToolTip tips = new() { InitialDelay = 500, ReshowDelay = 100, AutoPopDelay = 12000 };
    private readonly System.Windows.Forms.Timer errorTimer = new() { Interval = 8000 };
    private readonly List<Control> phaseControls = new();
    private readonly List<Font> fonts = new();
    private int fontDpi;

    // Header
    private readonly TvHeader header = new();
    private readonly GlyphButton power = new() { Glyph = Glyph.Power, GlyphColor = Theme.Red, GlyphPoints = 11 };
    private readonly ContextMenuStrip tvMenu = new() { Renderer = new DarkMenuRenderer(), ShowImageMargin = false, ShowCheckMargin = true };

    // Finding a TV
    private readonly Label searchIcon = Plain();
    private readonly Label searchTitle = Plain();
    private readonly Label searchHint = Plain(Theme.Dim);
    private readonly List<GlyphButton> foundButtons = new();
    private string foundSignature = "";
    private readonly TextBox addressBox = Field("Or type the TV's address");
    private readonly GlyphButton addressButton = new() { Label = "Connect", Shape = ButtonShape.Pill };

    // Pairing
    private readonly Label pairTitle = Plain();
    private readonly Label pairHint = Plain(Theme.Dim);
    private readonly TextBox codeBox = Field("");
    private readonly GlyphButton showCode = new() { Label = "Show the code again", Shape = ButtonShape.Pill };
    private readonly LinkLabel otherTv = Link("Choose another TV");
    private string? codeShownFor;
    private bool pairing;

    // The remote
    private readonly NowPlayingCard nowPlaying = new();
    private readonly ClickPad pad = new();
    private readonly GlyphButton back = new() { Glyph = Glyph.Back };
    private readonly GlyphButton home = new() { Glyph = Glyph.Home };
    private readonly GlyphButton control = new() { Glyph = Glyph.Control };
    private readonly GlyphButton playPause = new() { Glyph = Glyph.Play };
    private readonly GlyphButton mute = new() { Glyph = Glyph.Mute };
    private readonly GlyphButton keyboard = new() { Glyph = Glyph.Keyboard };
    private readonly GlyphButton previous = new() { Glyph = Glyph.Previous, GlyphPoints = 11 };
    private readonly VolumePill volume = new();
    private readonly GlyphButton next = new() { Glyph = Glyph.Next, GlyphPoints = 11 };
    private readonly TextBox typeBox = Field("Type on the TV");
    private readonly Label typeHint = Plain(Theme.Dim);
    private bool typing;

    // TV as Display, pointer, Send to TV
    private readonly Panel line1 = Separator();
    private readonly Label displayTitle = Plain();
    private readonly Label usbTag = Plain(Theme.Dim);
    private readonly ComboBox monitorBox = new()
    {
        DropDownStyle = ComboBoxStyle.DropDownList, FlatStyle = FlatStyle.Flat, BackColor = Theme.Field, ForeColor = Theme.Text, TabStop = false,
    };
    private readonly GlyphButton displayButton = new() { Label = "Start", Shape = ButtonShape.Pill };
    private readonly CheckBox soundBox = Check("Sound on TV");
    private readonly Label displayStatus = Plain(Theme.Dim);
    private readonly CheckBox pointerBox = Check("Move the pointer onto the TV");
    private readonly Label pointerHint = Plain(Theme.Dim);
    private readonly GlyphButton sendButton = new() { Glyph = Glyph.Send, Label = "Send to TV", Shape = ButtonShape.Pill, GlyphPoints = 10 };
    private readonly Label sendStatus = Plain(Theme.Dim);

    // Footer
    private readonly Panel line2 = Separator();
    private readonly CheckBox startupBox = Check("Start with Windows");
    private readonly Label keysIcon = Plain(Theme.Dim);
    private readonly LinkLabel quitLink = Link("Quit");
    private readonly Label errorLabel = Plain(Theme.Red);

    private bool updatingChecks;

    public RemoteWindow(AppServices app, Action quit)
    {
        this.app = app;
        this.quit = quit;
        Text = "OpenTV";
        FormBorderStyle = FormBorderStyle.None;
        StartPosition = FormStartPosition.Manual;
        ShowInTaskbar = false;
        TopMost = true;
        AutoScaleMode = AutoScaleMode.None;
        BackColor = Theme.Back;
        ForeColor = Theme.Text;
        DoubleBuffered = true;
        KeyPreview = true;

        searchIcon.Text = Glyph.Tv;
        searchIcon.TextAlign = ContentAlignment.MiddleCenter;
        searchTitle.TextAlign = ContentAlignment.MiddleCenter;
        searchHint.TextAlign = ContentAlignment.TopCenter;
        searchHint.Text = "Make sure the TV is on, OtvOS is installed and it's on the same network as this PC.";
        pairTitle.TextAlign = ContentAlignment.MiddleCenter;
        pairHint.TextAlign = ContentAlignment.TopCenter;
        pairHint.Text = "Type the 4-digit code shown on your TV.";
        codeBox.TextAlign = HorizontalAlignment.Center;
        codeBox.MaxLength = 4;
        typeHint.Text = "Text goes into the box that's selected on the TV. Enter submits.";
        displayTitle.Text = "TV as Display";
        usbTag.Text = "USB";
        usbTag.TextAlign = ContentAlignment.MiddleRight;
        pointerHint.Text = "Push past the top of your screen. On the TV, push into the left edge for Back or the top edge for Control Center; right-click is Back. Ctrl+Esc comes back.";
        keysIcon.Text = Glyph.Keyboard;
        keysIcon.TextAlign = ContentAlignment.MiddleCenter;
        quitLink.TextAlign = ContentAlignment.MiddleRight;
        errorLabel.TextAlign = ContentAlignment.TopCenter;

        tips.SetToolTip(power, "Sleep or wake the TV");
        tips.SetToolTip(back, "Back (Esc)");
        tips.SetToolTip(home, "Home (H)");
        tips.SetToolTip(control, "Control Center (C)");
        tips.SetToolTip(playPause, "Play or pause (Space)");
        tips.SetToolTip(mute, "Mute (M)");
        tips.SetToolTip(keyboard, "Type on the TV");
        tips.SetToolTip(previous, "Previous");
        tips.SetToolTip(next, "Next");
        tips.SetToolTip(volume, "Volume (+ and -)");
        tips.SetToolTip(usbTag, "The TV is plugged into this PC: everything goes over the cable.");
        tips.SetToolTip(sendButton, "Sends the tab open in your browser, or the video playing in a player on this PC, to the TV. Ctrl+Alt+T from anywhere.");
        tips.SetToolTip(keysIcon, "Arrow keys move, Enter selects, Esc goes back, Space plays or pauses, H goes Home, C opens Control Center, M mutes, + and - change the volume.");
        tips.SetToolTip(displayButton, "Shows this PC's screen on the TV. The TV's remote drives the PC meanwhile.");

        Controls.AddRange([header, power, errorLabel]);
        phaseControls.AddRange([
            power, searchIcon, searchTitle, searchHint, addressBox, addressButton,
            pairTitle, pairHint, codeBox, showCode, otherTv,
            nowPlaying, pad, back, home, control, playPause, mute, keyboard, previous, volume, next, typeBox, typeHint,
            line1, displayTitle, usbTag, monitorBox, displayButton, soundBox, displayStatus, pointerBox, pointerHint,
            sendButton, sendStatus, line2, startupBox, keysIcon, quitLink, errorLabel,
        ]);
        foreach (var c in phaseControls) if (!Controls.Contains(c)) Controls.Add(c);

        Wire();
        errorTimer.Tick += (_, _) =>
        {
            errorTimer.Stop();
            errorLabel.Text = "";
            Relayout();
        };
    }

    // ---- Wiring ------------------------------------------------------------------------------------

    private RemoteModel Model => app.Model;

    private void Wire()
    {
        Model.Changed += UpdateView;
        app.Display.Changed += UpdateView;
        app.Bridge.OnTvChanged += onTv =>
        {
            // The pointer went onto the TV: get out of its way.
            if (onTv && Visible) Hide();
        };

        header.Click += (_, _) => ShowTvMenu();
        power.Click += (_, _) => Model.Press(RemoteKey.Power);

        addressButton.Click += (_, _) => AddAddress();
        addressBox.KeyDown += (_, e) =>
        {
            if (e.KeyCode != Keys.Enter) return;
            e.SuppressKeyPress = true;
            AddAddress();
        };

        codeBox.KeyPress += (_, e) =>
        {
            if (!char.IsControl(e.KeyChar) && !char.IsDigit(e.KeyChar)) e.Handled = true;
        };
        codeBox.TextChanged += async (_, _) =>
        {
            if (codeBox.Text.Length == 4 && codeBox.Text.All(char.IsDigit)) await PairAsync(codeBox.Text);
        };
        showCode.Click += (_, _) => _ = Model.ShowCodeAsync();
        otherTv.LinkClicked += (_, _) => Model.Forget();

        nowPlaying.PlayPause += () => Model.Press(RemoteKey.PlayPause);
        pad.Press += key => Model.Press(key);
        back.Click += (_, _) => Model.Press(RemoteKey.Back);
        home.Click += (_, _) => Model.Press(RemoteKey.Home);
        control.Click += (_, _) => Model.Press(RemoteKey.Control);
        playPause.Click += (_, _) => Model.Press(RemoteKey.PlayPause);
        mute.Click += (_, _) => Model.Press(RemoteKey.Mute);
        previous.Click += (_, _) => Model.Press(RemoteKey.Previous);
        next.Click += (_, _) => Model.Press(RemoteKey.Next);
        volume.Press += key => Model.Press(key);
        keyboard.Click += (_, _) => SetTyping(!typing);
        typeBox.TextChanged += (_, _) =>
        {
            if (typing) Model.SetText(typeBox.Text);
        };
        typeBox.KeyDown += (_, e) =>
        {
            if (e.KeyCode == Keys.Enter)
            {
                e.SuppressKeyPress = true;
                Model.Press(RemoteKey.Select);
                SetTyping(false);
            }
            else if (e.KeyCode == Keys.Escape)
            {
                e.SuppressKeyPress = true;
                SetTyping(false);
            }
        };

        displayButton.Click += (_, _) => app.Display.Toggle();
        monitorBox.SelectionChangeCommitted += (_, _) =>
        {
            if (monitorBox.SelectedItem is MonitorItem m) app.SetMirrorMonitor(m.Device);
        };
        soundBox.CheckedChanged += (_, _) =>
        {
            if (!updatingChecks) app.SetSound(soundBox.Checked);
        };
        pointerBox.CheckedChanged += (_, _) =>
        {
            if (!updatingChecks) app.SetPointerBridge(pointerBox.Checked);
        };
        sendButton.Click += async (_, _) => await SendAsync();
        startupBox.CheckedChanged += (_, _) =>
        {
            if (updatingChecks) return;
            try
            {
                StartupRegistration.Enabled = startupBox.Checked;
            }
            catch (Exception e)
            {
                Log.Error("start with Windows", e);
            }
            SyncChecks();
        };
        quitLink.LinkClicked += (_, _) => quit();
    }

    // ---- Showing and hiding ------------------------------------------------------------------------

    /// <summary>When the remote last hid itself, so a click on the tray icon that caused that doesn't reopen it.</summary>
    public long LastHidden { get; private set; }

    public void Toggle(Point? anchor)
    {
        if (Visible)
        {
            Hide();
            return;
        }
        if (Environment.TickCount64 - LastHidden < 300) return;
        ShowNear(anchor);
    }

    /// <summary>Opens the remote by the notification area (or by <paramref name="anchor"/>, the tray icon's click).</summary>
    public void ShowNear(Point? anchor)
    {
        // The cursor can't be on the TV and on the remote at once.
        app.Bridge.ComeBack();
        if (!IsHandleCreated) CreateHandle();
        Model.Activate();
        LoadMonitors();
        SyncChecks();
        UpdateView();
        Place(anchor ?? Cursor.Position, anchor is not null);
        Show();
        Activate();
        ActiveControl = null;
        if (Model.Phase == RemotePhase.Pairing) codeBox.Focus();
        RequestCodeIfNeeded();
    }

    private void Place(Point anchor, bool fromTray)
    {
        var screen = Screen.FromPoint(anchor);
        var work = screen.WorkingArea;
        var bounds = screen.Bounds;
        var margin = S(12);
        int x, y;
        if (!fromTray)
        {
            // Opened again from Start or a shortcut: the usual corner, by the clock.
            x = work.Right - Width - margin;
            y = work.Bottom - Height - margin;
        }
        else if (work.Left > bounds.Left && anchor.X < work.Left)
        {
            x = work.Left + margin;
            y = anchor.Y - Height / 2;
        }
        else if (work.Right < bounds.Right && anchor.X >= work.Right)
        {
            x = work.Right - Width - margin;
            y = anchor.Y - Height / 2;
        }
        else if (work.Top > bounds.Top && anchor.Y < work.Top)
        {
            x = anchor.X - Width / 2;
            y = work.Top + margin;
        }
        else
        {
            x = anchor.X - Width / 2;
            y = work.Bottom - Height - margin;
        }
        x = Math.Clamp(x, work.Left + margin, Math.Max(work.Left + margin, work.Right - Width - margin));
        y = Math.Clamp(y, work.Top + margin, Math.Max(work.Top + margin, work.Bottom - Height - margin));
        Location = new Point(x, y);
    }

    protected override CreateParams CreateParams
    {
        get
        {
            const int CS_DROPSHADOW = 0x20000, WS_EX_TOOLWINDOW = 0x80;
            var cp = base.CreateParams;
            cp.ClassStyle |= CS_DROPSHADOW;
            cp.ExStyle |= WS_EX_TOOLWINDOW;
            return cp;
        }
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        // Windows 11: dark frame and rounded corners (ignored on Windows 10).
        var on = 1;
        Win32.DwmSetWindowAttribute(Handle, Win32.DWMWA_USE_IMMERSIVE_DARK_MODE, ref on, sizeof(int));
        var round = Win32.DWMWCP_ROUND;
        Win32.DwmSetWindowAttribute(Handle, Win32.DWMWA_WINDOW_CORNER_PREFERENCE, ref round, sizeof(int));
        var border = ColorTranslator.ToWin32(Color.FromArgb(56, 56, 56));
        Win32.DwmSetWindowAttribute(Handle, Win32.DWMWA_BORDER_COLOR, ref border, sizeof(int));
        Relayout();
    }

    protected override void OnDeactivate(EventArgs e)
    {
        base.OnDeactivate(e);
        if (tvMenu.Visible || monitorBox.DroppedDown) return;
        Hide();
    }

    protected override void OnVisibleChanged(EventArgs e)
    {
        base.OnVisibleChanged(e);
        if (Visible) return;
        LastHidden = Environment.TickCount64;
        if (typing) SetTyping(false);
    }

    protected override void OnDpiChanged(DpiChangedEventArgs e)
    {
        base.OnDpiChanged(e);
        Relayout();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        if (FormBorderStyle != FormBorderStyle.None) return;
        // Windows 10 has no DWM border colour: draw a hairline so the dark window doesn't melt into dark apps.
        using var pen = new Pen(Color.FromArgb(56, 56, 56));
        e.Graphics.DrawRectangle(pen, 0, 0, ClientSize.Width - 1, ClientSize.Height - 1);
    }

    // ---- Keyboard as the remote --------------------------------------------------------------------

    protected override bool ProcessCmdKey(ref Message msg, Keys keyData)
    {
        if (ActiveControl is TextBox or ComboBox)
        {
            if (keyData == Keys.Escape && ActiveControl == addressBox)
            {
                Hide();
                return true;
            }
            return base.ProcessCmdKey(ref msg, keyData);
        }
        if (!Model.IsPaired)
        {
            if (keyData == Keys.Escape)
            {
                Hide();
                return true;
            }
            return base.ProcessCmdKey(ref msg, keyData);
        }
        if (keyData == (Keys.Oemplus | Keys.Shift)) keyData = Keys.Oemplus;
        if (keyData == Keys.T)
        {
            SetTyping(true);
            return true;
        }
        RemoteKey? key = keyData switch
        {
            Keys.Up => RemoteKey.Up,
            Keys.Down => RemoteKey.Down,
            Keys.Left => RemoteKey.Left,
            Keys.Right => RemoteKey.Right,
            Keys.Enter => RemoteKey.Select,
            Keys.Escape or Keys.Back or Keys.BrowserBack => RemoteKey.Back,
            Keys.Space or Keys.MediaPlayPause => RemoteKey.PlayPause,
            Keys.H or Keys.BrowserHome => RemoteKey.Home,
            Keys.C => RemoteKey.Control,
            Keys.M or Keys.VolumeMute => RemoteKey.Mute,
            Keys.Oemplus or Keys.Add or Keys.VolumeUp => RemoteKey.VolUp,
            Keys.OemMinus or Keys.Subtract or Keys.VolumeDown => RemoteKey.VolDown,
            Keys.MediaNextTrack => RemoteKey.Next,
            Keys.MediaPreviousTrack => RemoteKey.Previous,
            _ => null,
        };
        if (key is not { } k) return base.ProcessCmdKey(ref msg, keyData);
        Model.Press(k);
        return true;
    }

    // ---- Actions -----------------------------------------------------------------------------------

    private void AddAddress()
    {
        var text = addressBox.Text.Trim();
        if (text.Length == 0) return;
        Model.Add(text);
        addressBox.Clear();
    }

    private async Task PairAsync(string code)
    {
        if (pairing) return;
        pairing = true;
        codeBox.Enabled = false;
        try
        {
            if (!await Model.PairAsync(code))
            {
                codeBox.Clear();
            }
        }
        finally
        {
            pairing = false;
            codeBox.Enabled = true;
            if (Model.Phase == RemotePhase.Pairing && Visible) codeBox.Focus();
            else ActiveControl = null;
        }
    }

    private void RequestCodeIfNeeded()
    {
        // Put the code on the TV as soon as the remote shows the pairing screen, once per TV.
        if (!Visible || Model.Phase != RemotePhase.Pairing || Model.Tv is not { } tv || codeShownFor == tv.Key) return;
        codeShownFor = tv.Key;
        _ = Model.ShowCodeAsync();
    }

    private void SetTyping(bool on)
    {
        if (typing == on) return;
        typing = on;
        typeBox.Text = "";
        Relayout();
        if (on) typeBox.Focus();
        else ActiveControl = null;
    }

    private async Task SendAsync()
    {
        sendStatus.Text = "Sending…";
        Relayout();
        sendStatus.Text = await app.Sender.SendAsync();
        Relayout();
    }

    private void ShowTvMenu()
    {
        tvMenu.Items.Clear();
        foreach (var tv in Model.Found)
        {
            var current = Model.Tv is { } t && (t.Host == tv.Host || (t.TvId is not null && t.TvId == tv.TvId));
            var item = new ToolStripMenuItem(tv.Name) { Checked = current };
            item.Click += (_, _) => Model.Choose(tv);
            tvMenu.Items.Add(item);
        }
        if (Model.Found.Count == 0)
        {
            tvMenu.Items.Add(new ToolStripMenuItem("Looking for TVs…") { Enabled = false });
        }
        if (Model.Tv is not null)
        {
            tvMenu.Items.Add(new ToolStripSeparator());
            var forget = new ToolStripMenuItem("Forget This TV");
            forget.Click += (_, _) => Model.Forget();
            tvMenu.Items.Add(forget);
        }
        tvMenu.Font = Font;
        tvMenu.Show(header, new Point(0, header.Height));
    }

    private void LoadMonitors()
    {
        monitorBox.Items.Clear();
        try
        {
            foreach (var (device, label, _) in ScreenCapture.Monitors()) monitorBox.Items.Add(new MonitorItem(device, label));
        }
        catch (Exception e)
        {
            Log.Error("listing monitors", e);
        }
        var chosen = app.Store.Settings.MirrorMonitor;
        var index = monitorBox.Items.Cast<MonitorItem>().ToList().FindIndex(m => m.Device == chosen);
        if (monitorBox.Items.Count > 0) monitorBox.SelectedIndex = Math.Max(0, index);
    }

    private void SyncChecks()
    {
        updatingChecks = true;
        soundBox.Checked = app.Display.Sound;
        pointerBox.Checked = app.Store.Settings.PointerBridge;
        try
        {
            startupBox.Checked = StartupRegistration.Enabled;
        }
        catch (Exception)
        {
            startupBox.Checked = false;
        }
        updatingChecks = false;
    }

    // ---- State → controls --------------------------------------------------------------------------

    public void UpdateView()
    {
        if (IsDisposed) return;
        var phase = Model.Phase;
        header.TvName = Model.Tv?.Name ?? (Model.Found.Count > 0 ? "Choose a TV" : "OpenTV");
        header.Status = Model.Tv is null ? "" : phase switch
        {
            RemotePhase.Connected => Model.Route?.Usb == true ? "Connected · USB" : "Connected",
            RemotePhase.Offline => "Not responding",
            RemotePhase.Pairing => "Not connected",
            _ => "Searching",
        };
        header.StatusColor = phase switch
        {
            RemotePhase.Connected => Theme.Green,
            RemotePhase.Offline => Theme.Red,
            _ => Theme.Orange,
        };
        header.Invalidate();

        searchTitle.Text = Model.Found.Count == 0 ? "Looking for your TV…" : "Choose your TV";
        pairTitle.Text = $"Connect to {Model.Tv?.Name ?? "your TV"}";
        nowPlaying.Value = Model.NowPlaying;
        playPause.Glyph = Model.NowPlaying is { Playing: true } ? Glyph.Pause : Glyph.Play;
        playPause.Invalidate();

        var display = app.Display;
        displayButton.Label = display.Running ? "Stop" : "Start";
        displayButton.Invalidate();
        displayStatus.Text = display.Status;
        soundBox.Enabled = !display.Running;
        monitorBox.Enabled = !display.Running;

        if (Model.Error is { } error)
        {
            Model.Error = null;
            errorLabel.Text = error;
            errorTimer.Stop();
            errorTimer.Start();
        }

        if (phase != RemotePhase.Pairing) codeShownFor = null;
        RebuildFound();
        Relayout();
        RequestCodeIfNeeded();
    }

    private void RebuildFound()
    {
        var signature = string.Join("\n", Model.Found.Select(t => t.Host + "|" + t.Name));
        if (signature == foundSignature) return;
        foundSignature = signature;
        foreach (var b in foundButtons)
        {
            Controls.Remove(b);
            b.Dispose();
        }
        foundButtons.Clear();
        foreach (var tv in Model.Found)
        {
            var b = new GlyphButton { Glyph = Glyph.Tv, Label = tv.Name, Shape = ButtonShape.Rounded, GlyphPoints = 12, Visible = false };
            b.Click += (_, _) => Model.Choose(tv);
            b.SetFonts(DeviceDpi);
            foundButtons.Add(b);
            Controls.Add(b);
        }
    }

    // ---- Layout ------------------------------------------------------------------------------------

    private int S(double v) => (int)Math.Round(v * DeviceDpi / 96.0);

    private Font MakeFont(float points, FontStyle style = FontStyle.Regular)
    {
        var f = Theme.Font(points, DeviceDpi, style);
        fonts.Add(f);
        return f;
    }

    private void ApplyFonts()
    {
        if (fontDpi == DeviceDpi) return;
        fontDpi = DeviceDpi;
        var old = fonts.ToList();
        fonts.Clear();
        var body = MakeFont(9.5f);
        var small = MakeFont(8.5f);
        Font = body;
        searchIcon.Font = new Font(Theme.IconFamily, 34f * DeviceDpi / 72f, FontStyle.Regular, GraphicsUnit.Pixel);
        fonts.Add(searchIcon.Font);
        searchTitle.Font = MakeFont(14, FontStyle.Bold);
        pairTitle.Font = searchTitle.Font;
        foreach (var l in new Control[] { searchHint, pairHint, typeHint, displayStatus, pointerHint, sendStatus, usbTag, errorLabel }) l.Font = small;
        foreach (var c in new Control[] { addressBox, typeBox, monitorBox, soundBox, pointerBox, startupBox, otherTv, quitLink }) c.Font = body;
        codeBox.Font = MakeFont(20, FontStyle.Bold);
        displayTitle.Font = MakeFont(10, FontStyle.Bold);
        keysIcon.Font = new Font(Theme.IconFamily, 11f * DeviceDpi / 72f, FontStyle.Regular, GraphicsUnit.Pixel);
        fonts.Add(keysIcon.Font);

        header.SetFonts(DeviceDpi);
        pad.SetFonts(DeviceDpi);
        volume.SetFonts(DeviceDpi);
        nowPlaying.SetFonts(DeviceDpi);
        foreach (var b in new[] { power, addressButton, showCode, back, home, control, playPause, mute, keyboard, previous, next, displayButton, sendButton }.Concat(foundButtons))
        {
            b.SetFonts(DeviceDpi);
        }
        foreach (var f in old) f.Dispose();
    }

    /// <summary>Stacks whatever the current state shows from top to bottom and sizes the window to fit.</summary>
    private void Relayout()
    {
        if (!IsHandleCreated || IsDisposed) return;
        ApplyFonts();
        SuspendLayout();
        // Controls are shown or hidden once, at the end: hiding and re-showing would take focus from a text box.
        var shown = new HashSet<Control>();

        var pad16 = S(16);
        var width = S(LogicalWidth);
        var inner = width - 2 * pad16;
        var y = S(14);

        void Put(Control c, int x, int top, int w, int h)
        {
            c.SetBounds(x, top, w, h);
            shown.Add(c);
        }

        int Para(Label l, int top, int gapAfter, int indent = 0)
        {
            if (l.Text.Length == 0) return top;
            l.Padding = new Padding(indent, 0, 0, 0);
            var h = TextRenderer.MeasureText(l.Text, l.Font, new Size(inner - indent, int.MaxValue), TextFormatFlags.WordBreak).Height;
            Put(l, pad16, top, inner, h);
            return top + h + gapAfter;
        }

        // Header: the TV's name and state, and power once paired.
        var headerH = S(42);
        var paired = Model.IsPaired;
        Put(header, pad16 - S(6), y, inner + S(6) - (paired ? S(42) : 0), headerH);
        if (paired) Put(power, width - pad16 - S(34), y + (headerH - S(34)) / 2, S(34), S(34));
        y += headerH + S(14);

        switch (Model.Phase)
        {
            case RemotePhase.Searching:
                Put(searchIcon, pad16, y, inner, S(56));
                y += S(60);
                y = Para(searchTitle, y, S(6));
                if (Model.Found.Count == 0) y = Para(searchHint, y, S(12));
                foreach (var b in foundButtons)
                {
                    Put(b, pad16, y, inner, S(46));
                    y += S(46) + S(8);
                }
                y += S(10);
                Put(addressBox, pad16, y + (S(32) - addressBox.PreferredHeight) / 2, inner - S(92), addressBox.PreferredHeight);
                Put(addressButton, width - pad16 - S(84), y, S(84), S(32));
                y += S(32) + S(16);
                break;

            case RemotePhase.Pairing:
                y += S(6);
                y = Para(pairTitle, y, S(6));
                y = Para(pairHint, y, S(14));
                Put(codeBox, pad16 + inner / 2 - S(70), y, S(140), codeBox.PreferredHeight);
                y += codeBox.PreferredHeight + S(16);
                Put(showCode, pad16 + inner / 2 - S(80), y, S(160), S(32));
                y += S(32) + S(10);
                Put(otherTv, pad16, y, inner, S(22));
                otherTv.TextAlign = ContentAlignment.MiddleCenter;
                y += S(22) + S(16);
                break;

            default:
                if (Model.NowPlaying is not null)
                {
                    Put(nowPlaying, pad16, y, inner, S(56));
                    y += S(56) + S(14);
                }
                var padSize = S(176);
                Put(pad, (width - padSize) / 2, y, padSize, padSize);
                y += padSize + S(16);

                var b = S(52);
                var gap = S(18);
                var gridX = (width - 3 * b - 2 * gap) / 2;
                Put(back, gridX, y, b, b);
                Put(home, gridX + b + gap, y, b, b);
                Put(control, gridX + 2 * (b + gap), y, b, b);
                y += b + S(14);
                Put(playPause, gridX, y, b, b);
                Put(mute, gridX + b + gap, y, b, b);
                Put(keyboard, gridX + 2 * (b + gap), y, b, b);
                y += b + S(16);

                var v = S(44);
                Put(previous, pad16, y, v, v);
                Put(volume, pad16 + v + S(12), y, inner - 2 * (v + S(12)), v);
                Put(next, width - pad16 - v, y, v, v);
                y += v + S(14);

                if (typing)
                {
                    Put(typeBox, pad16, y, inner, typeBox.PreferredHeight);
                    y += typeBox.PreferredHeight + S(4);
                    y = Para(typeHint, y, S(12));
                }

                // TV as Display
                Put(line1, 0, y, width, 1);
                y += S(12);
                Put(displayTitle, pad16, y, inner - S(60), S(22));
                if (Model.Route?.Usb == true) Put(usbTag, width - pad16 - S(60), y, S(60), S(22));
                y += S(26);
                var startW = S(78);
                if (monitorBox.Items.Count > 1)
                {
                    Put(monitorBox, pad16, y + (S(30) - monitorBox.PreferredHeight) / 2, inner - startW - S(8), monitorBox.PreferredHeight);
                    Put(displayButton, width - pad16 - startW, y, startW, S(30));
                    y += S(30) + S(6);
                    Put(soundBox, pad16, y, inner, S(24));
                    y += S(26);
                }
                else
                {
                    Put(soundBox, pad16, y + (S(30) - S(24)) / 2, inner - startW - S(8), S(24));
                    Put(displayButton, width - pad16 - startW, y, startW, S(30));
                    y += S(34);
                }
                y = Para(displayStatus, y, S(6));
                y += S(6);

                // The pointer and Send to TV
                Put(pointerBox, pad16, y, inner, S(24));
                y += S(24);
                y = Para(pointerHint, y, S(12), S(18));

                Put(sendButton, pad16, y, S(118), S(30));
                Put(sendStatus, pad16 + S(126), y, inner - S(126), S(30));
                sendStatus.TextAlign = ContentAlignment.MiddleLeft;
                y += S(30) + S(12);
                break;
        }

        if (errorLabel.Text.Length > 0) y = Para(errorLabel, y, S(10));

        // Footer
        Put(line2, 0, y, width, 1);
        y += 1;
        var footerH = S(38);
        Put(startupBox, pad16, y + (footerH - S(24)) / 2, S(170), S(24));
        Put(keysIcon, pad16 + S(176), y, S(30), footerH);
        Put(quitLink, width - pad16 - S(60), y, S(60), footerH);
        y += footerH;

        foreach (var c in phaseControls.Concat(foundButtons)) c.Visible = shown.Contains(c);
        ClientSize = new Size(width, y);
        ResumeLayout(false);
        Invalidate();

        // Stay inside the screen when the window grows (pairing finished, something started playing).
        if (Visible)
        {
            var work = Screen.FromControl(this).WorkingArea;
            var margin = S(12);
            if (Bottom > work.Bottom - margin) Top = Math.Max(work.Top + margin, work.Bottom - margin - Height);
        }
    }

    // ---- Small factories ---------------------------------------------------------------------------

    private static Label Plain(Color? color = null) => new()
    {
        AutoSize = false, BackColor = Theme.Back, ForeColor = color ?? Theme.Text, UseMnemonic = false, Visible = false,
    };

    private static TextBox Field(string placeholder) => new()
    {
        BorderStyle = BorderStyle.FixedSingle, BackColor = Theme.Field, ForeColor = Theme.Text, PlaceholderText = placeholder, Visible = false,
    };

    private static CheckBox Check(string text)
    {
        var c = new CheckBox
        {
            Text = text, AutoSize = false, ForeColor = Theme.Text, BackColor = Theme.Back, FlatStyle = FlatStyle.Flat,
            TextAlign = ContentAlignment.MiddleLeft, TabStop = false, UseMnemonic = false, Visible = false,
        };
        c.FlatAppearance.BorderColor = Theme.Faint;
        c.FlatAppearance.CheckedBackColor = Theme.Back;
        c.FlatAppearance.MouseOverBackColor = Theme.Back;
        c.FlatAppearance.MouseDownBackColor = Theme.Back;
        return c;
    }

    private static LinkLabel Link(string text) => new()
    {
        Text = text, AutoSize = false, BackColor = Theme.Back, LinkColor = Theme.Dim, ActiveLinkColor = Theme.Text,
        VisitedLinkColor = Theme.Dim, LinkBehavior = LinkBehavior.HoverUnderline, TabStop = false, Visible = false,
    };

    private static Panel Separator() => new() { BackColor = Theme.Line, Visible = false };

    private sealed record MonitorItem(string Device, string Label)
    {
        public override string ToString() => Label;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            Model.Changed -= UpdateView;
            app.Display.Changed -= UpdateView;
            tips.Dispose();
            errorTimer.Dispose();
            tvMenu.Dispose();
            foreach (var f in fonts) f.Dispose();
        }
        base.Dispose(disposing);
    }
}
