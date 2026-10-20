using System.Diagnostics;
using System.Runtime.InteropServices;

namespace OpenTV.Core;

/// <summary>
/// Wired connection: when the Chromecast is plugged into this PC by USB (with USB debugging on) and adb is
/// installed, the TV's servers are forwarded to local ports and the remote, pointer and display stream use
/// the cable instead of Wi-Fi. Does nothing when adb isn't installed; never installs anything.
/// </summary>
public sealed class AdbLink : IDisposable
{
    public readonly record struct Ports(int Http, int Control, int Display);

    public static readonly Ports DefaultPorts = new(18800, 18801, 18802);

    private readonly string? adb;
    private readonly Ports ports;
    private Timer? timer;
    private string? serial;
    private TvDevice? current;
    private int checking;

    /// <summary>The TV as reached over USB, or null when it's unplugged. Raised on a pool thread.</summary>
    public event Action<TvDevice?>? Changed;

    public AdbLink(Ports? localPorts = null, string? adbPath = null)
    {
        adb = adbPath ?? FindAdb();
        ports = localPorts ?? DefaultPorts;
    }

    public bool Available => adb is not null;
    public string? AdbPath => adb;

    public void Start(TimeSpan? every = null)
    {
        if (adb is null || timer is not null) return;
        timer = new Timer(_ => _ = CheckAsync(), null, TimeSpan.Zero, every ?? TimeSpan.FromSeconds(4));
    }

    public void Dispose()
    {
        timer?.Dispose();
        timer = null;
    }

    public static string? FindAdb()
    {
        var exe = RuntimeInformation.IsOSPlatform(OSPlatform.Windows) ? "adb.exe" : "adb";
        var candidates = new List<string>();
        foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            candidates.Add(Path.Combine(dir.Trim('"'), exe));
        }
        var local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var sdk = Environment.GetEnvironmentVariable("ANDROID_HOME") ?? Environment.GetEnvironmentVariable("ANDROID_SDK_ROOT");
        if (sdk is not null) candidates.Add(Path.Combine(sdk, "platform-tools", exe));
        candidates.Add(Path.Combine(local, "Android", "Sdk", "platform-tools", exe));
        candidates.Add(Path.Combine(home, "platform-tools", exe));
        candidates.Add(@"C:\platform-tools\adb.exe");
        candidates.Add("/opt/homebrew/share/android-commandlinetools/platform-tools/adb");
        candidates.Add("/opt/homebrew/bin/adb");
        candidates.Add(Path.Combine(home, "Library", "Android", "sdk", "platform-tools", "adb"));
        return candidates.FirstOrDefault(File.Exists);
    }

    /// <summary>
    /// Serials of devices connected by cable and ready, from <c>adb devices -l</c>: wireless debugging
    /// connections ("ip:port", mDNS names) and emulators are left out.
    /// </summary>
    public static List<string> UsbSerials(string devicesOutput)
    {
        var list = new List<string>();
        foreach (var raw in devicesOutput.Split('\n'))
        {
            var line = raw.Trim();
            if (line.Length == 0 || line.StartsWith("List of devices") || line.StartsWith('*')) continue;
            var cols = line.Split((char[])[' ', '\t'], StringSplitOptions.RemoveEmptyEntries);
            if (cols.Length < 2 || cols[1] != "device") continue;
            var s = cols[0];
            if (s.Contains(':') || s.StartsWith("emulator-") || s.Contains("._adb-tls-")) continue;
            list.Add(s);
        }
        return list;
    }

    public async Task CheckAsync()
    {
        if (adb is null || Interlocked.Exchange(ref checking, 1) == 1) return;
        try
        {
            var usb = UsbSerials(await RunAsync(adb, "devices -l").ConfigureAwait(false)).FirstOrDefault();
            if (usb == serial && (usb is null || current is not null)) return;
            serial = usb;
            var tv = usb is null ? null : await ForwardAsync(usb).ConfigureAwait(false);
            if (tv == current) return;
            current = tv;
            Changed?.Invoke(tv);
        }
        catch (Exception)
        {
        }
        finally
        {
            Interlocked.Exchange(ref checking, 0);
        }
    }

    /// <summary>Forwards the TV's three ports and checks the API answers through them.</summary>
    public async Task<TvDevice?> ForwardAsync(string deviceSerial)
    {
        if (adb is null) return null;
        foreach (var (local, remote) in new[] { (ports.Http, 8800), (ports.Control, 8801), (ports.Display, 8802) })
        {
            await RunAsync(adb, $"-s {deviceSerial} forward tcp:{local} tcp:{remote}").ConfigureAwait(false);
        }
        var probe = new TvDevice { Name = "TV", Host = "127.0.0.1", Port = ports.Http, ControlPort = ports.Control, DisplayPort = ports.Display, Usb = true };
        try
        {
            var hello = await new RemoteClient(probe, null).HelloAsync().ConfigureAwait(false);
            return probe with { Name = hello.Name, TvId = hello.Id };
        }
        catch (RemoteException)
        {
            return null;
        }
    }

    /// <summary>Removes this link's port forwards (the protocol test cleans up after itself with it).</summary>
    public async Task RemoveForwardsAsync(string deviceSerial)
    {
        if (adb is null) return;
        foreach (var local in new[] { ports.Http, ports.Control, ports.Display })
        {
            await RunAsync(adb, $"-s {deviceSerial} forward --remove tcp:{local}").ConfigureAwait(false);
        }
    }

    public async Task<string> RunAsync(string tool, string args)
    {
        var psi = new ProcessStartInfo(tool, args)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        using var p = Process.Start(psi);
        if (p is null) return "";
        var output = p.StandardOutput.ReadToEndAsync();
        var err = p.StandardError.ReadToEndAsync();
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        try
        {
            await p.WaitForExitAsync(timeout.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            try
            {
                p.Kill();
            }
            catch (Exception)
            {
            }
        }
        await err.ConfigureAwait(false);
        return await output.ConfigureAwait(false);
    }
}
