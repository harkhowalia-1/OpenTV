using System.Diagnostics;

namespace OpenTV.Core;

/// <summary>
/// Gathers mouse movement and sends it to the TV at most <c>hz</c> times a second (120 by default): a
/// 1000 Hz gaming mouse would otherwise send a thousand tiny frames a second over Wi-Fi. Nothing is lost:
/// what arrives between two sends is added up. Sending happens on this object's own thread, so the caller
/// (a low-level mouse hook, which must return quickly) only adds numbers.
/// </summary>
public sealed class MoveCoalescer : IDisposable
{
    private readonly Action<int, int> send;
    private readonly long intervalTicks;
    private readonly AutoResetEvent wake = new(false);
    private readonly Thread thread;
    private readonly object gate = new();
    private int dx, dy;
    private long lastSend;
    private volatile bool running = true;

    public int Sent { get; private set; }

    public MoveCoalescer(Action<int, int> send, double hz = 120)
    {
        this.send = send;
        intervalTicks = (long)(Stopwatch.Frequency / hz);
        thread = new Thread(Loop) { IsBackground = true, Name = "OpenTV pointer", Priority = ThreadPriority.AboveNormal };
        thread.Start();
    }

    public void Add(int x, int y)
    {
        if (x == 0 && y == 0) return;
        lock (gate)
        {
            dx += x;
            dy += y;
        }
        wake.Set();
    }

    /// <summary>Forgets movement that hasn't gone out yet (the cursor just came back to the PC).</summary>
    public void Reset()
    {
        lock (gate) dx = dy = 0;
    }

    /// <summary>Sends whatever is pending now (before a click, so it lands where the cursor is).</summary>
    public void Flush()
    {
        int x, y;
        lock (gate)
        {
            (x, y) = (dx, dy);
            dx = dy = 0;
        }
        if (x != 0 || y != 0) Emit(x, y);
    }

    private void Emit(int x, int y)
    {
        // One frame carries at most ±32767 each way.
        while (x != 0 || y != 0)
        {
            var sx = Math.Clamp(x, short.MinValue, short.MaxValue);
            var sy = Math.Clamp(y, short.MinValue, short.MaxValue);
            send(sx, sy);
            Sent++;
            x -= sx;
            y -= sy;
        }
        Interlocked.Exchange(ref lastSend, Stopwatch.GetTimestamp());
    }

    private void Loop()
    {
        while (running)
        {
            wake.WaitOne();
            if (!running) return;
            var wait = intervalTicks - (Stopwatch.GetTimestamp() - Interlocked.Read(ref lastSend));
            if (wait > 0)
            {
                var ms = (int)(wait * 1000 / Stopwatch.Frequency);
                if (ms > 0) Thread.Sleep(ms);
                while (Stopwatch.GetTimestamp() - Interlocked.Read(ref lastSend) < intervalTicks) Thread.SpinWait(50);
            }
            Flush();
        }
    }

    public void Dispose()
    {
        running = false;
        wake.Set();
        thread.Join(200);
        wake.Dispose();
    }
}

/// <summary>A scroll to send: pixel-ish deltas (positive y = content moves down) and its phase.</summary>
public readonly record struct ScrollStep(int Dx, int Dy, ScrollPhase Phase);

/// <summary>
/// Turns Windows wheel messages into the TV's scroll gestures. A mouse wheel notch (a multiple of 120) is a
/// discrete step (phase 0). A precision touchpad's two-finger scroll arrives as a stream of small deltas; that
/// becomes one gesture: Began, Changed..., then Ended once the fingers have been still for
/// <see cref="GestureGapMs"/>. Windows has no momentum phase to forward, which suits the TV (it ignores it).
/// </summary>
public sealed class ScrollPhaser
{
    /// <summary>One wheel notch (WHEEL_DELTA) becomes this many TV points, like a Mac's line scroll.</summary>
    public const double PointsPerNotch = 40;
    public const int GestureGapMs = 120;

    private bool inGesture;
    private long lastMs;
    private double remX, remY;

    public bool InGesture => inGesture;

    /// <summary>
    /// One WM_MOUSEWHEEL / WM_MOUSEHWHEEL. <paramref name="wheelY"/> is positive when the wheel turns away from
    /// the user (scroll up, content moves down); <paramref name="wheelX"/> is positive for scrolling right.
    /// </summary>
    public IReadOnlyList<ScrollStep> Wheel(int wheelX, int wheelY, long nowMs)
    {
        var steps = new List<ScrollStep>(2);
        // A gesture that went quiet without Tick ending it: end it before starting the next one.
        if (inGesture && nowMs - lastMs > GestureGapMs)
        {
            inGesture = false;
            remX = remY = 0;
            steps.Add(new ScrollStep(0, 0, ScrollPhase.Ended));
        }
        // Content-direction deltas for the TV: scrolling right moves content left.
        var px = -wheelX * PointsPerNotch / 120.0 + remX;
        var py = wheelY * PointsPerNotch / 120.0 + remY;
        var notch = wheelX % 120 == 0 && wheelY % 120 == 0;
        lastMs = nowMs;
        int ix = (int)Math.Truncate(px), iy = (int)Math.Truncate(py);
        remX = px - ix;
        remY = py - iy;
        if (!inGesture && notch)
        {
            remX = remY = 0;
            steps.Add(new ScrollStep(ix, iy, ScrollPhase.Wheel));
            return steps;
        }
        steps.Add(new ScrollStep(ix, iy, inGesture ? ScrollPhase.Changed : ScrollPhase.Began));
        inGesture = true;
        return steps;
    }

    /// <summary>Call regularly (every ~20 ms while on the TV): ends a touchpad gesture once it goes quiet.</summary>
    public ScrollStep? Tick(long nowMs)
    {
        if (!inGesture || nowMs - lastMs < GestureGapMs) return null;
        inGesture = false;
        remX = remY = 0;
        return new ScrollStep(0, 0, ScrollPhase.Ended);
    }

    public void Reset()
    {
        inGesture = false;
        remX = remY = 0;
    }
}
