using Microsoft.Win32;

namespace OpenTV.Windows;

/// <summary>"Start with Windows": the per-user Run key (no administrator rights, no scheduled task).</summary>
internal static class StartupRegistration
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string Name = "OpenTV";

    public static bool Enabled
    {
        get
        {
            using var k = Registry.CurrentUser.OpenSubKey(RunKey);
            return k?.GetValue(Name) is string;
        }
        set
        {
            using var k = Registry.CurrentUser.CreateSubKey(RunKey, writable: true);
            if (value) k.SetValue(Name, $"\"{Environment.ProcessPath}\" --background");
            else k.DeleteValue(Name, throwOnMissingValue: false);
        }
    }

    /// <summary>After the app has been moved: point an existing entry at where it runs from now.</summary>
    public static void Refresh()
    {
        if (Enabled) Enabled = true;
    }
}
