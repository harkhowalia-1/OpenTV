using System.Diagnostics;
using System.Net.Sockets;
using System.Text.Json.Nodes;
using System.Threading.Channels;

namespace OpenTV.Core;

public enum ChannelState { Idle, Connecting, Ready, Denied }

/// <summary>
/// The live connection to the TV (port 8801): one open TCP socket with Nagle off, so a button press or a mouse
/// movement is a few bytes with no handshake. It reconnects on its own (TV asleep, restarted, moved to a new
/// address, app relaunched) and the TV pushes what's playing and when the cursor leaves its screen.
///
/// Events are raised on <see cref="Context"/> when one is set (the UI thread), otherwise on a pool thread.
/// Sending never blocks: frames go into a queue that a writer task drains, and are dropped while not ready.
/// </summary>
public sealed class ControlChannel : IDisposable
{
    private readonly object gate = new();
    private CancellationTokenSource? run;
    private Channel<byte[]>? outbox;
    private TvDevice? tv;
    private string? token;
    private volatile ChannelState state = ChannelState.Idle;
    private long lastPongTicks;

    public SynchronizationContext? Context { get; set; }
    public TimeSpan PingInterval { get; set; } = TimeSpan.FromSeconds(10);
    public TimeSpan ConnectTimeout { get; set; } = TimeSpan.FromSeconds(4);

    public event Action<ChannelState>? StateChanged;
    public event Action<NowPlaying?>? NowPlayingChanged;
    /// <summary>The TV says the cursor left its screen, at this point (0..1) along the edge it came in by.</summary>
    public event Action<double>? PointerLeft;
    public event Action<string, string?>? Welcomed;
    /// <summary>A PONG arrived; the argument is the round trip of the PING it answers.</summary>
    public event Action<TimeSpan>? Pong;

    public ChannelState State => state;
    public bool IsReady => state == ChannelState.Ready;
    public TvDevice? Tv => tv;

    public void Connect(TvDevice to, string withToken)
    {
        lock (gate)
        {
            if (run is not null && tv == to && token == withToken) return;
            StopLocked();
            tv = to;
            token = withToken;
            run = new CancellationTokenSource();
            var ct = run.Token;
            _ = Task.Run(() => LoopAsync(to, withToken, ct));
        }
    }

    public void Disconnect()
    {
        lock (gate) StopLocked();
        SetState(ChannelState.Idle);
    }

    private void StopLocked()
    {
        run?.Cancel();
        run?.Dispose();
        run = null;
        outbox?.Writer.TryComplete();
        outbox = null;
    }

    public void Dispose() => Disconnect();

    // ---- Sending ---------------------------------------------------------------------------------

    public void Key(RemoteKey key) => Send(ControlProtocol.KeyFrame(key));
    /// <summary>Replaces the text of the TV's focused field.</summary>
    public void SetText(string text) => Send(ControlProtocol.TextFrame(text));
    /// <summary>Typing: characters are added to the focused field; "\b" deletes, "\n" submits.</summary>
    public void Type(string chars) => Send(ControlProtocol.TypeFrame(chars));
    public void PointerEnter(double along, string edge = "bottom") => Send(ControlProtocol.PointerEnterFrame(along, edge));
    public void PointerMove(int dx, int dy) => Send(ControlProtocol.PointerMoveFrame(dx, dy));
    public void PointerButton(int button, bool down) => Send(ControlProtocol.PointerButtonFrame(button, down));
    public void PointerScroll(int dx, int dy, ScrollPhase phase) => Send(ControlProtocol.PointerScrollFrame(dx, dy, phase));
    public void PointerExit() => Send(ControlProtocol.PointerExitFrame());

    private long pingSentTicks;

    public void Ping()
    {
        Interlocked.Exchange(ref pingSentTicks, Stopwatch.GetTimestamp());
        Send(ControlProtocol.PingFrame());
    }

    public void Send(byte[] frame)
    {
        if (state != ChannelState.Ready) return;
        outbox?.Writer.TryWrite(frame);
    }

    // ---- Connection ------------------------------------------------------------------------------

    private async Task LoopAsync(TvDevice to, string withToken, CancellationToken ct)
    {
        var retry = TimeSpan.FromSeconds(0.5);
        while (!ct.IsCancellationRequested)
        {
            SetState(ChannelState.Connecting, ct);
            var denied = false;
            try
            {
                var end = await SessionAsync(to, withToken, () => retry = TimeSpan.FromSeconds(0.5), ct).ConfigureAwait(false);
                denied = end == SessionEnd.Denied ||
                         (end == SessionEnd.ClosedBeforeWelcome && await TokenRefusedAsync(to, withToken, ct).ConfigureAwait(false));
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                return;
            }
            catch (Exception)
            {
                // Dropped or unreachable: try again shortly.
            }
            if (denied)
            {
                SetState(ChannelState.Denied, ct);
                lock (gate)
                {
                    if (run?.Token == ct) StopLocked();
                }
                return;
            }
            SetState(ChannelState.Connecting, ct);
            try
            {
                await Task.Delay(retry, ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            retry = TimeSpan.FromSeconds(Math.Min(retry.TotalSeconds * 2, 5));
        }
    }

    private enum SessionEnd { Dropped, Denied, ClosedBeforeWelcome }

    /// <summary>
    /// The TV closes the socket right after queueing DENIED, so DENIED often never arrives: a hang-up before
    /// WELCOME is checked against /api/hello, which says whether the TV still knows this token.
    /// </summary>
    private static async Task<bool> TokenRefusedAsync(TvDevice to, string withToken, CancellationToken ct)
    {
        try
        {
            return !(await new RemoteClient(to, withToken).HelloAsync(ct).ConfigureAwait(false)).Paired;
        }
        catch (RemoteException)
        {
            return false;   // not answering: a drop, not a refusal
        }
    }

    /// <summary>One connection, from connect to drop.</summary>
    private async Task<SessionEnd> SessionAsync(TvDevice to, string withToken, Action welcomed, CancellationToken ct)
    {
        using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
        socket.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.KeepAlive, true);
        TrySetKeepAlive(socket);
        using (var connectTimeout = CancellationTokenSource.CreateLinkedTokenSource(ct))
        {
            connectTimeout.CancelAfter(ConnectTimeout);
            await socket.ConnectAsync(to.Host, to.ControlPort, connectTimeout.Token).ConfigureAwait(false);
        }
        using var stream = new NetworkStream(socket, ownsSocket: false);
        await stream.WriteAsync(ControlProtocol.HelloFrame(withToken), ct).ConfigureAwait(false);

        var box = Channel.CreateUnbounded<byte[]>(new UnboundedChannelOptions { SingleReader = true });
        using var session = CancellationTokenSource.CreateLinkedTokenSource(ct);
        Task? writer = null, pinger = null;
        var header = new byte[3];
        var gotWelcome = false;
        try
        {
            while (true)
            {
                await stream.ReadExactlyAsync(header, session.Token).ConfigureAwait(false);
                int len = header[0] << 8 | header[1];
                var type = header[2];
                var payload = new byte[len];
                if (len > 0) await stream.ReadExactlyAsync(payload, session.Token).ConfigureAwait(false);
                switch (type)
                {
                    case ControlProtocol.Welcome:
                        gotWelcome = true;
                        welcomed();
                        lock (gate)
                        {
                            if (ct.IsCancellationRequested) return SessionEnd.Dropped;
                            outbox = box;
                        }
                        Interlocked.Exchange(ref lastPongTicks, Stopwatch.GetTimestamp());
                        writer = WriteLoopAsync(stream, box.Reader, session);
                        pinger = PingLoopAsync(session);
                        SetState(ChannelState.Ready, ct);
                        var w = Parse(payload);
                        Raise(() => Welcomed?.Invoke(Str(w, "name") ?? "", Str(w, "id")));
                        break;
                    case ControlProtocol.Denied:
                        return SessionEnd.Denied;
                    case ControlProtocol.State:
                        var np = RemoteClient.ParseNowPlaying(Parse(payload));
                        Raise(() => NowPlayingChanged?.Invoke(np));
                        break;
                    case ControlProtocol.PointerLeft:
                        var along = ControlProtocol.ParseAlong(payload);
                        // Straight away, not through the UI thread: the cursor is waiting.
                        PointerLeft?.Invoke(along);
                        break;
                    case ControlProtocol.Pong:
                        var now = Stopwatch.GetTimestamp();
                        Interlocked.Exchange(ref lastPongTicks, now);
                        var rtt = Stopwatch.GetElapsedTime(Interlocked.Read(ref pingSentTicks), now);
                        Raise(() => Pong?.Invoke(rtt));
                        break;
                }
            }
        }
        catch (Exception e) when (!gotWelcome && e is EndOfStreamException or IOException && !ct.IsCancellationRequested)
        {
            return SessionEnd.ClosedBeforeWelcome;
        }
        finally
        {
            lock (gate)
            {
                if (outbox == box) outbox = null;
            }
            box.Writer.TryComplete();
            session.Cancel();
            try
            {
                socket.Shutdown(SocketShutdown.Both);
            }
            catch (SocketException)
            {
            }
            catch (ObjectDisposedException)
            {
            }
            if (writer is not null) await Task.WhenAny(writer, Task.Delay(500)).ConfigureAwait(false);
            if (pinger is not null) await Task.WhenAny(pinger, Task.Delay(100)).ConfigureAwait(false);
        }
    }

    private static async Task WriteLoopAsync(NetworkStream stream, ChannelReader<byte[]> reader, CancellationTokenSource session)
    {
        try
        {
            while (await reader.WaitToReadAsync(session.Token).ConfigureAwait(false))
            {
                while (reader.TryRead(out var frame))
                {
                    await stream.WriteAsync(frame, session.Token).ConfigureAwait(false);
                }
            }
        }
        catch (Exception)
        {
            session.Cancel();   // a failed write ends the session; the loop reconnects
        }
    }

    private async Task PingLoopAsync(CancellationTokenSource session)
    {
        try
        {
            while (!session.IsCancellationRequested)
            {
                await Task.Delay(PingInterval, session.Token).ConfigureAwait(false);
                // No answer to the last pings: the TV has gone (asleep, unplugged); start over.
                if (Stopwatch.GetElapsedTime(Interlocked.Read(ref lastPongTicks)) > PingInterval * 2.5)
                {
                    session.Cancel();
                    return;
                }
                Ping();
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    private static void TrySetKeepAlive(Socket s)
    {
        try
        {
            s.SetSocketOption(SocketOptionLevel.Tcp, SocketOptionName.TcpKeepAliveTime, 5);
            s.SetSocketOption(SocketOptionLevel.Tcp, SocketOptionName.TcpKeepAliveInterval, 2);
        }
        catch (Exception)
        {
            // Not supported on every OS version; the pings cover it.
        }
    }

    private void SetState(ChannelState s, CancellationToken ct = default)
    {
        // A connection that has been replaced or stopped no longer speaks for the channel.
        if (ct.IsCancellationRequested || state == s) return;
        state = s;
        Raise(() => StateChanged?.Invoke(s));
    }

    private void Raise(Action a)
    {
        var ctx = Context;
        if (ctx is null) a();
        else ctx.Post(_ => a(), null);
    }

    private static JsonObject? Parse(byte[] payload)
    {
        try
        {
            return payload.Length == 0 ? null : JsonNode.Parse(payload) as JsonObject;
        }
        catch (Exception)
        {
            return null;
        }
    }

    private static string? Str(JsonObject? o, string key) =>
        o is not null && o.TryGetPropertyValue(key, out var v) && v is JsonValue jv && jv.TryGetValue<string>(out var s) ? s : null;
}
