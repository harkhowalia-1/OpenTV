using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using OpenTV.Core;
using OpenTV.Windows.Native;
using static OpenTV.Windows.Native.Win32;

namespace OpenTV.Windows.Pointer;

/// <summary>
/// Universal Control for the TV: push the cursor past the top edge of the topmost screen and it carries on
/// onto the TV (the TV shows its own cursor; hovering highlights, clicking selects, scrolling swipes, typing goes
/// into the TV's text field, right-click is Back). Push it down off the bottom of the TV and it comes back to
/// Windows where it left. Ctrl+Esc always brings it back.
///
/// How: low-level mouse and keyboard hooks on a thread of their own (they must answer within a few ms), plus raw
/// mouse input to feel the "push" against the top edge, where the cursor itself can't move any further. While on
/// the TV the Windows cursor is parked in the middle of the screen it left, hidden, and every movement the hook
/// sees is swallowed and sent as a delta, so the TV cursor moves with the user's own Windows pointer speed and
/// acceleration. Moves are coalesced to 120 Hz.
/// </summary>
internal sealed class PointerBridge : IDisposable
{
    /// <summary>Marks input this app injects itself (TV remote keys while mirroring), so the hooks let it pass.</summary>
    public static readonly UIntPtr InjectedMarker = (UIntPtr)0x4F54564Eu;   // "OTVN"

    private const uint WmAppReturn = WM_APP + 1, WmAppStop = WM_APP + 2;
    private const int PushThreshold = 120;          // raw mouse counts pushed against the edge
    private static readonly long DwellTicks = Stopwatch.Frequency / 10;   // ...after resting there 100 ms

    private readonly ControlChannel channel;
    private readonly SynchronizationContext ui;
    private readonly MoveCoalescer coalescer;
    private readonly ScrollPhaser scroll = new();
    private readonly HashSet<uint> swallowedKeys = new();
    private Thread? thread;
    private IntPtr hwnd, mouseHook, keyboardHook;
    private HookProc? mouseProc, keyboardProc;
    private WndProc? wndProc;

    private volatile bool onTv;
    private Rectangle leftScreen;
    private POINT anchor;
    private double lastAlong = 0.5;
    private int pressure;
    private long atEdgeSince;
    private Rectangle[] screens = [];

    public PointerBridge(ControlChannel channel, SynchronizationContext ui)
    {
        this.channel = channel;
        this.ui = ui;
        coalescer = new MoveCoalescer((dx, dy) => channel.PointerMove(dx, dy));
        channel.PointerLeft += along => TvReleased(along);
    }

    /// <summary>The cursor went onto the TV (true) or came back (false). Raised on the UI thread.</summary>
    public event Action<bool>? OnTvChanged;

    public bool OnTv => onTv;
    public bool Running => thread is not null;

    public void Start()
    {
        if (thread is not null) return;
        var ready = new ManualResetEventSlim();
        thread = new Thread(() => Run(ready)) { IsBackground = true, Name = "OpenTV input hooks", Priority = ThreadPriority.Highest };
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        ready.Wait(2000);
    }

    public void Stop()
    {
        if (thread is null) return;
        PostMessageW(hwnd, WmAppStop, IntPtr.Zero, IntPtr.Zero);
        thread.Join(1000);
        thread = null;
    }

    public void Dispose()
    {
        Stop();
        coalescer.Dispose();
    }

    /// <summary>The TV says the cursor left its screen at <paramref name="along"/> (0..1). Any thread.</summary>
    public void TvReleased(double along)
    {
        if (!onTv || hwnd == IntPtr.Zero) return;
        PostMessageW(hwnd, WmAppReturn, (IntPtr)(int)(Math.Clamp(along, 0, 1) * 65535), IntPtr.Zero);
    }

    /// <summary>Brings the cursor back now (the remote's menu, the app quitting). Any thread.</summary>
    public void ComeBack()
    {
        if (!onTv || hwnd == IntPtr.Zero) return;
        channel.PointerExit();
        PostMessageW(hwnd, WmAppReturn, (IntPtr)(int)(lastAlong * 65535), IntPtr.Zero);
    }

    // ---- the hook thread -------------------------------------------------------------------------

    private void Run(ManualResetEventSlim ready)
    {
        wndProc = WindowProc;
        var hInstance = GetModuleHandleW(null);
        var cls = new WNDCLASSEXW
        {
            cbSize = (uint)Marshal.SizeOf<WNDCLASSEXW>(),
            lpfnWndProc = Marshal.GetFunctionPointerForDelegate(wndProc),
            hInstance = hInstance,
            lpszClassName = "OpenTV.PointerBridge",
        };
        RegisterClassExW(ref cls);
        // A hidden top-level window (not message-only): it receives WM_DISPLAYCHANGE and raw input with INPUTSINK.
        hwnd = CreateWindowExW(WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE, cls.lpszClassName, "OpenTV pointer", WS_POPUP,
            0, 0, 0, 0, IntPtr.Zero, IntPtr.Zero, hInstance, IntPtr.Zero);
        RefreshScreens();
        var rid = new[] { new RAWINPUTDEVICE { usUsagePage = 0x01, usUsage = 0x02, dwFlags = RIDEV_INPUTSINK, hwndTarget = hwnd } };
        if (!RegisterRawInputDevices(rid, 1, (uint)Marshal.SizeOf<RAWINPUTDEVICE>())) Log.Write("raw input registration failed");
        WTSRegisterSessionNotification(hwnd, 0);
        mouseProc = MouseHook;
        keyboardProc = KeyboardHook;
        mouseHook = SetWindowsHookExW(WH_MOUSE_LL, mouseProc, hInstance, 0);
        keyboardHook = SetWindowsHookExW(WH_KEYBOARD_LL, keyboardProc, hInstance, 0);
        if (mouseHook == IntPtr.Zero || keyboardHook == IntPtr.Zero) Log.Write($"hook install failed ({Marshal.GetLastWin32Error()})");
        SetTimer(hwnd, (UIntPtr)1, 20, IntPtr.Zero);
        ready.Set();

        while (GetMessageW(out var msg, IntPtr.Zero, 0, 0) > 0)
        {
            TranslateMessage(ref msg);
            DispatchMessageW(ref msg);
        }

        if (onTv) ReturnToPc(lastAlong);
        KillTimer(hwnd, (UIntPtr)1);
        UnhookWindowsHookEx(mouseHook);
        UnhookWindowsHookEx(keyboardHook);
        WTSUnRegisterSessionNotification(hwnd);
        rid[0].dwFlags = RIDEV_REMOVE;
        rid[0].hwndTarget = IntPtr.Zero;
        RegisterRawInputDevices(rid, 1, (uint)Marshal.SizeOf<RAWINPUTDEVICE>());
        DestroyWindow(hwnd);
        hwnd = IntPtr.Zero;
    }

    private IntPtr WindowProc(IntPtr h, uint msg, IntPtr wParam, IntPtr lParam)
    {
        switch (msg)
        {
            case WM_INPUT:
                RawInput(lParam);
                break;
            case WmAppReturn:
                if (onTv) ReturnToPc(wParam.ToInt32() / 65535.0);
                return IntPtr.Zero;
            case WmAppStop:
                PostQuitMessage(0);
                return IntPtr.Zero;
            case WM_TIMER:
                Tick();
                return IntPtr.Zero;
            case WM_DISPLAYCHANGE:
                RefreshScreens();
                break;
            case WM_WTSSESSION_CHANGE:
                // Locked, switched user, remote session: never leave the cursor hidden on the TV.
                if (onTv) ReturnToPc(lastAlong);
                break;
        }
        return DefWindowProcW(h, msg, wParam, lParam);
    }

    private void RefreshScreens() => screens = Screen.AllScreens.Select(s => s.Bounds).ToArray();

    private void Tick()
    {
        if (!onTv) return;
        if (scroll.Tick(Environment.TickCount64) is { } end) channel.PointerScroll(end.Dx, end.Dy, end.Phase);
        // The TV went away (asleep, Wi-Fi): the cursor comes home.
        if (!channel.IsReady) ReturnToPc(lastAlong);
    }

    // ---- crossing over ---------------------------------------------------------------------------

    /// <summary>Raw mouse movement: pushing up against the top of the topmost screen crosses to the TV.</summary>
    private void RawInput(IntPtr hRawInput)
    {
        if (onTv || !channel.IsReady) return;
        uint size = 0;
        var headerSize = (uint)Marshal.SizeOf<RAWINPUTHEADER>();
        GetRawInputData(hRawInput, RID_INPUT, IntPtr.Zero, ref size, headerSize);
        if (size == 0 || size > 1024) return;
        var buf = Marshal.AllocHGlobal((int)size);
        try
        {
            if (GetRawInputData(hRawInput, RID_INPUT, buf, ref size, headerSize) != size) return;
            var raw = Marshal.PtrToStructure<RAWINPUTMOUSE>(buf);
            if (raw.header.dwType != RIM_TYPEMOUSE || (raw.mouse.usFlags & MOUSE_MOVE_ABSOLUTE) != 0) return;
            Push(raw.mouse.lLastY);
        }
        finally
        {
            Marshal.FreeHGlobal(buf);
        }
    }

    private void Push(int dy)
    {
        if (dy >= 0)
        {
            if (dy > 0) pressure = 0;   // moving away from the edge
            return;
        }
        GetCursorPos(out var p);
        var screen = screens.FirstOrDefault(s => s.Contains(p.X, p.Y));
        if (screen.IsEmpty) screen = screens.FirstOrDefault(s => p.X >= s.Left && p.X < s.Right);
        var atTop = !screen.IsEmpty && p.Y <= screen.Top + 1 && !screens.Any(s => s.Contains(p.X, screen.Top - 2));
        if (!atTop)
        {
            pressure = 0;
            atEdgeSince = 0;
            return;
        }
        var now = Stopwatch.GetTimestamp();
        if (atEdgeSince == 0) atEdgeSince = now;
        pressure += -dy;
        // A deliberate push, not a fling at a browser's tab strip: it has to keep pressing for a moment.
        if (pressure >= PushThreshold && now - atEdgeSince >= DwellTicks)
        {
            pressure = 0;
            atEdgeSince = 0;
            GoToTv(screen, (p.X - screen.Left) / (double)Math.Max(1, screen.Width));
        }
    }

    private void GoToTv(Rectangle screen, double along)
    {
        onTv = true;
        leftScreen = screen;
        lastAlong = along;
        anchor = new POINT(screen.Left + screen.Width / 2, screen.Top + screen.Height / 2);
        coalescer.Reset();
        scroll.Reset();
        swallowedKeys.Clear();
        CursorHider.Hide();
        SetCursorPos(anchor.X, anchor.Y);
        timeBeginPeriod(1);
        channel.PointerEnter(along, "bottom");
        ui.Post(_ => OnTvChanged?.Invoke(true), null);
    }

    private void ReturnToPc(double along)
    {
        if (!onTv) return;
        onTv = false;
        lastAlong = along;
        coalescer.Reset();
        if (scroll.InGesture) channel.PointerScroll(0, 0, ScrollPhase.Ended);
        scroll.Reset();
        var b = leftScreen.IsEmpty ? Screen.PrimaryScreen?.Bounds ?? Rectangle.Empty : leftScreen;
        SetCursorPos(b.Left + (int)(along * b.Width), b.Top + 8);
        CursorHider.Show();
        timeEndPeriod(1);
        pressure = 0;
        atEdgeSince = 0;
        ui.Post(_ => OnTvChanged?.Invoke(false), null);
    }

    // ---- hooks -----------------------------------------------------------------------------------

    private IntPtr MouseHook(int nCode, IntPtr wParam, IntPtr lParam)
    {
        if (nCode < 0 || !onTv) return CallNextHookEx(mouseHook, nCode, wParam, lParam);
        var info = Marshal.PtrToStructure<MSLLHOOKSTRUCT>(lParam);
        if ((info.flags & LLMHF_INJECTED) != 0 && info.dwExtraInfo == InjectedMarker) return CallNextHookEx(mouseHook, nCode, wParam, lParam);
        switch ((int)wParam)
        {
            case WM_MOUSEMOVE:
                // Parking the cursor itself (or no movement at all): let it through.
                if (info.pt.X == anchor.X && info.pt.Y == anchor.Y) return CallNextHookEx(mouseHook, nCode, wParam, lParam);
                // Where the cursor would have gone from the parked spot: the user's own speed and acceleration.
                coalescer.Add(info.pt.X - anchor.X, info.pt.Y - anchor.Y);
                break;
            case WM_LBUTTONDOWN:
                coalescer.Flush();
                channel.PointerButton(0, true);
                break;
            case WM_LBUTTONUP:
                coalescer.Flush();
                channel.PointerButton(0, false);
                break;
            case WM_RBUTTONDOWN:
                channel.PointerButton(1, true);
                break;
            case WM_RBUTTONUP:
                channel.PointerButton(1, false);
                break;
            case WM_XBUTTONDOWN:
                if ((info.mouseData >> 16) == 1) channel.Key(RemoteKey.Back);   // the mouse's Back button
                break;
            case WM_MOUSEWHEEL:
                foreach (var s in scroll.Wheel(0, (short)(info.mouseData >> 16), Environment.TickCount64)) channel.PointerScroll(s.Dx, s.Dy, s.Phase);
                break;
            case WM_MOUSEHWHEEL:
                foreach (var s in scroll.Wheel((short)(info.mouseData >> 16), 0, Environment.TickCount64)) channel.PointerScroll(s.Dx, s.Dy, s.Phase);
                break;
        }
        return (IntPtr)1;   // the TV has it; the Windows cursor stays put
    }

    private static bool Down(int vk) => GetAsyncKeyState(vk) < 0;

    private static bool IsModifier(uint vk) => vk is VK_SHIFT or VK_CONTROL or VK_MENU or VK_LSHIFT or VK_RSHIFT or VK_LCONTROL
        or VK_RCONTROL or VK_LMENU or VK_RMENU or VK_LWIN or VK_RWIN or VK_CAPITAL;

    private IntPtr KeyboardHook(int nCode, IntPtr wParam, IntPtr lParam)
    {
        if (nCode < 0 || !onTv) return CallNextHookEx(keyboardHook, nCode, wParam, lParam);
        var k = Marshal.PtrToStructure<KBDLLHOOKSTRUCT>(lParam);
        if ((k.flags & LLKHF_INJECTED) != 0 && k.dwExtraInfo == InjectedMarker) return CallNextHookEx(keyboardHook, nCode, wParam, lParam);
        var msg = (int)wParam;
        var down = msg is WM_KEYDOWN or WM_SYSKEYDOWN;
        var vk = k.vkCode;
        // Modifiers pass through, so Windows' idea of which are held stays right.
        if (IsModifier(vk)) return CallNextHookEx(keyboardHook, nCode, wParam, lParam);

        var ctrl = Down(VK_CONTROL);
        var altGr = Down(VK_RMENU);
        if (vk == VK_ESCAPE && ctrl && !altGr)
        {
            if (down)
            {
                channel.PointerExit();
                ReturnToPc(lastAlong);
            }
            return (IntPtr)1;   // also keeps Ctrl+Esc from opening Start
        }
        if (!down) return swallowedKeys.Remove(vk) ? (IntPtr)1 : CallNextHookEx(keyboardHook, nCode, wParam, lParam);
        // Windows shortcuts (Alt+Tab, Win+anything, Ctrl+C...) still work on the PC.
        if (!altGr && (ctrl || Down(VK_MENU) || Down(VK_LWIN) || Down(VK_RWIN))) return CallNextHookEx(keyboardHook, nCode, wParam, lParam);

        swallowedKeys.Add(vk);
        switch ((int)vk)
        {
            case VK_UP: channel.Key(RemoteKey.Up); break;
            case VK_DOWN: channel.Key(RemoteKey.Down); break;
            case VK_LEFT: channel.Key(RemoteKey.Left); break;
            case VK_RIGHT: channel.Key(RemoteKey.Right); break;
            case VK_RETURN: channel.Key(RemoteKey.Select); break;
            case VK_ESCAPE: case VK_BROWSER_BACK: channel.Key(RemoteKey.Back); break;
            case VK_BROWSER_HOME: channel.Key(RemoteKey.Home); break;
            case VK_BACK: channel.Type("\b"); break;
            case VK_MEDIA_PLAY_PAUSE: channel.Key(RemoteKey.PlayPause); break;
            case VK_MEDIA_NEXT_TRACK: channel.Key(RemoteKey.Next); break;
            case VK_MEDIA_PREV_TRACK: channel.Key(RemoteKey.Previous); break;
            case VK_VOLUME_UP: channel.Key(RemoteKey.VolUp); break;
            case VK_VOLUME_DOWN: channel.Key(RemoteKey.VolDown); break;
            case VK_VOLUME_MUTE: channel.Key(RemoteKey.Mute); break;
            case VK_TAB: break;
            default:
                var chars = Translate(vk, k.scanCode, altGr);
                if (chars.Length > 0) channel.Type(chars);
                break;
        }
        return (IntPtr)1;
    }

    private readonly byte[] keyState = new byte[256];
    private readonly StringBuilder charBuffer = new(8);

    /// <summary>The characters a key makes in the foreground app's keyboard layout, with Shift, Caps Lock and AltGr.</summary>
    private string Translate(uint vk, uint scan, bool altGr)
    {
        Array.Clear(keyState);
        if (Down(VK_SHIFT)) keyState[VK_SHIFT] = 0x80;
        if ((GetKeyState(VK_CAPITAL) & 1) != 0) keyState[VK_CAPITAL] = 0x01;
        if (altGr)
        {
            keyState[VK_CONTROL] = 0x80;
            keyState[VK_MENU] = 0x80;
            keyState[VK_RMENU] = 0x80;
        }
        var layout = GetKeyboardLayout(GetWindowThreadProcessId(GetForegroundWindow(), out _));
        charBuffer.Clear();
        // Flag 4: don't disturb the keyboard's dead-key state (Windows 10 1607+).
        var n = ToUnicodeEx(vk, scan, keyState, charBuffer, 8, 4, layout);
        if (n <= 0) return "";
        var s = charBuffer.ToString(0, Math.Min(n, charBuffer.Length));
        return new string(s.Where(c => c >= ' ' || c == '\n').ToArray());
    }
}
