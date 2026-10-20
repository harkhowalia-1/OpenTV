using OpenTV.Windows.Native;

namespace OpenTV.Windows.Pointer;

/// <summary>
/// Hides the Windows cursor everywhere while it's on the TV. Windows has no "hide the cursor for every app"
/// call, so each system cursor shape is swapped for a blank one, and the user's own cursors are reloaded
/// (SPI_SETCURSORS) when it comes back. A marker file covers a crash in between: the next launch restores them.
/// </summary>
internal static class CursorHider
{
    // OCR_NORMAL, IBEAM, WAIT, CROSS, UP, SIZENWSE, SIZENESW, SIZEWE, SIZENS, SIZEALL, NO, HAND, APPSTARTING, HELP
    private static readonly uint[] Ids = [32512, 32513, 32514, 32515, 32516, 32642, 32643, 32644, 32645, 32646, 32648, 32649, 32650, 32651];
    private static readonly string Marker = Path.Combine(Log.Folder, "cursor-hidden");
    private static volatile bool hidden;

    public static bool Hidden => hidden;

    public static void Hide()
    {
        if (hidden) return;
        hidden = true;
        try
        {
            Directory.CreateDirectory(Log.Folder);
            File.WriteAllText(Marker, "");
        }
        catch (Exception)
        {
        }
        var and = Enumerable.Repeat((byte)0xFF, 32 * 32 / 8).ToArray();
        var xor = new byte[32 * 32 / 8];
        foreach (var id in Ids)
        {
            // SetSystemCursor takes ownership of (and destroys) the cursor it's given: one blank cursor per shape.
            var blank = Win32.CreateCursor(IntPtr.Zero, 0, 0, 32, 32, and, xor);
            if (blank != IntPtr.Zero) Win32.SetSystemCursor(blank, id);
        }
    }

    public static void Show()
    {
        if (!hidden) return;
        hidden = false;
        Win32.SystemParametersInfoW(Win32.SPI_SETCURSORS, 0, IntPtr.Zero, 0);
        try
        {
            File.Delete(Marker);
        }
        catch (Exception)
        {
        }
    }

    /// <summary>At launch: if the last run ended with the cursor hidden, bring the user's cursors back.</summary>
    public static void RestoreIfLeftHidden()
    {
        if (!File.Exists(Marker)) return;
        hidden = true;
        Show();
    }
}
