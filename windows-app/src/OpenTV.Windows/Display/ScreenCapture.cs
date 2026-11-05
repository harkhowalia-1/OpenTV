using System.Runtime.InteropServices;
using OpenTV.Windows.Native;
using OpenTV.Windows.Pointer;
using SharpGen.Runtime;
using Vortice;
using Vortice.Direct3D;
using Vortice.Direct3D11;
using Vortice.DXGI;

namespace OpenTV.Windows.Display;

/// <summary>
/// Captures one monitor with DXGI Desktop Duplication (frames arrive only when something on screen changed,
/// straight from the GPU's desktop image, with no yellow capture border), draws the mouse cursor in, and has
/// the GPU's video processor scale it to the TV's resolution and convert it to NV12 for the encoder in one
/// pass. Nothing leaves the GPU.
/// </summary>
internal sealed class ScreenCapture : IDisposable
{
    private const int DxgiErrorWaitTimeout = unchecked((int)0x887A0027);
    private const int DxgiErrorAccessLost = unchecked((int)0x887A0026);
    private const int RingSize = 4;

    private readonly IDXGIAdapter1 adapter;
    private readonly IDXGIOutput1 output;
    private readonly ID3D11VideoDevice videoDevice;
    private readonly ID3D11VideoContext videoContext;
    private readonly ID3D11Texture2D desktop;
    private readonly ID3D11VideoProcessorEnumerator vpEnumerator;
    private readonly ID3D11VideoProcessor vp;
    private readonly ID3D11VideoProcessorInputView inputView;
    private readonly ID3D11Texture2D[] ring = new ID3D11Texture2D[RingSize];
    private readonly ID3D11VideoProcessorOutputView[] outputViews = new ID3D11VideoProcessorOutputView[RingSize];
    private IDXGIOutputDuplication? duplication;
    private long nextDuplicationTry;
    private int next;
    private IntPtr cursorHandle;
    private Point cursorHotspot;

    public ID3D11Device Device { get; }
    public ID3D11DeviceContext Context { get; }

    /// <summary>The monitor's place on the Windows desktop.</summary>
    public Rectangle Bounds { get; }

    public int Width { get; }
    public int Height { get; }
    public int SourceWidth { get; }
    public int SourceHeight { get; }

    /// <summary>A picture has been captured since the last one was converted.</summary>
    public bool Dirty { get; private set; }

    /// <summary>At least one picture has been captured (so a keyframe can always be made from it).</summary>
    public bool HasFrame { get; private set; }

    public string AdapterName { get; }

    /// <param name="monitorDeviceName">"\\.\DISPLAY1" etc.; null for the primary monitor.</param>
    public ScreenCapture(string? monitorDeviceName, int width, int height, int fps)
    {
        Width = width & ~1;
        Height = height & ~1;
        using (var factory = DXGI.CreateDXGIFactory1<IDXGIFactory1>())
        {
            (adapter, var o) = FindOutput(factory, monitorDeviceName);
            output = o.QueryInterface<IDXGIOutput1>();
            o.Dispose();
        }
        AdapterName = adapter.Description1.Description;
        var desc = output.Description;
        Bounds = Rectangle.FromLTRB(desc.DesktopCoordinates.Left, desc.DesktopCoordinates.Top, desc.DesktopCoordinates.Right, desc.DesktopCoordinates.Bottom);

        // The device must live on the GPU that drives this monitor, or duplication fails (hybrid laptops).
        D3D11.D3D11CreateDevice(adapter, DriverType.Unknown, DeviceCreationFlags.BgraSupport | DeviceCreationFlags.VideoSupport,
            [FeatureLevel.Level_11_1, FeatureLevel.Level_11_0, FeatureLevel.Level_10_1], out ID3D11Device? device, out ID3D11DeviceContext? context).CheckError();
        Device = device!;
        Context = context!;
        using (var mt = Device.QueryInterface<ID3D11Multithread>()) mt.SetMultithreadProtected(true);   // Media Foundation shares it

        duplication = output.DuplicateOutput(Device);
        var mode = duplication.Description.ModeDescription;
        SourceWidth = (int)mode.Width;
        SourceHeight = (int)mode.Height;

        // A copy of the desktop that GDI can draw the cursor onto.
        var dd = new Texture2DDescription
        {
            Width = (uint)SourceWidth, Height = (uint)SourceHeight, MipLevels = 1, ArraySize = 1, Format = Format.B8G8R8A8_UNorm,
            SampleDescription = new SampleDescription(1, 0), Usage = ResourceUsage.Default,
            BindFlags = BindFlags.RenderTarget | BindFlags.ShaderResource, MiscFlags = ResourceOptionFlags.GdiCompatible,
        };
        desktop = Device.CreateTexture2D(ref dd);

        videoDevice = Device.QueryInterface<ID3D11VideoDevice>();
        videoContext = Context.QueryInterface<ID3D11VideoContext>();
        var content = new VideoProcessorContentDescription
        {
            InputFrameFormat = VideoFrameFormat.Progressive,
            InputWidth = (uint)SourceWidth, InputHeight = (uint)SourceHeight,
            OutputWidth = (uint)Width, OutputHeight = (uint)Height,
            InputFrameRate = new Rational((uint)fps, 1), OutputFrameRate = new Rational((uint)fps, 1),
            Usage = VideoUsage.OptimalSpeed,
        };
        vpEnumerator = videoDevice.CreateVideoProcessorEnumerator(content);
        vp = videoDevice.CreateVideoProcessor(vpEnumerator, 0);
        inputView = videoDevice.CreateVideoProcessorInputView(desktop, vpEnumerator, new VideoProcessorInputViewDescription
        {
            FourCC = 0, ViewDimension = VideoProcessorInputViewDimension.Texture2D,
            Texture2D = new Texture2DVideoProcessorInputView { MipSlice = 0, ArraySlice = 0 },
        });
        for (var i = 0; i < RingSize; i++)
        {
            ring[i] = CreateNv12(Device, Width, Height);
            outputViews[i] = videoDevice.CreateVideoProcessorOutputView(ring[i], vpEnumerator, new VideoProcessorOutputViewDescription
            {
                ViewDimension = VideoProcessorOutputViewDimension.Texture2D,
                Texture2D = new Texture2DVideoProcessorOutputView { MipSlice = 0 },
            });
        }

        // Full-range RGB in, BT.709 limited-range video out (what the encoder signals and the TV expects).
        videoContext.VideoProcessorSetStreamColorSpace(vp, 0, new VideoProcessorColorSpace { RGB_Range = 0, Nominal_Range = 2 });
        videoContext.VideoProcessorSetOutputColorSpace(vp, new VideoProcessorColorSpace { YCbCr_Matrix = 1, Nominal_Range = 1 });
        videoContext.VideoProcessorSetStreamFrameFormat(vp, 0, VideoFrameFormat.Progressive);
        videoContext.VideoProcessorSetStreamAutoProcessingMode(vp, 0, false);   // no driver "enhancements": they add delay
        // Keep the shape: letterbox inside the TV's picture.
        var scale = Math.Min(Width / (double)SourceWidth, Height / (double)SourceHeight);
        int w = (int)(SourceWidth * scale) & ~1, h = (int)(SourceHeight * scale) & ~1;
        int x = (Width - w) / 2 & ~1, y = (Height - h) / 2 & ~1;
        videoContext.VideoProcessorSetStreamSourceRect(vp, 0, true, new RawRect(0, 0, SourceWidth, SourceHeight));
        videoContext.VideoProcessorSetStreamDestRect(vp, 0, true, new RawRect(x, y, x + w, y + h));
        videoContext.VideoProcessorSetOutputTargetRect(vp, true, new RawRect(0, 0, Width, Height));
        videoContext.VideoProcessorSetOutputBackgroundColor(vp, true, new VideoColor
        {
            YCbCr = new VideoColorYCbCrA { Y = 16 / 255f, Cb = 0.5f, Cr = 0.5f, A = 1 },
        });
    }

    private static ID3D11Texture2D CreateNv12(ID3D11Device device, int width, int height)
    {
        var d = new Texture2DDescription
        {
            Width = (uint)width, Height = (uint)height, MipLevels = 1, ArraySize = 1, Format = Format.NV12,
            SampleDescription = new SampleDescription(1, 0), Usage = ResourceUsage.Default,
            BindFlags = BindFlags.RenderTarget | BindFlags.VideoEncoder,
        };
        try
        {
            return device.CreateTexture2D(ref d);
        }
        catch (SharpGenException)
        {
            d.BindFlags = BindFlags.RenderTarget;   // older drivers don't know the encoder bind flag
            return device.CreateTexture2D(ref d);
        }
    }

    /// <summary>All monitors Windows is showing the desktop on, as (device name, friendly label).</summary>
    public static List<(string device, string label, Rectangle bounds)> Monitors()
    {
        var list = new List<(string, string, Rectangle)>();
        var i = 1;
        foreach (var s in Screen.AllScreens.OrderBy(s => s.Primary ? 0 : 1).ThenBy(s => s.Bounds.Left))
        {
            list.Add((s.DeviceName, $"Screen {i++}{(s.Primary ? " (main)" : "")} · {s.Bounds.Width}×{s.Bounds.Height}", s.Bounds));
        }
        return list;
    }

    /// <summary>The adapter and output showing <paramref name="deviceName"/> (or the primary monitor).</summary>
    private static (IDXGIAdapter1, IDXGIOutput) FindOutput(IDXGIFactory1 factory, string? deviceName)
    {
        IDXGIAdapter1? fallbackAdapter = null;
        IDXGIOutput? fallbackOutput = null;
        for (uint a = 0; factory.EnumAdapters1(a, out var adapter).Success; a++)
        {
            var keep = false;
            for (uint o = 0; adapter.EnumOutputs(o, out var output).Success; o++)
            {
                var d = output.Description;
                var primary = d.DesktopCoordinates.Left == 0 && d.DesktopCoordinates.Top == 0;
                var wanted = d.AttachedToDesktop && (deviceName is null
                    ? primary
                    : string.Equals(d.DeviceName, deviceName, StringComparison.OrdinalIgnoreCase));
                if (wanted)
                {
                    fallbackOutput?.Dispose();
                    if (fallbackAdapter is not null && fallbackAdapter != adapter) fallbackAdapter.Dispose();
                    return (adapter, output);
                }
                if (d.AttachedToDesktop && fallbackOutput is null)
                {
                    fallbackOutput = output;
                    fallbackAdapter = adapter;
                    keep = true;
                }
                else
                {
                    output.Dispose();
                }
            }
            if (!keep) adapter.Dispose();
        }
        // The chosen monitor is gone: use the first one there is.
        return fallbackAdapter is not null && fallbackOutput is not null
            ? (fallbackAdapter, fallbackOutput)
            : throw new InvalidOperationException("No screen to capture.");
    }

    /// <summary>
    /// Waits up to <paramref name="timeoutMs"/> for the screen to change. Returns true when a new picture was
    /// captured (it then waits in the desktop copy until <see cref="Convert"/>).
    /// </summary>
    public bool Acquire(int timeoutMs)
    {
        if (duplication is null && !Reopen())
        {
            Thread.Sleep(Math.Min(timeoutMs, 50));
            return false;
        }
        var r = duplication!.AcquireNextFrame((uint)Math.Max(0, timeoutMs), out var info, out var resource);
        if (r.Code == DxgiErrorWaitTimeout) return false;
        if (r.Code == DxgiErrorAccessLost)
        {
            // Mode change, full-screen app, UAC prompt, lock screen: start duplicating again.
            duplication.Dispose();
            duplication = null;
            return false;
        }
        r.CheckError();
        try
        {
            if (info.LastPresentTime != 0 || info.LastMouseUpdateTime != 0 || !HasFrame)
            {
                using var tex = resource!.QueryInterface<ID3D11Texture2D>();
                Context.CopyResource(desktop, tex);
                DrawCursor();
                Dirty = true;
                HasFrame = true;
                return true;
            }
            return false;
        }
        finally
        {
            resource?.Dispose();
            duplication.ReleaseFrame();
        }
    }

    private bool Reopen()
    {
        if (Environment.TickCount64 < nextDuplicationTry) return false;
        try
        {
            duplication = output.DuplicateOutput(Device);
            return true;
        }
        catch (SharpGenException)
        {
            nextDuplicationTry = Environment.TickCount64 + 250;   // the secure desktop is up; try again shortly
            return false;
        }
    }

    /// <summary>The cursor isn't part of the duplicated image: draw the current one in with GDI.</summary>
    private void DrawCursor()
    {
        if (CursorHider.Hidden) return;   // it's on the TV right now
        var ci = new Win32.CURSORINFO { cbSize = Marshal.SizeOf<Win32.CURSORINFO>() };
        if (!Win32.GetCursorInfo(ref ci) || (ci.flags & Win32.CURSOR_SHOWING) == 0 || ci.hCursor == IntPtr.Zero) return;
        if (!Bounds.Contains(ci.ptScreenPos.X, ci.ptScreenPos.Y)) return;
        if (ci.hCursor != cursorHandle && Win32.GetIconInfo(ci.hCursor, out var ii))
        {
            cursorHandle = ci.hCursor;
            cursorHotspot = new Point(ii.xHotspot, ii.yHotspot);
            if (ii.hbmMask != IntPtr.Zero) Win32.DeleteObject(ii.hbmMask);
            if (ii.hbmColor != IntPtr.Zero) Win32.DeleteObject(ii.hbmColor);
        }
        // Desktop coordinates are in pixels of the full desktop; the duplicated image is this monitor's.
        var sx = SourceWidth / (double)Math.Max(1, Bounds.Width);
        var sy = SourceHeight / (double)Math.Max(1, Bounds.Height);
        var x = (int)((ci.ptScreenPos.X - Bounds.Left) * sx) - cursorHotspot.X;
        var y = (int)((ci.ptScreenPos.Y - Bounds.Top) * sy) - cursorHotspot.Y;
        using var surface = desktop.QueryInterface<IDXGISurface1>();
        var hdc = surface.GetDC(false);
        try
        {
            Win32.DrawIconEx(hdc, x, y, ci.hCursor, 0, 0, 0, IntPtr.Zero, Win32.DI_NORMAL);
        }
        finally
        {
            surface.ReleaseDC(null);
        }
    }

    /// <summary>Scales and converts the latest picture to NV12 at the TV's size; returns the texture to encode.</summary>
    public ID3D11Texture2D Convert()
    {
        var i = next;
        next = (next + 1) % RingSize;
        var streams = new[] { new VideoProcessorStream { Enable = true, InputSurface = inputView } };
        videoContext.VideoProcessorBlt(vp, outputViews[i], 0, 1, streams).CheckError();
        Dirty = false;
        return ring[i];
    }

    public void Dispose()
    {
        duplication?.Dispose();
        foreach (var v in outputViews) v?.Dispose();
        foreach (var t in ring) t?.Dispose();
        inputView.Dispose();
        vp.Dispose();
        vpEnumerator.Dispose();
        videoContext.Dispose();
        videoDevice.Dispose();
        desktop.Dispose();
        Context.Dispose();
        Device.Dispose();
        output.Dispose();
        adapter.Dispose();
    }
}
