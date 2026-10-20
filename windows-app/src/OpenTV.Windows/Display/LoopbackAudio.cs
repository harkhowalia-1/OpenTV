using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace OpenTV.Windows.Display;

/// <summary>
/// The PC's sound for the TV: WASAPI loopback of the default output device, turned into what the TV's
/// receiver plays, 48 kHz 16-bit stereo PCM (no codec, so no codec delay). Read every ~10 ms.
/// </summary>
internal sealed class LoopbackAudio : IDisposable
{
    public const int Rate = 48_000;

    private static readonly Guid IeeeFloat = new("00000003-0000-0010-8000-00aa00389b71");   // KSDATAFORMAT_SUBTYPE_IEEE_FLOAT

    private readonly Action<byte[]> sink;
    private readonly MMDeviceEnumerator enumerator = new();
    private LowLatencyLoopback? capture;
    private double position;          // resampler: fractional read position into the source
    private float lastL, lastR;

    public LoopbackAudio(Action<byte[]> sink) => this.sink = sink;

    public string? DeviceName { get; private set; }

    public void Start()
    {
        var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        DeviceName = device.FriendlyName;
        capture = new LowLatencyLoopback(device);
        capture.DataAvailable += (_, e) =>
        {
            if (e.BytesRecorded > 0) sink(Convert(e.Buffer, e.BytesRecorded, capture.WaveFormat));
        };
        capture.StartRecording();
    }

    public void Dispose()
    {
        try
        {
            capture?.StopRecording();
        }
        catch (Exception)
        {
        }
        capture?.Dispose();
        enumerator.Dispose();
    }

    /// <summary>Any shared-mode mix format (float or PCM, any rate, any channel count) to 48 kHz s16le stereo.</summary>
    private byte[] Convert(byte[] buffer, int bytes, WaveFormat f)
    {
        var channels = Math.Max(1, f.Channels);
        var bytesPerSample = f.BitsPerSample / 8;
        var frames = bytes / (bytesPerSample * channels);
        var isFloat = f.Encoding == WaveFormatEncoding.IeeeFloat ||
                      (f is WaveFormatExtensible x && x.SubFormat == IeeeFloat);

        float Sample(int frame, int ch)
        {
            var i = (frame * channels + Math.Min(ch, channels - 1)) * bytesPerSample;
            if (isFloat && bytesPerSample == 4) return BitConverter.ToSingle(buffer, i);
            return bytesPerSample switch
            {
                2 => BitConverter.ToInt16(buffer, i) / 32768f,
                3 => ((buffer[i] << 8 | buffer[i + 1] << 16 | buffer[i + 2] << 24) >> 8) / 8388608f,
                4 => BitConverter.ToInt32(buffer, i) / 2147483648f,
                _ => 0,
            };
        }

        var step = f.SampleRate / (double)Rate;
        var outFrames = (int)Math.Max(0, Math.Floor((frames - position) / step));
        var pcm = new byte[outFrames * 4];
        var o = 0;
        for (var n = 0; n < outFrames; n++)
        {
            var p = position + n * step;
            var i0 = (int)Math.Floor(p);
            var t = (float)(p - i0);
            // Linear interpolation; the frame before this buffer's first one is remembered from the last call.
            float l0 = i0 >= 0 ? Sample(i0, 0) : lastL, r0 = i0 >= 0 ? Sample(i0, 1) : lastR;
            float l1 = i0 + 1 < frames ? Sample(i0 + 1, 0) : l0, r1 = i0 + 1 < frames ? Sample(i0 + 1, 1) : r0;
            Write(pcm, ref o, l0 + (l1 - l0) * t);
            Write(pcm, ref o, r0 + (r1 - r0) * t);
        }
        position = position + outFrames * step - frames;
        if (frames > 0)
        {
            lastL = Sample(frames - 1, 0);
            lastR = Sample(frames - 1, 1);
        }
        return pcm;
    }

    private static void Write(byte[] b, ref int o, float v)
    {
        var s = (short)Math.Clamp((int)(v * 32767f), short.MinValue, short.MaxValue);
        b[o++] = (byte)s;
        b[o++] = (byte)(s >> 8);
    }

    /// <summary>Loopback capture with a 20 ms buffer (NAudio's default is 100 ms), polled every 10 ms.</summary>
    private sealed class LowLatencyLoopback(MMDevice device) : WasapiCapture(device, false, 20)
    {
        protected override AudioClientStreamFlags GetAudioClientStreamFlags() => AudioClientStreamFlags.Loopback;
    }
}
