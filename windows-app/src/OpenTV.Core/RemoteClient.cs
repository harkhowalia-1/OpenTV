using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace OpenTV.Core;

/// <summary>What <c>GET /api/hello</c> says about the TV.</summary>
public sealed record TvHello(string Name, string? Id, bool Paired, int ControlPort, int DisplayPort);

/// <summary>
/// The TV's small JSON API on port 8800. Every call except hello and pairing carries this PC's token in
/// <c>X-Token</c>; the token comes from typing the 4-digit code the TV shows.
/// </summary>
public sealed class RemoteClient(TvDevice tv, string? token)
{
    private static readonly HttpClient Http = new(new SocketsHttpHandler
    {
        ConnectTimeout = TimeSpan.FromSeconds(3),
        PooledConnectionLifetime = TimeSpan.FromSeconds(30),
        UseProxy = false,
    })
    {
        Timeout = TimeSpan.FromSeconds(6),
    };

    public TvDevice Tv { get; } = tv;

    private async Task<JsonObject> CallAsync(string path, HttpMethod method, object? body = null, CancellationToken ct = default)
    {
        using var req = new HttpRequestMessage(method, $"http://{Tv.Host}:{Tv.Port}/api/{path}");
        if (token is not null) req.Headers.TryAddWithoutValidation("X-Token", token);
        if (body is not null)
        {
            req.Content = new StringContent(JsonSerializer.Serialize(body), Encoding.UTF8);
            req.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
        }
        else if (method == HttpMethod.Post)
        {
            req.Content = new ByteArrayContent([]);
        }
        HttpResponseMessage resp;
        try
        {
            resp = await Http.SendAsync(req, ct).ConfigureAwait(false);
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException or OperationCanceledException)
        {
            if (ct.IsCancellationRequested) throw;
            throw new RemoteException(RemoteErrorKind.Unreachable, e.Message);
        }
        using (resp)
        {
            if (resp.StatusCode == HttpStatusCode.Unauthorized) throw new RemoteException(RemoteErrorKind.NotPaired);
            if (resp.StatusCode == HttpStatusCode.Forbidden) throw new RemoteException(RemoteErrorKind.WrongCode);
            var text = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            try
            {
                return JsonNode.Parse(text) as JsonObject ?? new JsonObject();
            }
            catch (JsonException)
            {
                return new JsonObject();
            }
        }
    }

    /// <summary>The TV's name, its stable ID, whether this PC is paired, and its other ports.</summary>
    public async Task<TvHello> HelloAsync(CancellationToken ct = default)
    {
        var o = await CallAsync("hello", HttpMethod.Get, ct: ct).ConfigureAwait(false);
        return new TvHello(
            Str(o, "name") ?? Tv.Name,
            Str(o, "id"),
            Bool(o, "paired"),
            Int(o, "control") ?? Tv.ControlPort,
            Int(o, "display") ?? Tv.DisplayPort);
    }

    /// <summary>Puts a 4-digit code on the TV screen (it stays valid for two minutes).</summary>
    public Task StartPairingAsync(CancellationToken ct = default) => CallAsync("pair/start", HttpMethod.Post, ct: ct);

    /// <summary>Sends the code shown on the TV; returns this PC's new token.</summary>
    public async Task<string> FinishPairingAsync(string code, CancellationToken ct = default)
    {
        var o = await CallAsync("pair/finish", HttpMethod.Post, new { code }, ct).ConfigureAwait(false);
        return Str(o, "token") ?? throw new RemoteException(RemoteErrorKind.WrongCode);
    }

    public Task PressAsync(RemoteKey key, CancellationToken ct = default) =>
        CallAsync("key/" + key.Wire(), HttpMethod.Post, ct: ct);

    /// <summary>Replaces the text of the TV's focused text field.</summary>
    public Task TextAsync(string text, CancellationToken ct = default) =>
        CallAsync("text", HttpMethod.Post, new { text }, ct);

    public async Task<NowPlaying?> StateAsync(CancellationToken ct = default)
    {
        var o = await CallAsync("state", HttpMethod.Get, ct: ct).ConfigureAwait(false);
        return ParseNowPlaying(o);
    }

    /// <summary>
    /// "Send to TV": opens a link (YouTube, Netflix, Spotify, a video file, a web page) on the TV at
    /// <paramref name="time"/> seconds when known. Returns what opened it ("YouTube", "Player", ...) or null.
    /// </summary>
    public async Task<string?> OpenAsync(string url, double? time = null, CancellationToken ct = default)
    {
        object body = time is double t ? new { url, time = t } : new { url };
        var o = await CallAsync("open", HttpMethod.Post, body, ct).ConfigureAwait(false);
        return Str(o, "opened");
    }

    internal static NowPlaying? ParseNowPlaying(JsonObject? o)
    {
        var title = o is null ? null : Str(o, "title");
        if (string.IsNullOrEmpty(title)) return null;
        return new NowPlaying(title, Str(o!, "artist") ?? "", Bool(o!, "playing"));
    }

    private static string? Str(JsonObject o, string key) =>
        o.TryGetPropertyValue(key, out var v) && v is JsonValue jv && jv.TryGetValue<string>(out var s) ? s : null;

    private static bool Bool(JsonObject o, string key) =>
        o.TryGetPropertyValue(key, out var v) && v is JsonValue jv && jv.TryGetValue<bool>(out var b) && b;

    private static int? Int(JsonObject o, string key) =>
        o.TryGetPropertyValue(key, out var v) && v is JsonValue jv && jv.TryGetValue<int>(out var i) ? i : null;
}
