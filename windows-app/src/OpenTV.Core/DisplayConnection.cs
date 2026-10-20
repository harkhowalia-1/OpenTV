using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Net.Sockets;
using System.Text;
using System.Text.Json.Nodes;

namespace OpenTV.Core;

/// <summary>Display stream framing (TCP 8802): <c>u8 type, u32 BE length, payload</c>.</summary>
public static class DisplayProtocol
{
    // PC -> TV
    public const byte Hello = 0x01, VideoConfig = 0x10, VideoFrame = 0x11, AudioConfig = 0x20, AudioPcm = 0x21, Bye = 0x40;

    // TV -> PC
    public const byte Welcome = 0x81, Denied = 0x82, Key = 0x50, NeedKeyframe = 0x51, Stopped = 0x52;

    public const byte CodecH264 = 0;

    public static byte[] Frame(byte type, ReadOnlySpan<byte> payload)
    {
        var f = new byte[5 + payload.Length];
        f[0] = type;
        BinaryPrimitives.WriteUInt32BigEndian(f.AsSpan(1), (uint)payload.Length);
        payload.CopyTo(f.AsSpan(5));
        return f;
    }

    /// <summary>VIDEO_CONFIG: codec, size and the Annex-B parameter sets (SPS, PPS).</summary>
    public static byte[] VideoConfigPayload(int width, int height, ReadOnlySpan<byte> parameterSets, byte codec = CodecH264)
    {
        var p = new byte[5 + parameterSets.Length];
        p[0] = codec;
        BinaryPrimitives.WriteUInt16BigEndian(p.AsSpan(1), (ushort)width);
        BinaryPrimitives.WriteUInt16BigEndian(p.AsSpan(3), (ushort)height);
        parameterSets.CopyTo(p.AsSpan(5));
        return p;
    }

    /// <summary>VIDEO_FRAME: keyframe flag, presentation time in microseconds, then the Annex-B NAL units.</summary>
    public static byte[] VideoFrameFrame(bool keyframe, long ptsUs, ReadOnlySpan<byte> prefix, ReadOnlySpan<byte> nals)
    {
        var len = 9 + prefix.Length + nals.Length;
        var f = new byte[5 + len];
        f[0] = VideoFrame;
        BinaryPrimitives.WriteUInt32BigEndian(f.AsSpan(1), (uint)len);
        f[5] = (byte)(keyframe ? 1 : 0);
        BinaryPrimitives.WriteInt64BigEndian(f.AsSpan(6), ptsUs);
        prefix.CopyTo(f.AsSpan(14));
        nals.CopyTo(f.AsSpan(14 + prefix.Length));
        return f;
    }

    public static byte[] AudioConfigPayload(int rate, int channels)
    {
        var p = new byte[5];
        BinaryPrimitives.WriteUInt32BigEndian(p, (uint)rate);
        p[4] = (byte)channels;
        return p;
    }
}

/// <summary>What the TV said in WELCOME: its real display mode, so the PC streams exactly that.</summary>
public sealed record TvDisplayInfo(string Name, int Width, int Height, int Refresh, IReadOnlyList<string> Codecs);

/// <summary>
/// The network half of "TV as Display": connects to the TV's display receiver, says HELLO with the pairing
/// token, and then carries video and audio. Writes happen on one writer thread; when Wi-Fi backs up, frames
/// that aren't keyframes are dropped (and <see cref="NeedKeyframe"/> asks the encoder for a fresh IDR)
/// instead of queueing pictures that are already late.
/// </summary>
public sealed class DisplayConnection : IDisposable
{
    public const long BacklogLimit = 1_500_000;

    private readonly Socket socket;
    private readonly NetworkStream stream;
    private readonly BlockingCollection<byte[]> queue = new(new ConcurrentQueue<byte[]>());
    private readonly Thread writer;
    private long pending;
    private int ended;
    private volatile bool stopping;
    private byte[] lastConfig = [];

    public TvDisplayInfo Info { get; }

    /// <summary>A remote button pressed on the TV while it shows this PC ("up", "select", "back", ...).</summary>
    public event Action<string>? Key;
    /// <summary>The TV's decoder (re)started, or this side dropped frames: the next frame must be an IDR.</summary>
    public event Action? NeedKeyframe;
    /// <summary>The stream is over: null when this side stopped it, otherwise why.</summary>
    public event Action<string?>? Ended;

    public long PendingBytes => Interlocked.Read(ref pending);
    public long BytesSent { get; private set; }

    private DisplayConnection(Socket socket, NetworkStream stream, TvDisplayInfo info)
    {
        this.socket = socket;
        this.stream = stream;
        Info = info;
        writer = new Thread(WriteLoop) { IsBackground = true, Name = "OpenTV display writer", Priority = ThreadPriority.AboveNormal };
    }

    /// <summary>Connects and waits for WELCOME. Throws <see cref="RemoteException"/> (NotPaired) when refused.</summary>
    public static async Task<DisplayConnection> ConnectAsync(TvDevice tv, string token, CancellationToken ct = default)
    {
        var s = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp)
        {
            NoDelay = true,
            SendBufferSize = 1 << 20,
        };
        try
        {
            using (var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct))
            {
                timeout.CancelAfter(TimeSpan.FromSeconds(5));
                try
                {
                    await s.ConnectAsync(tv.Host, tv.DisplayPort, timeout.Token).ConfigureAwait(false);
                }
                catch (Exception e) when (e is SocketException or OperationCanceledException && !ct.IsCancellationRequested)
                {
                    throw new RemoteException(RemoteErrorKind.Unreachable, e.Message);
                }
            }
            var ns = new NetworkStream(s, ownsSocket: true);
            await ns.WriteAsync(DisplayProtocol.Frame(DisplayProtocol.Hello, Encoding.UTF8.GetBytes(token)), ct).ConfigureAwait(false);
            byte type;
            byte[] payload;
            try
            {
                (type, payload) = await ReadFrameAsync(ns, ct).ConfigureAwait(false);
            }
            catch (IOException e)
            {
                // The TV hangs up right after queueing DENIED, so DENIED itself often never arrives.
                var paired = false;
                try
                {
                    paired = (await new RemoteClient(tv, token).HelloAsync(ct).ConfigureAwait(false)).Paired;
                }
                catch (RemoteException)
                {
                    throw new RemoteException(RemoteErrorKind.Unreachable, e.Message);
                }
                throw new RemoteException(paired ? RemoteErrorKind.Unreachable : RemoteErrorKind.NotPaired, paired ? e.Message : null);
            }
            if (type != DisplayProtocol.Welcome) throw new RemoteException(RemoteErrorKind.NotPaired);
            var info = ParseWelcome(payload, tv.Name);
            var c = new DisplayConnection(s, ns, info);
            c.writer.Start();
            _ = Task.Run(c.ListenAsync);
            return c;
        }
        catch
        {
            s.Dispose();
            throw;
        }
    }

    public static TvDisplayInfo ParseWelcome(byte[] payload, string fallbackName)
    {
        JsonObject? o = null;
        try
        {
            o = JsonNode.Parse(payload) as JsonObject;
        }
        catch (Exception)
        {
        }
        int Int(string k, int d) => o is not null && o[k] is JsonValue v && v.TryGetValue<int>(out var i) ? i : d;
        var codecs = new List<string>();
        if (o?["codecs"] is JsonArray arr)
        {
            foreach (var n in arr)
            {
                if (n is JsonValue v && v.TryGetValue<string>(out var c)) codecs.Add(c);
            }
        }
        if (codecs.Count == 0) codecs.Add("avc");
        var name = o?["name"] is JsonValue nv && nv.TryGetValue<string>(out var nm) ? nm : fallbackName;
        return new TvDisplayInfo(name, Int("width", 1920), Int("height", 1080), Int("refresh", 60), codecs);
    }

    private static async Task<(byte type, byte[] payload)> ReadFrameAsync(Stream s, CancellationToken ct)
    {
        var head = new byte[5];
        await s.ReadExactlyAsync(head, ct).ConfigureAwait(false);
        var len = (int)BinaryPrimitives.ReadUInt32BigEndian(head.AsSpan(1));
        if (len < 0 || len > 16 << 20) throw new IOException("bad frame");
        var payload = new byte[len];
        if (len > 0) await s.ReadExactlyAsync(payload, ct).ConfigureAwait(false);
        return (head[0], payload);
    }

    /// <summary>Messages from the TV while streaming: remote buttons, keyframe requests, "stopped on the TV".</summary>
    private async Task ListenAsync()
    {
        try
        {
            while (true)
            {
                var (type, payload) = await ReadFrameAsync(stream, CancellationToken.None).ConfigureAwait(false);
                switch (type)
                {
                    case DisplayProtocol.Key:
                        Key?.Invoke(Encoding.UTF8.GetString(payload));
                        break;
                    case DisplayProtocol.NeedKeyframe:
                        NeedKeyframe?.Invoke();
                        break;
                    case DisplayProtocol.Stopped:
                        End("Stopped on the TV.");
                        return;
                }
            }
        }
        catch (Exception)
        {
            End("Lost the connection to the TV.");
        }
    }

    // ---- Sending ---------------------------------------------------------------------------------

    private void Enqueue(byte[] frame)
    {
        if (Volatile.Read(ref ended) != 0) return;
        Interlocked.Add(ref pending, frame.Length);
        try
        {
            queue.Add(frame);
        }
        catch (InvalidOperationException)
        {
            // Closed meanwhile.
        }
    }

    /// <summary>Sends VIDEO_CONFIG when the size or parameter sets changed.</summary>
    public void SendVideoConfig(int width, int height, byte[] parameterSets)
    {
        var p = DisplayProtocol.VideoConfigPayload(width, height, parameterSets);
        if (p.AsSpan().SequenceEqual(lastConfig)) return;
        lastConfig = p;
        Enqueue(DisplayProtocol.Frame(DisplayProtocol.VideoConfig, p));
    }

    /// <summary>
    /// Queues one encoded picture. Returns false when it was dropped because the network is behind; the
    /// caller then forces a keyframe (also signalled through <see cref="NeedKeyframe"/>).
    /// </summary>
    public bool SendVideoFrame(bool keyframe, long ptsUs, ReadOnlySpan<byte> prefix, ReadOnlySpan<byte> nals)
    {
        if (!keyframe && PendingBytes > BacklogLimit)
        {
            NeedKeyframe?.Invoke();
            return false;
        }
        var f = DisplayProtocol.VideoFrameFrame(keyframe, ptsUs, prefix, nals);
        Enqueue(f);
        return true;
    }

    private bool audioConfigured;

    /// <summary>16-bit little-endian interleaved PCM; the first call also sends AUDIO_CONFIG.</summary>
    public void SendAudio(ReadOnlySpan<byte> pcm, int rate = 48_000, int channels = 2)
    {
        if (!audioConfigured)
        {
            audioConfigured = true;
            Enqueue(DisplayProtocol.Frame(DisplayProtocol.AudioConfig, DisplayProtocol.AudioConfigPayload(rate, channels)));
        }
        if (PendingBytes > BacklogLimit) return;
        Enqueue(DisplayProtocol.Frame(DisplayProtocol.AudioPcm, pcm));
    }

    private void WriteLoop()
    {
        try
        {
            foreach (var f in queue.GetConsumingEnumerable())
            {
                stream.Write(f);
                BytesSent += f.Length;
                Interlocked.Add(ref pending, -f.Length);
            }
        }
        catch (Exception)
        {
            End("Lost the connection to the TV.");
        }
    }

    /// <summary>Says BYE (the TV goes back to where it was) and closes.</summary>
    public void Stop()
    {
        if (Volatile.Read(ref ended) != 0) return;
        stopping = true;   // the TV closing its end after BYE is not an error
        Enqueue(DisplayProtocol.Frame(DisplayProtocol.Bye, []));
        queue.CompleteAdding();
        writer.Join(TimeSpan.FromSeconds(1));
        End(null);
    }

    private void End(string? reason)
    {
        if (Interlocked.Exchange(ref ended, 1) != 0) return;
        if (stopping) reason = null;
        queue.CompleteAdding();
        try
        {
            socket.Shutdown(SocketShutdown.Both);
        }
        catch (Exception)
        {
        }
        stream.Dispose();
        Ended?.Invoke(reason);
    }

    public void Dispose() => Stop();
}
