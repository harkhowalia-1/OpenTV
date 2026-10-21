using System.Drawing;
using System.Reflection;
using System.Windows.Forms;
using OpenTV.Core;
using OpenTV.Windows.Display;
using OpenTV.Windows.Native;
using OpenTV.Windows.Pointer;

namespace OpenTV.Windows.App;

/// <summary>The app's long-lived pieces, started once at launch and kept connected in the background.</summary>
internal sealed class AppServices : IDisposable
{
    public AppServices(SynchronizationContext ui)
    {
        Ui = ui;
        Store = new TvStore(TvStore.DefaultPath) { Protect = Win32.Protect, Unprotect = Win32.Unprotect };
        Model = new RemoteModel(Store, ui);
        Display = new DisplayStreamer(Model, ui)
        {
            Sound = Store.Settings.SoundOnTv,
            Monitor = Store.Settings.MirrorMonitor,
        };
        Bridge = new PointerBridge(Model.Channel, ui);
        Usb = new AdbLink();
        Usb.Changed += tv => ui.Post(_ => Model.UsbTv = tv, null);
        Sender = new SendToTv(Model);
    }

    public SynchronizationContext Ui { get; }
    public TvStore Store { get; }
    public RemoteModel Model { get; }
    public DisplayStreamer Display { get; }
    public PointerBridge Bridge { get; }
    public AdbLink Usb { get; }
    public SendToTv Sender { get; }

    public void Start()
    {
        // Stay connected from launch: the pointer bridge and the TV's pushes need the live channel.
        Model.Activate();
        if (Usb.Available)
        {
            Log.Write($"adb: {Usb.AdbPath}");
            Usb.Start();
        }
        if (Store.Settings.PointerBridge) Bridge.Start();
        try
        {
            StartupRegistration.Refresh();
        }
        catch (Exception e)
        {
            Log.Error("start with Windows", e);
        }
    }

    public void SetPointerBridge(bool on)
    {
        Store.Settings.PointerBridge = on;
        Store.Save();
        if (on)
        {
            Bridge.Start();
        }
        else
        {
            Bridge.ComeBack();
            Bridge.Stop();
        }
    }

    public void SetSound(bool on)
    {
        Display.Sound = on;
        Store.Settings.SoundOnTv = on;
        Store.Save();
    }

    public void SetMirrorMonitor(string? device)
    {
        Display.Monitor = device;
        Store.Settings.MirrorMonitor = device;
        Store.Save();
    }

    public void Dispose()
    {
        try
        {
            Bridge.ComeBack();
            Bridge.Dispose();
        }
        finally
        {
            CursorHider.Show();
        }
        if (Display.Running) Display.Stop();
        Usb.Dispose();
        Sender.Dispose();
        Model.Dispose();
    }
}

/// <summary>
/// OpenTV for Windows: an icon in the notification area. Click it for the remote; right-click for Send to TV,
/// TV as Display and settings. Ctrl+Alt+T sends the open tab or video to the TV from anywhere, Ctrl+Alt+R opens
/// the remote.
/// </summary>
internal sealed class TrayApp : ApplicationContext
{
    private readonly AppServices app;
    private readonly RemoteWindow remote;
    private readonly NotifyIcon tray;
    private readonly ContextMenuStrip menu;
    private readonly HotKeys hotKeys;
    private readonly Icon icon;
    private readonly ToolStripMenuItem displayItem, pointerItem, startupItem, sendItem;
    private bool exiting;

    public TrayApp(bool showRemote, SingleInstance instance)
    {
        // The UI thread's context, before anything that reports back through it is created.
        if (SynchronizationContext.Current is not WindowsFormsSynchronizationContext)
        {
            SynchronizationContext.SetSynchronizationContext(new WindowsFormsSynchronizationContext());
        }
        var ui = SynchronizationContext.Current!;
        app = new AppServices(ui);
        remote = new RemoteWindow(app, Quit);
        icon = LoadIcon();
        remote.Icon = icon;

        menu = new ContextMenuStrip { Renderer = new DarkMenuRenderer(), ShowImageMargin = false, ShowCheckMargin = true };
        var open = new ToolStripMenuItem("Open Remote", null, (_, _) => remote.ShowNear(null)) { ShortcutKeyDisplayString = "Ctrl+Alt+R" };
        open.Font = new Font(open.Font, FontStyle.Bold);
        sendItem = new ToolStripMenuItem("Send to TV", null, async (_, _) => await SendAsync()) { ShortcutKeyDisplayString = "Ctrl+Alt+T" };
        displayItem = new ToolStripMenuItem("Show This PC on the TV", null, (_, _) => app.Display.Toggle());
        pointerItem = new ToolStripMenuItem("Move the Pointer onto the TV", null, (_, _) => app.SetPointerBridge(!app.Store.Settings.PointerBridge));
        startupItem = new ToolStripMenuItem("Start with Windows", null, (_, _) => ToggleStartup());
        var logs = new ToolStripMenuItem("Open Log Folder", null, (_, _) => Moonlight.Open(Log.Folder));
        var quit = new ToolStripMenuItem("Quit OpenTV", null, (_, _) => Quit());
        menu.Items.AddRange([
            open, sendItem, new ToolStripSeparator(), displayItem, pointerItem, new ToolStripSeparator(),
            startupItem, logs, new ToolStripSeparator(), quit,
        ]);
        menu.Opening += (_, _) => SyncMenu();

        tray = new NotifyIcon { Icon = icon, Text = "OpenTV", ContextMenuStrip = menu, Visible = true };
        tray.MouseClick += (_, e) =>
        {
            if (e.Button == MouseButtons.Left) remote.Toggle(Cursor.Position);
        };

        hotKeys = new HotKeys();
        hotKeys.Add(Keys.T, () => _ = SendAsync());
        hotKeys.Add(Keys.R, () => remote.ShowNear(null));

        app.Model.Changed += UpdateTray;
        app.Display.Changed += UpdateTray;
        instance.Listen(() => ui.Post(_ => remote.ShowNear(null), null));

        app.Start();
        UpdateTray();
        Log.Write($"OpenTV {Application.ProductVersion} started" + (showRemote ? "" : " in the background"));
        if (showRemote) ui.Post(_ => remote.ShowNear(null), null);
    }

    private static Icon LoadIcon()
    {
        try
        {
            using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("OpenTV.ico");
            if (stream is not null) return new Icon(stream, SystemInformation.SmallIconSize);
        }
        catch (Exception e)
        {
            Log.Error("loading the icon", e);
        }
        return (Icon)SystemIcons.Application.Clone();
    }

    private void UpdateTray()
    {
        if (exiting) return;
        var model = app.Model;
        var state = model.Tv is null ? "Looking for your TV" : model.Phase switch
        {
            RemotePhase.Connected => app.Display.Running ? $"Showing this PC on {model.Tv.Name}" : $"Connected to {model.Tv.Name}",
            RemotePhase.Offline => $"{model.Tv.Name} isn't responding",
            RemotePhase.Pairing => $"Not connected to {model.Tv.Name}",
            _ => "Looking for your TV",
        };
        var text = "OpenTV · " + state;
        tray.Text = text.Length > 120 ? text[..120] : text;
    }

    private void SyncMenu()
    {
        var paired = app.Model.IsPaired;
        sendItem.Enabled = paired;
        displayItem.Enabled = paired;
        displayItem.Text = app.Display.Running ? "Stop Showing This PC on the TV" : "Show This PC on the TV";
        pointerItem.Checked = app.Store.Settings.PointerBridge;
        try
        {
            startupItem.Checked = StartupRegistration.Enabled;
        }
        catch (Exception)
        {
            startupItem.Checked = false;
        }
    }

    private void ToggleStartup()
    {
        try
        {
            StartupRegistration.Enabled = !StartupRegistration.Enabled;
        }
        catch (Exception e)
        {
            Log.Error("start with Windows", e);
        }
    }

    private async Task SendAsync()
    {
        var result = await app.Sender.SendAsync();
        if (!remote.Visible) tray.ShowBalloonTip(3000, "Send to TV", result, ToolTipIcon.None);
    }

    private void Quit() => ExitThread();

    protected override void ExitThreadCore()
    {
        if (!exiting)
        {
            exiting = true;
            Log.Write("quitting");
            tray.Visible = false;
            app.Model.Changed -= UpdateTray;
            app.Display.Changed -= UpdateTray;
            hotKeys.Dispose();
            remote.Hide();
            app.Dispose();
            remote.Dispose();
            tray.Dispose();
            menu.Dispose();
            icon.Dispose();
        }
        base.ExitThreadCore();
    }
}
