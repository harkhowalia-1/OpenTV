namespace OpenTV.Windows;

/// <summary>A small log in %LOCALAPPDATA%\OpenTV\log.txt (kept under 1 MB) for "why didn't it work" questions.</summary>
internal static class Log
{
    private static readonly object Gate = new();

    public static string Folder { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "OpenTV");

    public static string FilePath { get; } = Path.Combine(Folder, "log.txt");

    public static void Write(string message)
    {
        lock (Gate)
        {
            try
            {
                Directory.CreateDirectory(Folder);
                var f = new FileInfo(FilePath);
                if (f.Exists && f.Length > 1_000_000) File.Move(FilePath, FilePath + ".old", overwrite: true);
                File.AppendAllText(FilePath, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} {message}{Environment.NewLine}");
            }
            catch (Exception)
            {
            }
        }
    }

    public static void Error(string what, Exception e) => Write($"{what}: {e.GetType().Name}: {e.Message}");
}
