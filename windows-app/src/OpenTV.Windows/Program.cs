using System.Windows.Forms;
using OpenTV.Windows.App;
using OpenTV.Windows.Pointer;

namespace OpenTV.Windows;

/// <summary>
/// OpenTV for Windows. Runs once per user in the notification area; opening it again shows the remote.
/// <c>--background</c> (used by Start with Windows) starts it without showing the remote.
/// </summary>
internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        using var instance = SingleInstance.Claim();
        if (instance is null) return 0;   // already running: it shows its remote

        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (_, e) => Log.Error("unhandled (UI)", e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
        {
            if (e.ExceptionObject is Exception ex) Log.Error("unhandled", ex);
            // Never leave the user without a cursor.
            CursorHider.Show();
        };
        TaskScheduler.UnobservedTaskException += (_, e) =>
        {
            Log.Error("unobserved task", e.Exception);
            e.SetObserved();
        };

        // The last run ended with the cursor on the TV (a crash, a power cut): give the user their cursors back.
        CursorHider.RestoreIfLeftHidden();

        var background = args.Any(a => string.Equals(a, "--background", StringComparison.OrdinalIgnoreCase));
        using var context = new TrayApp(showRemote: !background, instance);
        Application.Run(context);
        return 0;
    }
}
