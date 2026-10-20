using System.Runtime.InteropServices;
using OpenTV.Core;
using SharpGen.Runtime;
using Vortice.Direct3D11;
using Vortice.MediaFoundation;

namespace OpenTV.Windows.Display;

/// <summary>
/// H.264 with Media Foundation, tuned for latency rather than size: the GPU's hardware encoder (Intel Quick
/// Sync, NVIDIA NVENC, AMD VCN) fed straight from the capture texture, in low-latency mode
/// (CODECAPI_AVLowLatencyMode), no B-frames, constant bit rate, an IDR whenever the TV asks. Hardware encoders
/// are asynchronous MFTs driven by their events; Microsoft's software encoder is the fallback (synchronous,
/// fed from system memory) for PCs without one.
/// </summary>
internal sealed class H264Encoder : IDisposable
{
    private const int MF_E_TRANSFORM_NEED_MORE_INPUT = unchecked((int)0xC00D6D72);
    private const int MF_E_TRANSFORM_STREAM_CHANGE = unchecked((int)0xC00D6D61);
    private const int MF_E_NOTACCEPTING = unchecked((int)0xC00D36B5);
    private const uint H264ProfileHigh = 100, H264ProfileMain = 77, H264ProfileBase = 66;

    private static int started;

    private readonly IMFActivate activate;
    private readonly IMFTransform mft;
    private readonly ICodecAPI? codec;
    private readonly IMFDXGIDeviceManager? manager;
    private readonly IMFMediaEventGenerator? events;
    private readonly Thread? eventThread;
    private readonly ID3D11Device device;
    private readonly bool isAsync, d3d;
    private readonly int width, height, fps;
    private OutputStreamInfo outputInfo;
    private int needInput;
    private volatile bool disposed;
    private ID3D11Texture2D? staging;

    public string Name { get; }
    public bool Hardware { get; }

    /// <summary>One encoded picture: Annex-B bytes, presentation time in µs, keyframe or not.</summary>
    public event Action<byte[], long, bool>? Output;

    public static H264Encoder Create(ID3D11Device device, int width, int height, int fps, int bitrate)
    {
        if (Interlocked.Exchange(ref started, 1) == 0) MediaFactory.MFStartup(false).CheckError();
        var input = new RegisterTypeInfo { GuidMajorType = MediaTypeGuids.Video, GuidSubtype = VideoFormatGuids.NV12 };
        var output = new RegisterTypeInfo { GuidMajorType = MediaTypeGuids.Video, GuidSubtype = VideoFormatGuids.H264 };
        foreach (var hardware in new[] { true, false })
        {
            var flags = (hardware ? EnumFlag.EnumFlagHardware : EnumFlag.EnumFlagSyncmft) | EnumFlag.EnumFlagSortandfilter;
            using var found = MediaFactory.MFTEnumEx(TransformCategoryGuids.VideoEncoder, (uint)flags, input, output);
            foreach (var act in found)
            {
                var name = "H.264 encoder";
                try
                {
                    name = act.GetString(TransformAttributeKeys.MftFriendlyNameAttribute);
                }
                catch (SharpGenException)
                {
                }
                // The list releases its entries when disposed: keep a reference of our own to the one used.
                Marshal.AddRef(act.NativePointer);
                var mine = new IMFActivate(act.NativePointer);
                try
                {
                    var e = new H264Encoder(mine, name, hardware, device, width, height, fps, bitrate);
                    Log.Write($"encoder: {name} ({(hardware ? "hardware" : "software")}{(e.isAsync ? ", async" : "")}{(e.d3d ? ", GPU input" : "")})");
                    return e;
                }
                catch (Exception ex)
                {
                    // An encoder on another GPU refuses this device; try the next one.
                    Log.Error($"encoder {name} unusable", ex);
                    try
                    {
                        mine.ShutdownObject();
                    }
                    catch (Exception)
                    {
                    }
                    mine.Dispose();
                }
            }
        }
        throw new InvalidOperationException("This PC has no H.264 video encoder.");
    }

    private H264Encoder(IMFActivate activate, string name, bool hardware, ID3D11Device device, int width, int height, int fps, int bitrate)
    {
        this.activate = activate;
        this.device = device;
        this.width = width;
        this.height = height;
        this.fps = fps;
        Name = name;
        Hardware = hardware;
        mft = activate.ActivateObject<IMFTransform>();
        try
        {
            using (var attrs = mft.Attributes)
            {
                if (hardware && attrs.GetUInt32(TransformAttributeKeys.TransformAsync, out var a).Success && a != 0)
                {
                    attrs.Set(TransformAttributeKeys.TransformAsyncUnlock, 1u).CheckError();
                    isAsync = true;
                }
                attrs.Set(SinkWriterAttributeKeys.LowLatency, 1u);   // MF_LOW_LATENCY
            }
            if (hardware)
            {
                manager = MediaFactory.MFCreateDXGIDeviceManager();
                manager.ResetDevice(device).CheckError();
                mft.ProcessMessage(TMessageType.MessageSetD3DManager, (UIntPtr)(ulong)manager.NativePointer);
                d3d = true;
            }

            codec = Marshal.GetObjectForIUnknown(mft.NativePointer) as ICodecAPI;
            CodecApi.Set(codec, CodecApi.AVLowLatencyMode, true);
            CodecApi.Set(codec, CodecApi.AVEncCommonRateControlMode, CodecApi.RateControlCbr);
            CodecApi.Set(codec, CodecApi.AVEncCommonMeanBitRate, (uint)bitrate);
            CodecApi.Set(codec, CodecApi.AVEncMPVDefaultBPictureCount, 0u);
            CodecApi.Set(codec, CodecApi.AVEncMPVGOPSize, (uint)(fps * 10));
            CodecApi.Set(codec, CodecApi.AVEncCommonQualityVsSpeed, 20u);

            SetOutputType(bitrate);
            SetInputType();

            mft.ProcessMessage(TMessageType.MessageCommandFlush, UIntPtr.Zero);
            mft.ProcessMessage(TMessageType.MessageNotifyBeginStreaming, UIntPtr.Zero);
            mft.ProcessMessage(TMessageType.MessageNotifyStartOfStream, UIntPtr.Zero);
            outputInfo = mft.GetOutputStreamInfo(0);

            if (isAsync)
            {
                events = mft.QueryInterface<IMFMediaEventGenerator>();
                eventThread = new Thread(EventLoop) { IsBackground = true, Name = "OpenTV encoder events", Priority = ThreadPriority.AboveNormal };
                eventThread.Start();
            }
        }
        catch
        {
            if (codec is not null) Marshal.ReleaseComObject(codec);
            manager?.Dispose();
            mft.Dispose();
            throw;
        }
    }

    private static ulong Pack(int hi, int lo) => ((ulong)(uint)hi << 32) | (uint)lo;

    private void SetOutputType(int bitrate)
    {
        Exception? last = null;
        foreach (var profile in new[] { H264ProfileHigh, H264ProfileMain, H264ProfileBase })
        {
            using var t = MediaFactory.MFCreateMediaType();
            t.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Video);
            t.Set(MediaTypeAttributeKeys.Subtype, VideoFormatGuids.H264);
            t.Set(MediaTypeAttributeKeys.AvgBitrate, (uint)bitrate);
            t.Set(MediaTypeAttributeKeys.FrameSize, Pack(width, height));
            t.Set(MediaTypeAttributeKeys.FrameRate, Pack(fps, 1));
            t.Set(MediaTypeAttributeKeys.PixelAspectRatio, Pack(1, 1));
            t.Set(MediaTypeAttributeKeys.InterlaceMode, 2u);           // progressive
            t.Set(MediaTypeAttributeKeys.Mpeg2Profile, profile);
            t.Set(MediaTypeAttributeKeys.YuvMatrix, 1u);               // BT.709
            t.Set(MediaTypeAttributeKeys.VideoNominalRange, 2u);       // 16-235
            try
            {
                mft.SetOutputType(0, t, 0);
                return;
            }
            catch (SharpGenException e)
            {
                last = e;
            }
        }
        throw last ?? new InvalidOperationException("output type");
    }

    private void SetInputType()
    {
        IMFMediaType? chosen = null;
        for (var i = 0; chosen is null; i++)
        {
            IMFMediaType t;
            try
            {
                t = mft.GetInputAvailableType(0, i);
            }
            catch (SharpGenException)
            {
                break;   // MF_E_NO_MORE_TYPES
            }
            if (t.GetGUID(MediaTypeAttributeKeys.Subtype) == VideoFormatGuids.NV12) chosen = t;
            else t.Dispose();
        }
        if (chosen is null)
        {
            chosen = MediaFactory.MFCreateMediaType();
            chosen.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Video);
            chosen.Set(MediaTypeAttributeKeys.Subtype, VideoFormatGuids.NV12);
        }
        using (chosen)
        {
            chosen.Set(MediaTypeAttributeKeys.FrameSize, Pack(width, height));
            chosen.Set(MediaTypeAttributeKeys.FrameRate, Pack(fps, 1));
            chosen.Set(MediaTypeAttributeKeys.PixelAspectRatio, Pack(1, 1));
            chosen.Set(MediaTypeAttributeKeys.InterlaceMode, 2u);
            mft.SetInputType(0, chosen, 0);
        }
    }

    // ---- encoding --------------------------------------------------------------------------------

    /// <summary>
    /// Hands one NV12 picture to the encoder. Returns false when the encoder is still busy with the previous
    /// one: the picture is skipped (latency first) and the caller offers the next.
    /// </summary>
    public bool Encode(ID3D11Texture2D nv12, long ptsUs, bool keyframe)
    {
        if (disposed) return false;
        if (isAsync && Volatile.Read(ref needInput) <= 0) return false;
        using var sample = MediaFactory.MFCreateSample();
        var buffer = d3d ? GpuBuffer(nv12) : MemoryBuffer(nv12);
        using (buffer) sample.AddBuffer(buffer);
        sample.SampleTime = ptsUs * 10;
        sample.SampleDuration = 10_000_000 / fps;
        if (keyframe) CodecApi.Set(codec, CodecApi.AVEncVideoForceKeyFrame, 1u);
        try
        {
            mft.ProcessInput(0, sample, 0);
        }
        catch (SharpGenException e) when (e.ResultCode.Code == MF_E_NOTACCEPTING)
        {
            if (!isAsync) Drain();
            return false;
        }
        if (isAsync) Interlocked.Decrement(ref needInput);
        else Drain();
        return true;
    }

    private static IMFMediaBuffer GpuBuffer(ID3D11Texture2D nv12)
    {
        var buffer = MediaFactory.MFCreateDXGISurfaceBuffer(typeof(ID3D11Texture2D).GUID, nv12, 0, false);
        using var b2 = buffer.QueryInterface<IMF2DBuffer>();
        buffer.CurrentLength = b2.ContiguousLength;
        return buffer;
    }

    /// <summary>The software encoder reads system memory: copy the NV12 picture down from the GPU.</summary>
    private IMFMediaBuffer MemoryBuffer(ID3D11Texture2D nv12)
    {
        if (staging is null)
        {
            var d = nv12.Description;
            d.Usage = ResourceUsage.Staging;
            d.BindFlags = BindFlags.None;
            d.CPUAccessFlags = CpuAccessFlags.Read;
            d.MiscFlags = ResourceOptionFlags.None;
            staging = device.CreateTexture2D(ref d);
        }
        var ctx = device.ImmediateContext;
        ctx.CopyResource(staging, nv12);
        var size = width * height * 3 / 2;
        var buffer = MediaFactory.MFCreateMemoryBuffer(size);
        var map = ctx.Map(staging, 0, MapMode.Read, MapFlags.None);
        try
        {
            buffer.Lock(out var dst, out _, out _);
            unsafe
            {
                var src = (byte*)map.DataPointer;
                var d = (byte*)dst;
                var pitch = (int)map.RowPitch;
                // Y plane, then the interleaved UV plane, which follows the Y rows in the mapped texture.
                for (var y = 0; y < height; y++) Buffer.MemoryCopy(src + y * pitch, d + y * width, width, width);
                var uv = src + pitch * height;
                for (var y = 0; y < height / 2; y++) Buffer.MemoryCopy(uv + y * pitch, d + width * height + y * width, width, width);
            }
            buffer.Unlock();
            buffer.CurrentLength = size;
        }
        finally
        {
            ctx.Unmap(staging, 0);
        }
        return buffer;
    }

    private void EventLoop()
    {
        while (!disposed)
        {
            IMFMediaEvent ev;
            try
            {
                ev = events!.GetEvent(0);
            }
            catch (Exception)
            {
                return;   // shut down
            }
            using (ev)
            {
                switch (ev.EventType)
                {
                    case MediaEventTypes.TransformNeedInput:
                        Interlocked.Increment(ref needInput);
                        break;
                    case MediaEventTypes.TransformHaveOutput:
                        Drain();
                        break;
                    case MediaEventTypes.TransformDrainComplete:
                        return;
                }
            }
        }
    }

    /// <summary>Takes encoded pictures out: one per "have output" event (async), or all that are ready (sync).</summary>
    private void Drain()
    {
        while (!disposed)
        {
            var provides = (outputInfo.Flags & (int)(OutputStreamInfoFlags.OutputStreamProvidesSamples | OutputStreamInfoFlags.OutputStreamCanProvideSamples)) != 0;
            IMFSample? own = null;
            if (!provides)
            {
                own = MediaFactory.MFCreateSample();
                using var mb = MediaFactory.MFCreateMemoryBuffer(Math.Max(outputInfo.Size, width * height));
                own.AddBuffer(mb);
            }
            var odb = new OutputDataBuffer { StreamID = 0, Sample = own! };
            Result r;
            try
            {
                r = mft.ProcessOutput(ProcessOutputFlags.None, 1, ref odb, out _);
            }
            catch (SharpGenException e)
            {
                r = e.ResultCode;
            }
            odb.Events?.Dispose();
            if (r.Code == MF_E_TRANSFORM_STREAM_CHANGE)
            {
                own?.Dispose();
                using (var t = mft.GetOutputAvailableType(0, 0)) mft.SetOutputType(0, t, 0);
                outputInfo = mft.GetOutputStreamInfo(0);
                continue;
            }
            if (r.Failure)
            {
                own?.Dispose();
                if (r.Code != MF_E_TRANSFORM_NEED_MORE_INPUT) Log.Write($"encoder output failed: 0x{r.Code:X8}");
                return;
            }
            var s = odb.Sample;
            if (s is not null)
            {
                Emit(s);
                if (!ReferenceEquals(s, own)) s.Dispose();
            }
            own?.Dispose();
            if (isAsync) return;
        }
    }

    private void Emit(IMFSample s)
    {
        using var buf = s.ConvertToContiguousBuffer();
        buf.Lock(out var ptr, out _, out var length);
        byte[] data;
        try
        {
            data = new byte[length];
            Marshal.Copy(ptr, data, 0, length);
        }
        finally
        {
            buf.Unlock();
        }
        if (data.Length == 0) return;
        var key = AnnexB.IsKeyframe(data);
        Output?.Invoke(data, s.SampleTime / 10, key);
    }

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        try
        {
            mft.ProcessMessage(TMessageType.MessageNotifyEndOfStream, UIntPtr.Zero);
            mft.ProcessMessage(TMessageType.MessageNotifyEndStreaming, UIntPtr.Zero);
        }
        catch (Exception)
        {
        }
        if (isAsync)
        {
            // Unblocks GetEvent on the event thread.
            try
            {
                MediaFactory.MFShutdownObject(mft);
            }
            catch (Exception)
            {
            }
            eventThread?.Join(500);
        }
        if (codec is not null) Marshal.ReleaseComObject(codec);
        events?.Dispose();
        try
        {
            activate.ShutdownObject();
        }
        catch (Exception)
        {
        }
        mft.Dispose();
        activate.Dispose();
        manager?.Dispose();
        staging?.Dispose();
    }
}
