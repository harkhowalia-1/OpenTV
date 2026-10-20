import SwiftUI

/// Otv Remote for iPhone: the remote fills the screen, and the iPhone's volume buttons change the TV's volume
/// while it is open, as with Apple's remote.
@main
struct TVRemoteApp: App {
    @StateObject private var model = RemoteModel()
    @StateObject private var volume = VolumeButtons()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ZStack {
                Color.black.ignoresSafeArea()
                RemoteView(model: model)
                    .frame(maxWidth: 440)
                VolumeButtons.HiddenSlider()
                    .frame(width: 1, height: 1)
                    .opacity(0.01)
                    .allowsHitTesting(false)
            }
            .preferredColorScheme(.dark)
            .onAppear { volume.onPress = { model.press($0) } }
            .onOpenURL { _ in model.activate() }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                model.activate()
                volume.start(enabled: model.phase == .connected || model.phase == .offline)
            } else {
                model.deactivate()
                volume.stop()
            }
        }
        .onChange(of: model.phase) { _, phase in
            volume.start(enabled: phase == .connected || phase == .offline)
        }
    }
}
