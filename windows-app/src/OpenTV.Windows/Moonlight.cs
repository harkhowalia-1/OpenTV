using System.Diagnostics;

namespace OpenTV.Windows;

/// <summary>
/// The "Stream with Moonlight" path: game-streaming grade video (Sunshine on the PC, Moonlight on the TV) as an
/// alternative to OpenTV's own mirror. OpenTV never installs either; it finds Sunshine if it's there, opens its
/// settings page, and on request opens Moonlight's store page on the TV for the user to decide.
/// </summary>
internal static class Moonlight
{
    public const string SunshineReleases = "https://github.com/LizardByte/Sunshine/releases/latest";
    public const string ApolloReleases = "https://github.com/ClassicOldSong/Apollo/releases/latest";
    public const string SunshineWebUi = "https://localhost:47990";
    public const string MoonlightOnPlayStore = "https://play.google.com/store/apps/details?id=com.limelight";
    public const string VirtualDisplayDriver = "https://github.com/VirtualDrivers/Virtual-Display-Driver";

    /// <summary>Sunshine (or its Apollo fork), if installed: (name, path).</summary>
    public static (string name, string path)? FindHost()
    {
        var pf = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
        foreach (var (name, folder) in new[] { ("Sunshine", "Sunshine"), ("Apollo", "Apollo") })
        {
            var exe = Path.Combine(pf, folder, "sunshine.exe");
            if (File.Exists(exe)) return (name, exe);
        }
        return null;
    }

    public static bool HostRunning() => Process.GetProcessesByName("sunshine").Length > 0;

    public static void Open(string url)
    {
        try
        {
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch (Exception e)
        {
            Log.Error("opening " + url, e);
        }
    }
}
