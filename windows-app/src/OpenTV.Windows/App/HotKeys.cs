using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace OpenTV.Windows.App;

/// <summary>
/// System-wide shortcuts: Ctrl+Alt+T sends the open tab or video to the TV, Ctrl+Alt+R opens the remote.
/// If another app already owns a combination, that shortcut is simply left out.
/// </summary>
internal sealed class HotKeys : NativeWindow, IDisposable
{
    private const int WM_HOTKEY = 0x0312;
    private const uint MOD_ALT = 0x1, MOD_CONTROL = 0x2, MOD_NOREPEAT = 0x4000;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

    [DllImport("user32.dll")]
    private static extern bool UnregisterHotKey(IntPtr hWnd, int id);

    private readonly Dictionary<int, Action> actions = new();

    public HotKeys() => CreateHandle(new CreateParams { Caption = "OpenTV hotkeys" });

    /// <summary>Ctrl+Alt+<paramref name="key"/>. Returns false when another app already has it.</summary>
    public bool Add(Keys key, Action action)
    {
        var id = actions.Count + 1;
        if (!RegisterHotKey(Handle, id, MOD_CONTROL | MOD_ALT | MOD_NOREPEAT, (uint)key))
        {
            Log.Write($"hotkey Ctrl+Alt+{key} is taken ({Marshal.GetLastWin32Error()})");
            return false;
        }
        actions[id] = action;
        return true;
    }

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == WM_HOTKEY && actions.TryGetValue((int)m.WParam, out var action))
        {
            action();
            return;
        }
        base.WndProc(ref m);
    }

    public void Dispose()
    {
        foreach (var id in actions.Keys) UnregisterHotKey(Handle, id);
        actions.Clear();
        DestroyHandle();
    }
}

/// <summary>One OpenTV per Windows user: opening it again shows the running one's remote.</summary>
internal sealed class SingleInstance : IDisposable
{
    private const string MutexName = @"Local\OpenTV.SingleInstance";
    private const string ShowEventName = @"Local\OpenTV.ShowRemote";

    private readonly Mutex mutex;
    private readonly EventWaitHandle show;
    private RegisteredWaitHandle? wait;

    private SingleInstance(Mutex mutex, EventWaitHandle show)
    {
        this.mutex = mutex;
        this.show = show;
    }

    /// <summary>The instance for this process, or null after asking the one already running to show itself.</summary>
    public static SingleInstance? Claim()
    {
        var mutex = new Mutex(initiallyOwned: true, MutexName, out var created);
        var show = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);
        if (created) return new SingleInstance(mutex, show);
        show.Set();
        show.Dispose();
        mutex.Dispose();
        return null;
    }

    /// <summary>Calls <paramref name="onShow"/> (on a pool thread) whenever OpenTV is opened again.</summary>
    public void Listen(Action onShow)
    {
        wait = ThreadPool.RegisterWaitForSingleObject(show, (_, _) => onShow(), null, Timeout.Infinite, executeOnlyOnce: false);
    }

    public void Dispose()
    {
        wait?.Unregister(null);
        show.Dispose();
        try
        {
            mutex.ReleaseMutex();
        }
        catch (ApplicationException)
        {
        }
        mutex.Dispose();
    }
}
