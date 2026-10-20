import AppIntents

/// TV buttons for Control Center, Siri and Shortcuts.
enum TVButton: String, AppEnum {
    case playPause, power, volumeUp, volumeDown, mute, home, back, controlCenter, next, previous

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "TV Button"
    static let caseDisplayRepresentations: [TVButton: DisplayRepresentation] = [
        .playPause: "Play/Pause",
        .power: "Power",
        .volumeUp: "Volume Up",
        .volumeDown: "Volume Down",
        .mute: "Mute",
        .home: "Home",
        .back: "Back",
        .controlCenter: "Control Center",
        .next: "Next",
        .previous: "Previous",
    ]

    var key: RemoteKey {
        switch self {
        case .playPause: return .playpause
        case .power: return .power
        case .volumeUp: return .volup
        case .volumeDown: return .voldown
        case .mute: return .mute
        case .home: return .home
        case .back: return .back
        case .controlCenter: return .control
        case .next: return .next
        case .previous: return .previous
        }
    }
}

/// Presses a button on the TV without opening the app.
struct PressTVButtonIntent: AppIntent {
    static let title: LocalizedStringResource = "Press TV Button"
    static let description = IntentDescription("Presses a button on your TV's remote.")

    @Parameter(title: "Button", default: .playPause)
    var button: TVButton

    init() {}

    init(_ button: TVButton) {
        self.button = button
    }

    static var parameterSummary: some ParameterSummary {
        Summary("Press \(\.$button) on the TV")
    }

    func perform() async throws -> some IntentResult {
        guard let tv = TVStore.tv else {
            throw RemoteError.notPaired
        }
        try await RemoteClient(tv: tv).press(button.key)
        return .result()
    }
}

/// Opens the remote.
struct OpenRemoteIntent: AppIntent {
    static let title: LocalizedStringResource = "Open the Remote"
    static let description = IntentDescription("Opens the remote for your TV.")
    static let openAppWhenRun = true

    func perform() async throws -> some IntentResult {
        .result()
    }
}
