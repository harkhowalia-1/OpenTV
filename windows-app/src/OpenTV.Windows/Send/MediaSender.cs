using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Text;
using System.Text.RegularExpressions;
using OpenTV.Core;
using OpenTV.Windows.Native;
using Windows.Media.Control;

namespace OpenTV.Windows.Send;

/// <summary>
/// "Send to TV" for a video or song playing on this PC (Media Player, Films &amp; TV, VLC, MPC-HC, PotPlayer,
/// Windows Media Player, mpv...). Windows' media controls say what is playing and where, but not which file,
/// so the file is found from the players' command lines and the recently opened files, matched by title. The
/// file is then served from this PC (<see cref="MediaServer"/>) and the TV's player opens it at the same moment.
/// </summary>
internal static partial class MediaSender
{
    public sealed record Playing(string Path, double? Position, string Player, GlobalSystemMediaTransportControlsSession? Session);

    private static readonly string[] PlayerProcesses =
    [
        "vlc", "mpc-hc64", "mpc-hc", "mpc-be64", "mpc-be", "PotPlayerMini64", "PotPlayerMini", "PotPlayer64", "wmplayer",
        "mpv", "Microsoft.Media.Player", "Video.UI", "Music.UI", "KMPlayer64X", "GOM", "smplayer",
    ];

    private static readonly string[] PlayerApps =
    [
        "ZuneVideo", "ZuneMusic", "vlc", "mpc-hc", "mpc-be", "PotPlayer", "wmplayer", "mpv", "Media.Player", "KMPlayer", "GOM", "smplayer",
    ];

    /// <summary>The media file playing on this PC, if it can be told. Call off the UI thread.</summary>
    public static async Task<Playing?> FindAsync()
    {
        GlobalSystemMediaTransportControlsSession? session = null;
        string? title = null;
        try
        {
            var manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            var sessions = manager.GetSessions()
                .Where(s => PlayerApps.Any(a => (s.SourceAppUserModelId ?? "").Contains(a, StringComparison.OrdinalIgnoreCase)))
                .OrderBy(s => s.GetPlaybackInfo().PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing ? 0 : 1)
                .ToList();
            session = sessions.FirstOrDefault();
            if (session is not null) title = (await session.TryGetMediaPropertiesAsync())?.Title;
        }
        catch (Exception e)
        {
            Log.Error("media controls", e);
        }

        var candidates = new List<(string path, string player)>();
        foreach (var name in PlayerProcesses)
        {
            foreach (var p in Process.GetProcessesByName(name))
            {
                using (p)
                {
                    foreach (var f in FilesIn(Win32.CommandLine(p.Id))) candidates.Add((f, p.ProcessName));
                    if (TitleFile(p) is { } t) title ??= t;
                }
            }
        }
        foreach (var f in RecentMediaFiles()) candidates.Add((f, "Recent"));

        var playable = candidates.Where(c => MediaServer.CanPlayOnTv(c.path)).DistinctBy(c => c.path.ToLowerInvariant()).ToList();
        (string path, string player)? pick = null;
        if (!string.IsNullOrWhiteSpace(title))
        {
            var t = Simplify(title);
            pick = playable.FirstOrDefault(c => Simplify(Path.GetFileNameWithoutExtension(c.path)) == t);
            if (pick?.path is null) pick = playable.FirstOrDefault(c => t.Length >= 4 && Simplify(Path.GetFileNameWithoutExtension(c.path)).Contains(t));
            if (pick?.path is null) pick = playable.FirstOrDefault(c => Simplify(Path.GetFileNameWithoutExtension(c.path)) is { Length: >= 4 } n && t.Contains(n));
        }
        // No title to go by: a player that was started with exactly one file.
        if (pick?.path is null)
        {
            var fromPlayers = playable.Where(c => c.player != "Recent").ToList();
            if (fromPlayers.Count == 1) pick = fromPlayers[0];
        }
        if (pick?.path is not { } path) return null;

        double? position = null;
        if (session is not null)
        {
            var tl = session.GetTimelineProperties();
            var pos = tl.Position;
            if (session.GetPlaybackInfo().PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing)
            {
                pos += DateTimeOffset.Now - tl.LastUpdatedTime;
            }
            if (pos > TimeSpan.Zero) position = pos.TotalSeconds;
        }
        return new Playing(path, position, pick.Value.player, session);
    }

    private static string Simplify(string s) => NonWord().Replace(s.ToLowerInvariant(), "");

    [GeneratedRegex(@"[^\p{L}\p{N}]+")]
    private static partial Regex NonWord();

    [GeneratedRegex("\"([^\"]+)\"|(\\S+)")]
    private static partial Regex Argument();

    /// <summary>Existing files named on a command line (quoted or not).</summary>
    private static IEnumerable<string> FilesIn(string? commandLine)
    {
        if (string.IsNullOrEmpty(commandLine)) yield break;
        var args = Argument().Matches(commandLine).Select(m => m.Groups[1].Success ? m.Groups[1].Value : m.Groups[2].Value).Skip(1);
        foreach (var a in args)
        {
            var path = a.StartsWith("file:///", StringComparison.OrdinalIgnoreCase) ? Uri.UnescapeDataString(a[8..]).Replace('/', '\\') : a;
            if (path.Length > 3 && path[1] == ':' && File.Exists(path)) yield return path;
        }
    }

    /// <summary>Players that put the file name in their window title ("movie.mkv - VLC media player").</summary>
    private static string? TitleFile(Process p)
    {
        var t = p.MainWindowTitle;
        if (string.IsNullOrWhiteSpace(t)) return null;
        foreach (var suffix in new[] { " - VLC media player", " - MPC-HC", " - MPC-BE", " - PotPlayer", " - mpv", " - Windows Media Player" })
        {
            var i = t.IndexOf(suffix, StringComparison.OrdinalIgnoreCase);
            if (i > 0) t = t[..i];
        }
        return Path.GetFileNameWithoutExtension(t.Trim());
    }

    /// <summary>Media files opened recently (Explorer's Recent items), newest first.</summary>
    private static IEnumerable<string> RecentMediaFiles()
    {
        var dir = Environment.GetFolderPath(Environment.SpecialFolder.Recent);
        if (!Directory.Exists(dir)) return [];
        var links = new DirectoryInfo(dir).GetFiles("*.lnk")
            .Where(f => MediaServer.CanPlayOnTv(Path.GetFileNameWithoutExtension(f.Name)))
            .OrderByDescending(f => f.LastWriteTimeUtc)
            .Take(30);
        var list = new List<string>();
        foreach (var l in links)
        {
            if (ShellLink.Target(l.FullName) is { } target && File.Exists(target)) list.Add(target);
        }
        return list;
    }

    public static async Task PauseAsync(GlobalSystemMediaTransportControlsSession? session)
    {
        try
        {
            if (session is not null) await session.TryPauseAsync();
        }
        catch (Exception e)
        {
            Log.Error("pausing the player", e);
        }
    }
}

/// <summary>Reads where a .lnk shortcut points (the shell's ShellLink object).</summary>
internal static class ShellLink
{
    [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
    private class CShellLink;

    [ComImport, Guid("000214F9-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellLinkW
    {
        void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszFile, int cch, IntPtr pfd, uint fFlags);
    }

    public static string? Target(string lnk)
    {
        object? link = null;
        try
        {
            link = new CShellLink();
            ((IPersistFile)link).Load(lnk, 0);
            var sb = new StringBuilder(1024);
            ((IShellLinkW)link).GetPath(sb, sb.Capacity, IntPtr.Zero, 0);
            return sb.Length > 0 ? sb.ToString() : null;
        }
        catch (Exception)
        {
            return null;
        }
        finally
        {
            if (link is not null) Marshal.ReleaseComObject(link);
        }
    }
}
