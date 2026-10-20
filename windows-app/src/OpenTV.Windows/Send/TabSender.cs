using System.Diagnostics;
using System.Globalization;
using System.Windows.Automation;
using OpenTV.Windows.Native;
using Windows.Media.Control;

namespace OpenTV.Windows.Send;

/// <summary>
/// "Send to TV" for the browser: reads the address bar of the browser window used last (Chrome, Edge, Firefox,
/// Brave, Opera, Vivaldi) with UI Automation, the accessibility interface every one of them supports, so no
/// browser extension or setting is needed. For a YouTube video it also reads the player's position from the
/// page's seek slider and pauses the video here, so it simply carries on on the TV.
/// </summary>
internal static class TabSender
{
    public sealed record Tab(string Url, double? Time, string Browser, string Process);

    private static readonly Dictionary<string, string> Browsers = new(StringComparer.OrdinalIgnoreCase)
    {
        ["chrome"] = "Chrome", ["msedge"] = "Edge", ["firefox"] = "Firefox", ["brave"] = "Brave",
        ["opera"] = "Opera", ["vivaldi"] = "Vivaldi", ["chromium"] = "Chromium",
    };

    /// <summary>The front tab of the browser highest in the window stack. Call off the UI thread.</summary>
    public static Tab? FrontTab()
    {
        foreach (var (hwnd, process) in BrowserWindows())
        {
            try
            {
                var url = ReadUrl(hwnd, process);
                if (url is null) continue;
                double? time = null;
                if (IsYouTube(url)) time = TimeFromUrl(url) ?? YouTubeTime(hwnd);
                return new Tab(url, time, Browsers[process], process);
            }
            catch (Exception e) when (e is ElementNotAvailableException or InvalidOperationException or System.Runtime.InteropServices.COMException)
            {
                Log.Error("reading the address bar", e);
            }
        }
        return null;
    }

    /// <summary>Top-level browser windows, front to back (EnumWindows walks the Z order from the top).</summary>
    private static List<(IntPtr hwnd, string process)> BrowserWindows()
    {
        var list = new List<(IntPtr, string)>();
        var names = new Dictionary<uint, string>();
        Win32.EnumWindows((h, _) =>
        {
            if (!Win32.IsWindowVisible(h)) return true;
            var cls = Win32.ClassName(h);
            if (cls is not ("Chrome_WidgetWin_1" or "MozillaWindowClass")) return true;
            if (Win32.WindowText(h).Length == 0) return true;
            Win32.GetWindowThreadProcessId(h, out var pid);
            if (!names.TryGetValue(pid, out var name))
            {
                try
                {
                    name = Process.GetProcessById((int)pid).ProcessName;
                }
                catch (ArgumentException)
                {
                    name = "";
                }
                names[pid] = name;
            }
            if (Browsers.ContainsKey(name)) list.Add((h, name.ToLowerInvariant()));
            return true;
        }, IntPtr.Zero);
        // Minimized windows last.
        return list.OrderBy(w => Win32.IsIconic(w.Item1) ? 1 : 0).ToList();
    }

    private static string? ReadUrl(IntPtr hwnd, string process)
    {
        var root = AutomationElement.FromHandle(hwnd);
        AutomationElement? bar;
        if (process == "firefox")
        {
            bar = root.FindFirst(TreeScope.Descendants, new PropertyCondition(AutomationElement.AutomationIdProperty, "urlbar-input"));
        }
        else
        {
            // Chromium browsers: the first edit field in tree order is the address bar (the toolbar comes before
            // the page). Its name is localized, so it isn't matched by name.
            bar = root.FindFirst(TreeScope.Descendants, new AndCondition(
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Edit),
                new PropertyCondition(AutomationElement.IsValuePatternAvailableProperty, true)));
        }
        if (bar is null || !bar.TryGetCurrentPattern(ValuePattern.Pattern, out var p)) return null;
        return Normalize(((ValuePattern)p).Current.Value);
    }

    /// <summary>
    /// Address bars hide "https://" (and sometimes "www."): put the scheme back. Text that isn't an address
    /// (the user is typing a search) gives null.
    /// </summary>
    public static string? Normalize(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;
        var t = text.Trim();
        if (t.Contains(' ')) return null;
        if (!t.Contains("://"))
        {
            if (!t.Contains('.') && !t.StartsWith("localhost", StringComparison.OrdinalIgnoreCase)) return null;
            t = "https://" + t;
        }
        return Uri.TryCreate(t, UriKind.Absolute, out var u) && u.Scheme is "http" or "https" ? u.ToString() : null;
    }

    public static bool IsYouTube(string url) =>
        Uri.TryCreate(url, UriKind.Absolute, out var u) &&
        (u.Host.EndsWith("youtube.com", StringComparison.OrdinalIgnoreCase) || u.Host.Equals("youtu.be", StringComparison.OrdinalIgnoreCase));

    private static double? TimeFromUrl(string url)
    {
        var q = new Uri(url).Query.TrimStart('?').Split('&');
        var t = q.FirstOrDefault(x => x.StartsWith("t=", StringComparison.Ordinal))?[2..].TrimEnd('s');
        return double.TryParse(t, NumberStyles.Float, CultureInfo.InvariantCulture, out var s) && s > 0 ? s : null;
    }

    /// <summary>
    /// The YouTube player's position: its progress bar is a slider whose value is the current second. Browsers
    /// build the page's accessibility tree on first request, so this tries a few times.
    /// </summary>
    private static double? YouTubeTime(IntPtr hwnd)
    {
        var root = AutomationElement.FromHandle(hwnd);
        for (var attempt = 0; attempt < 3; attempt++)
        {
            var doc = root.FindFirst(TreeScope.Descendants, new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Document));
            if (doc is not null)
            {
                var sliders = doc.FindAll(TreeScope.Descendants, new AndCondition(
                    new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Slider),
                    new PropertyCondition(AutomationElement.IsRangeValuePatternAvailableProperty, true)));
                double? best = null;
                foreach (AutomationElement s in sliders)
                {
                    if (!s.TryGetCurrentPattern(RangeValuePattern.Pattern, out var p)) continue;
                    var range = ((RangeValuePattern)p).Current;
                    var name = s.Current.Name ?? "";
                    // "Seek slider" in English; otherwise the slider that isn't the 0-100 volume control.
                    if (name.Contains("seek", StringComparison.OrdinalIgnoreCase)) return range.Value;
                    if (range.Maximum > 0 && Math.Abs(range.Maximum - 100) > 0.001) best ??= range.Value;
                }
                if (best is not null) return best;
            }
            Thread.Sleep(200);
        }
        return null;
    }

    /// <summary>Pauses whatever that browser is playing (Windows' media controls), once the TV has taken over.</summary>
    public static async Task PauseAsync(string process)
    {
        try
        {
            var manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            foreach (var s in manager.GetSessions())
            {
                var app = s.SourceAppUserModelId ?? "";
                var match = app.Contains(process, StringComparison.OrdinalIgnoreCase) ||
                            (process == "msedge" && app.Contains("Edge", StringComparison.OrdinalIgnoreCase)) ||
                            (process == "firefox" && app.Contains("308046B0AF4A39CB", StringComparison.OrdinalIgnoreCase));
                if (match && s.GetPlaybackInfo().PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing)
                {
                    await s.TryPauseAsync();
                }
            }
        }
        catch (Exception e)
        {
            Log.Error("pausing the browser", e);
        }
    }
}
