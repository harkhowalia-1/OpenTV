import AppIntents
import SwiftUI
import WidgetKit

/// Control Center buttons, like the Apple TV Remote control: one opens the full remote, the others press a
/// single button on the TV straight from Control Center, the Lock Screen or the Action button.
@main
struct TVControls: WidgetBundle {
    var body: some Widget {
        RemoteControl()
        ButtonControl<PlayPause>()
        ButtonControl<Power>()
        ButtonControl<VolumeUp>()
        ButtonControl<VolumeDown>()
        ButtonControl<Mute>()
        ButtonControl<Home>()
    }
}

struct RemoteControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "com.harkhowalia.tvremote.controls.remote") {
            ControlWidgetButton(action: OpenRemoteIntent()) {
                Label("Otv Remote", systemImage: "appletvremote.gen4.fill")
            }
        }
        .displayName("Otv Remote")
        .description("Opens the remote for your TV.")
    }
}

/// One TV button as its own control.
protocol ButtonSpec {
    static var kind: String { get }
    static var title: LocalizedStringResource { get }
    static var symbol: String { get }
    static var button: TVButton { get }
}

struct ButtonControl<Spec: ButtonSpec>: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "com.harkhowalia.tvremote.controls.\(Spec.kind)") {
            ControlWidgetButton(action: PressTVButtonIntent(Spec.button)) {
                Label(Spec.title, systemImage: Spec.symbol)
            }
        }
        .displayName(Spec.title)
        .description("Presses this button on your TV.")
    }
}

enum PlayPause: ButtonSpec {
    static let kind = "playpause", title: LocalizedStringResource = "TV Play/Pause", symbol = "playpause.fill"
    static let button = TVButton.playPause
}

enum Power: ButtonSpec {
    static let kind = "power", title: LocalizedStringResource = "TV Power", symbol = "power"
    static let button = TVButton.power
}

enum VolumeUp: ButtonSpec {
    static let kind = "volup", title: LocalizedStringResource = "TV Volume Up", symbol = "speaker.plus.fill"
    static let button = TVButton.volumeUp
}

enum VolumeDown: ButtonSpec {
    static let kind = "voldown", title: LocalizedStringResource = "TV Volume Down", symbol = "speaker.minus.fill"
    static let button = TVButton.volumeDown
}

enum Mute: ButtonSpec {
    static let kind = "mute", title: LocalizedStringResource = "TV Mute", symbol = "speaker.slash.fill"
    static let button = TVButton.mute
}

enum Home: ButtonSpec {
    static let kind = "home", title: LocalizedStringResource = "TV Home", symbol = "tv"
    static let button = TVButton.home
}
