using System.Text.Json.Serialization;

namespace OpenTV.Core;

/// <summary>
/// A TV running OtvOS: HTTP API (8800), live control channel (8801) and display stream (8802). Over USB the
/// same servers are reached through adb's forwarded local ports.
/// </summary>
public sealed record TvDevice
{
    public string Name { get; init; } = "TV";
    public string Host { get; init; } = "";
    public int Port { get; init; } = 8800;
    public int ControlPort { get; init; } = 8801;
    public int DisplayPort { get; init; } = 8802;

    /// <summary>The TV's own stable ID (from mDNS or /api/hello); pairing is kept per TV, not per address.</summary>
    public string? TvId { get; init; }

    public string? Model { get; init; }
    public bool Usb { get; init; }

    /// <summary>For a USB route: the TV's Wi-Fi address, which older pairings are stored under.</summary>
    public string? HomeHost { get; init; }

    [JsonIgnore] public string Endpoint => $"{Host}:{Port}";

    /// <summary>What pairing is stored under: the TV itself, whichever way it's reached.</summary>
    [JsonIgnore] public string Key => TvId ?? HomeHost ?? Host;

    /// <summary>Parses "192.168.2.24", "192.168.2.24:8800" or "http://192.168.2.24:8800/".</summary>
    public static TvDevice? FromAddress(string text)
    {
        var clean = text.Trim();
        if (clean.StartsWith("http://", StringComparison.OrdinalIgnoreCase)) clean = clean[7..];
        clean = clean.Split('/')[0];
        if (clean.Length == 0) return null;
        var parts = clean.Split(':');
        var port = parts.Length > 1 && int.TryParse(parts[1], out var p) ? p : 8800;
        return new TvDevice { Name = "TV", Host = parts[0], Port = port };
    }
}

/// <summary>The buttons the TV understands (<c>/api/key/&lt;name&gt;</c> and the control channel's KEY).</summary>
public enum RemoteKey
{
    Up, Down, Left, Right, Select, Back, Home, Control,
    PlayPause, Next, Previous, VolUp, VolDown, Mute, Power,
}

public static class RemoteKeys
{
    /// <summary>The name on the wire: "up", "playpause", "volup", ...</summary>
    public static string Wire(this RemoteKey key) => key.ToString().ToLowerInvariant();

    public static RemoteKey? Parse(string name) =>
        Enum.TryParse<RemoteKey>(name, ignoreCase: true, out var k) ? k : null;
}

public sealed record NowPlaying(string Title, string Artist, bool Playing);

public enum RemoteErrorKind { NotPaired, WrongCode, Unreachable }

public sealed class RemoteException(RemoteErrorKind kind, string? detail = null) : Exception(Describe(kind, detail))
{
    public RemoteErrorKind Kind { get; } = kind;

    private static string Describe(RemoteErrorKind kind, string? detail) => kind switch
    {
        RemoteErrorKind.NotPaired => "This PC isn't connected to the TV yet.",
        RemoteErrorKind.WrongCode => "That code didn't match. Try again.",
        _ => "Can't reach the TV. Make sure it's on and on the same network." + (detail is null ? "" : $" ({detail})"),
    };
}
