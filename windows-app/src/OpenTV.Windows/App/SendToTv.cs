using OpenTV.Core;
using OpenTV.Windows.Send;

namespace OpenTV.Windows.App;

/// <summary>
/// "Send to TV": the video playing in a player on this PC (served to the TV from here), or else the front tab of
/// the browser used last. Whatever the TV took over is paused here, so it simply carries on on the TV.
/// </summary>
internal sealed class SendToTv(RemoteModel model) : IDisposable
{
    private readonly MediaServer media = new();
    private int busy;

    /// <summary>Sends whatever is open; returns a short line for the user. Call on the UI thread.</summary>
    public async Task<string> SendAsync()
    {
        if (model.Route is not { } route || model.Token is null) return "Connect to the TV first.";
        if (Interlocked.Exchange(ref busy, 1) == 1) return "Sending…";
        try
        {
            // Both lookups go through UI Automation and COM: off the UI thread.
            var playing = await Task.Run(() => MediaSender.FindAsync()).ConfigureAwait(true);
            var tab = await Task.Run(() => TabSender.FrontTab()).ConfigureAwait(true);

            if (playing is not null && (tab is null || IsPlaying(playing)))
            {
                return await SendFileAsync(route, playing).ConfigureAwait(true);
            }
            if (tab is not null)
            {
                var opened = await model.SendAsync(tab.Url, tab.Time).ConfigureAwait(true);
                if (opened is null) return TakeError() ?? "The TV couldn't open it.";
                _ = TabSender.PauseAsync(tab.Process);
                return $"{tab.Browser} → {Describe(opened)}";
            }
            return "Nothing open in a browser or player.";
        }
        catch (Exception e)
        {
            Log.Error("send to TV", e);
            return "Couldn't send that to the TV.";
        }
        finally
        {
            Interlocked.Exchange(ref busy, 0);
        }
    }

    private async Task<string> SendFileAsync(TvDevice route, MediaSender.Playing playing)
    {
        // Over USB the TV still fetches the file over Wi-Fi, from its own network address.
        var tvHost = route.Usb ? route.HomeHost : route.Host;
        if (tvHost is null) return "Connect the TV to Wi-Fi to send files from this PC.";
        try
        {
            media.Start();
        }
        catch (Exception e)
        {
            Log.Error("media server", e);
            return "Couldn't share the file with the TV.";
        }
        var url = media.UrlFor(media.Share(playing.Path), tvHost);
        var opened = await model.SendAsync(url, playing.Position).ConfigureAwait(true);
        if (opened is null) return TakeError() ?? "The TV couldn't play it.";
        _ = MediaSender.PauseAsync(playing.Session);
        return $"{Path.GetFileName(playing.Path)} → {Describe(opened)}";
    }

    private static bool IsPlaying(MediaSender.Playing p)
    {
        try
        {
            return p.Session?.GetPlaybackInfo().PlaybackStatus ==
                   global::Windows.Media.Control.GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
        }
        catch (Exception)
        {
            return false;
        }
    }

    private string? TakeError()
    {
        var e = model.Error;
        model.Error = null;
        return e;
    }

    private static string Describe(string opened) => opened == "Player" ? "TV" : opened;

    public void Dispose() => media.Dispose();
}
