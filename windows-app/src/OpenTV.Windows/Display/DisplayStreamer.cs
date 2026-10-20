using System.Diagnostics;
using OpenTV.Core;
using OpenTV.Windows.Native;

namespace OpenTV.Windows.Display;

/// <summary>
/// "TV as Display" (Mirror): shows one of this PC's monitors on the TV with as little delay as the hardware
/// allows. Capture (Desktop Duplication) → scale and convert on the GPU → hardware H.264 in low-latency mode →
/// a Nagle-free TCP socket to the TV's receiver, which decodes in hardware and shows each picture as soon as
/// it's decoded. The PC's sound goes along as plain PCM. The TV's remote drives the PC meanwhile.
///
/// Extending the desktop onto the TV (a separate screen, as the Mac app does) needs an Indirect Display
/// Driver on Windows; OpenTV doesn't install drivers. See the README for that option.
/// </summary>
internal sealed class DisplayStreamer
{
    private readonly RemoteModel model;
    private readonly SynchronizationContext ui;
    private DisplayConnection? connection;
    private Thread? videoThread;
    private LoopbackAudio? audio;
    private volatile bool running;
    private volatile bool forceKeyframe;
    private volatile string? error;

    public DisplayStreamer(RemoteModel model, SynchronizationContext ui)
    {
        this.model = model;
        this.ui = ui;
    }

    public bool Running => running;
    public string Status { get; private set; } = "";
    public bool Sound { get; set; } = true;

    /// <summary>The monitor to show ("\\.\DISPLAY2"); null for the main one.</summary>
    public string? Monitor { get; set; }

    public event Action? Changed;

    private void Report(string status)
    {
        ui.Post(_ =>
        {
            Status = status;
            Changed?.Invoke();
        }, null);
    }

    public void Toggle()
    {
        if (running) Stop();
        else _ = StartAsync();
    }

    public async Task StartAsync()
    {
        if (running) return;
        if (model.Route is not { } route || model.Token is not { } token)
        {
            Status = "Connect to the TV first.";
            Changed?.Invoke();
            return;
        }
        running = true;
        error = null;
        Status = "Connecting…";
        Changed?.Invoke();
        try
        {
            var c = await DisplayConnection.ConnectAsync(route, token);
            connection = c;
            c.Key += InputInjector.RemoteKey;
            c.NeedKeyframe += () => forceKeyframe = true;
            c.Ended += reason => ui.Post(_ => Ended(c, reason), null);
            forceKeyframe = true;
            var info = c.Info;
            var bitrate = route.Usb ? 30_000_000 : 18_000_000;
            videoThread = new Thread(() => VideoLoop(c, info, bitrate, route.Usb))
            {
                IsBackground = true, Name = "OpenTV display", Priority = ThreadPriority.Highest,
            };
            videoThread.Start();
            if (Sound) StartAudio(c);
            Status = $"{info.Height}p{info.Refresh} on {info.Name}" + (route.Usb ? " · USB" : "");
            Changed?.Invoke();
        }
        catch (Exception e)
        {
            running = false;
            Status = e is RemoteException re && re.Kind == RemoteErrorKind.NotPaired ? "The TV refused the stream. Pair this PC again." : e.Message;
            Log.Error("display start", e);
            Changed?.Invoke();
        }
    }

    public void Stop()
    {
        var c = connection;
        if (c is null)
        {
            running = false;
            Changed?.Invoke();
            return;
        }
        c.Stop();   // BYE: the TV goes back to where it was
    }

    private void Ended(DisplayConnection c, string? reason)
    {
        if (connection != c) return;
        connection = null;
        running = false;
        audio?.Dispose();
        audio = null;
        videoThread?.Join(1000);
        videoThread = null;
        Status = reason ?? error ?? "";
        Changed?.Invoke();
    }

    private void StartAudio(DisplayConnection c)
    {
        try
        {
            audio = new LoopbackAudio(pcm => c.SendAudio(pcm, LoopbackAudio.Rate, 2));
            audio.Start();
            Log.Write($"audio: loopback of {audio.DeviceName}");
        }
        catch (Exception e)
        {
            Log.Error("audio capture", e);
            audio?.Dispose();
            audio = null;
        }
    }

    // ---- the video thread ------------------------------------------------------------------------

    private void VideoLoop(DisplayConnection c, TvDisplayInfo info, int bitrate, bool usb)
    {
        uint taskIndex = 0;
        var mmcss = Win32.AvSetMmThreadCharacteristicsW("Capture", ref taskIndex);
        ScreenCapture? capture = null;
        H264Encoder? encoder = null;
        try
        {
            var fps = Math.Clamp(info.Refresh, 24, 60);
            capture = new ScreenCapture(Monitor, info.Width, info.Height, fps);
            encoder = H264Encoder.Create(capture.Device, capture.Width, capture.Height, fps, bitrate);
            Log.Write($"display: {capture.SourceWidth}×{capture.SourceHeight} on {capture.AdapterName} → {capture.Width}×{capture.Height}@{fps} via {encoder.Name}");

            var parameterSets = Array.Empty<byte>();
            long bytes = 0, frames = 0, statsStart = Stopwatch.GetTimestamp();
            var w = capture.Width;
            var h = capture.Height;
            encoder.Output += (data, ptsUs, key) =>
            {
                if (key)
                {
                    var ps = AnnexB.ParameterSets(data);
                    if (ps.Length > 0) parameterSets = ps;
                    if (parameterSets.Length > 0) c.SendVideoConfig(w, h, parameterSets);
                }
                if (parameterSets.Length == 0) return;   // nothing the TV can start decoding from yet
                var frame = AnnexB.ForTv(data, key, parameterSets);
                if (!c.SendVideoFrame(key, ptsUs, [], frame)) return;
                Interlocked.Add(ref bytes, frame.Length);
                Interlocked.Increment(ref frames);
            };

            var clock = Stopwatch.StartNew();
            var interval = Stopwatch.Frequency / fps;
            var nextSlot = Stopwatch.GetTimestamp();
            while (running)
            {
                var now = Stopwatch.GetTimestamp();
                var waitMs = (int)Math.Clamp((nextSlot - now) * 1000 / Stopwatch.Frequency, 0, 100);
                capture.Acquire(capture.Dirty ? waitMs : 100);
                now = Stopwatch.GetTimestamp();
                var wantKey = forceKeyframe;
                // A new picture when its slot comes (at most the TV's refresh rate), or the last one again as an
                // IDR when the TV asks for a keyframe and the screen happens to be still.
                if (capture.HasFrame && ((capture.Dirty && now >= nextSlot) || wantKey))
                {
                    var nv12 = capture.Convert();
                    var ptsUs = clock.ElapsedTicks * 1_000_000 / Stopwatch.Frequency;
                    if (encoder.Encode(nv12, ptsUs, wantKey))
                    {
                        if (wantKey) forceKeyframe = false;
                        nextSlot = Math.Max(nextSlot + interval, now);
                    }
                }
                var elapsed = Stopwatch.GetElapsedTime(statsStart);
                if (elapsed.TotalSeconds >= 2)
                {
                    var mbps = Interlocked.Exchange(ref bytes, 0) * 8 / elapsed.TotalSeconds / 1_000_000;
                    var fpsNow = Interlocked.Exchange(ref frames, 0) / elapsed.TotalSeconds;
                    statsStart = Stopwatch.GetTimestamp();
                    Report($"{info.Height}p · {fpsNow:0} fps · {mbps:0} Mb/s" + (usb ? " · USB" : "") + (encoder.Hardware ? "" : " · software encoder"));
                }
            }
        }
        catch (Exception e)
        {
            Log.Error("display stream", e);
            error = e.Message;
            ui.Post(_ => Stop(), null);
        }
        finally
        {
            encoder?.Dispose();
            capture?.Dispose();
            if (mmcss != IntPtr.Zero) Win32.AvRevertMmThreadCharacteristics(mmcss);
        }
    }
}
