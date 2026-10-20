using System.Text.Json;
using System.Text.Json.Serialization;

namespace OpenTV.Core;

/// <summary>What the app remembers between launches.</summary>
public sealed class Settings
{
    public TvDevice? Tv { get; set; }

    /// <summary>Pairing tokens by <see cref="TvDevice.Key"/> (the TV's ID, or its address for old pairings).</summary>
    public Dictionary<string, string> Tokens { get; set; } = new();

    public bool PointerBridge { get; set; } = true;
    public bool SoundOnTv { get; set; } = true;

    /// <summary>The monitor to mirror (its device name, e.g. "\\.\DISPLAY1"); null for the primary one.</summary>
    public string? MirrorMonitor { get; set; }
}

/// <summary>
/// The chosen TV and this PC's pairing tokens, kept in a JSON file (on Windows %APPDATA%\OpenTV\settings.json).
/// Tokens can be wrapped by the host (DPAPI on Windows) through <see cref="Protect"/> and <see cref="Unprotect"/>.
/// </summary>
public sealed class TvStore
{
    private static readonly JsonSerializerOptions Json = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    private readonly string? path;
    private readonly object gate = new();

    public Settings Settings { get; }

    public Func<string, string> Protect { get; set; } = s => s;
    public Func<string, string?> Unprotect { get; set; } = s => s;

    /// <summary>A store kept only in memory (tests, or when there's nowhere to write).</summary>
    public TvStore() => Settings = new Settings();

    public TvStore(string path)
    {
        this.path = path;
        Settings = Load(path);
    }

    public static string DefaultPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "OpenTV", "settings.json");

    private static Settings Load(string path)
    {
        try
        {
            if (File.Exists(path)) return JsonSerializer.Deserialize<Settings>(File.ReadAllText(path), Json) ?? new Settings();
        }
        catch (Exception)
        {
            // A damaged file: start over rather than refuse to run.
        }
        return new Settings();
    }

    public void Save()
    {
        if (path is null) return;
        lock (gate)
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                var tmp = path + ".tmp";
                File.WriteAllText(tmp, JsonSerializer.Serialize(Settings, Json));
                File.Move(tmp, path, overwrite: true);
            }
            catch (IOException)
            {
            }
            catch (UnauthorizedAccessException)
            {
            }
        }
    }

    public TvDevice? Tv
    {
        get => Settings.Tv;
        set { Settings.Tv = value; Save(); }
    }

    public string? Token(TvDevice tv)
    {
        lock (gate)
        {
            if (Settings.Tokens.TryGetValue(tv.Key, out var t) ||
                Settings.Tokens.TryGetValue(tv.HomeHost ?? tv.Host, out t))
            {
                return Unprotect(t);
            }
            return null;
        }
    }

    public void SetToken(string? token, TvDevice tv)
    {
        lock (gate)
        {
            if (token is null) Settings.Tokens.Remove(tv.Key);
            else Settings.Tokens[tv.Key] = Protect(token);
            // Once the TV's ID is known, the pairing lives under it alone.
            if (tv.TvId is not null) Settings.Tokens.Remove(tv.HomeHost ?? tv.Host);
        }
        Save();
    }
}
