import AppIntents

/// Siri phrases, e.g. "Pause the TV with Otv Remote".
struct TVRemoteShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: PressTVButtonIntent(), phrases: [
            "Press \(\.$button) on the TV with \(.applicationName)",
            "\(\.$button) the TV with \(.applicationName)",
            "Use \(.applicationName) to press \(\.$button)",
        ], shortTitle: "Press TV Button", systemImageName: "appletvremote.gen4.fill")
        AppShortcut(intent: OpenRemoteIntent(), phrases: [
            "Open \(.applicationName)",
            "Show the \(.applicationName)",
        ], shortTitle: "Open Remote", systemImageName: "tv")
    }
}
