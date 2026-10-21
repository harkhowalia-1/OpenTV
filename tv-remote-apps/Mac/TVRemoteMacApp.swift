import ServiceManagement
import SwiftUI

/// OpenTV Companion for the Mac: lives in the menu bar and stays connected in the background, so the cursor can
/// cross onto the TV at any time. Click the remote icon for the remote, "TV as Display" and Send Tab to TV;
/// while it is open the arrow keys, Return, Esc and Space work like the remote's buttons.
@main
struct TVRemoteMacApp: App {
    // One of each for the life of the app. SwiftUI may create this App value more than once, so nothing
    // that holds a connection is created here.
    @ObservedObject private var model = Services.model
    @ObservedObject private var display = Services.display
    @ObservedObject private var bridge = PointerBridge.shared

    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        Services.start()
    }

    var body: some Scene {
        MenuBarExtra {
            MenuRemote(model: model, display: display, bridge: bridge)
        } label: {
            Image(nsImage: TrayIcon.image(showing: display.running))
        }
        .menuBarExtraStyle(.window)
    }
}

/// The app's long-lived pieces, started once at launch.
@MainActor
enum Services {
    static let model = RemoteModel()
    static let display = DisplayStreamer(model: model)
    static let usb = USBLink(model: model)
    private static var started = false

    static func start() {
        guard !started else { return }
        started = true
        // Stay connected from launch: the cursor bridge and the TV's pushes need the live channel.
        model.activate()
        usb.start()
        let bridge = PointerBridge.shared
        bridge.channel = model.channel
        model.channel.onPointerLeft = { along in bridge.tvReleased(at: along) }
        // A dropped connection (TV restarted, asleep) means the TV no longer has the cursor.
        model.channel.onDisconnected = { bridge.connectionLost() }
        bridge.start()
        TabSender.startWatching()
        ADB.shared.start()

    }
}

struct MenuRemote: View {
    @ObservedObject var model: RemoteModel
    @ObservedObject var display: DisplayStreamer
    @ObservedObject var bridge: PointerBridge
    @State private var launchAtLogin = SMAppService.mainApp.status == .enabled
    @State private var sendStatus = ""
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 0) {
            RemoteView(model: model, compact: true)
                .frame(minHeight: 380)
                .fixedSize(horizontal: false, vertical: true)
            if model.phase == .connected || model.phase == .offline {
                Divider().overlay(Color.white.opacity(0.08))
                extras
            }
            Divider().overlay(Color.white.opacity(0.08))
            footer
        }
        .frame(width: 300)
        .fixedSize(horizontal: false, vertical: true)
        .background(Color(white: 0.07))
        .environment(\.colorScheme, .dark)
        .focusable()
        .focusEffectDisabled()
        .focused($focused)
        .onKeyPress(phases: .down) { press in keyDown(press) }
        .onAppear {
            model.activate()
            focused = true
        }
    }

    /// TV as Display, the cursor bridge and Send Tab to TV.
    private var extras: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("TV as Display", systemImage: "rectangle.on.rectangle")
                    .font(.system(size: 13, weight: .semibold))
                Spacer()
                if model.route?.usb == true {
                    Label("USB", systemImage: "cable.connector")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.secondary)
                        .help("The Chromecast is plugged into this Mac: everything goes over the cable.")
                }
            }
            HStack {
                Picker("", selection: $display.mode) {
                    ForEach(DisplayStreamer.Mode.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .disabled(display.running)
                Spacer()
                Button(display.running ? "Stop" : "Start") { display.toggle() }
                    .keyboardShortcut("d", modifiers: [.command])
            }
            Toggle("Sound on TV", isOn: $display.sound)
                .toggleStyle(.checkbox)
                .font(.system(size: 12))
                .disabled(display.running)
            if !display.status.isEmpty {
                Text(display.status).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(2)
            }

            Toggle(isOn: $bridge.enabled) {
                VStack(alignment: .leading, spacing: 1) {
                    Text("Move the pointer onto the TV").font(.system(size: 12))
                    Text(bridge.needsPermission ? "Allow OpenTV Companion in Privacy & Security > Accessibility."
                         : "Push past the top of the screen. On the TV: push into the left edge for Back, the top edge for Control Center; two-finger click is Home. ⌘Esc comes back.")
                        .font(.system(size: 11)).foregroundStyle(.secondary)
                }
            }
            .toggleStyle(.checkbox)

            HStack {
                Button { ManagerWindow.show() } label: { Label("Manage TV…", systemImage: "slider.horizontal.3") }
                    .controlSize(.small)
            }

            HStack {
                Button {
                    sendTab()
                } label: {
                    Label("Send to TV", systemImage: "arrow.up.forward.app")
                }
                .help("Sends the open browser tab (any browser), or the video playing in QuickTime or VLC, to the TV.")
                .controlSize(.small)
                .keyboardShortcut("t", modifiers: [.command])
                Text(sendStatus).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private var footer: some View {
        HStack {
            Toggle("Open at Login", isOn: $launchAtLogin)
                .toggleStyle(.checkbox)
                .font(.system(size: 12))
                .onChange(of: launchAtLogin) { _, on in
                    do {
                        if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
                    } catch {
                        launchAtLogin = SMAppService.mainApp.status == .enabled
                    }
                }
            Spacer()
            Image(systemName: "keyboard")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .help("Arrow keys move, Return selects, Esc goes back, Space plays or pauses, H goes Home, C opens Control Center")
            Spacer()
            Button("Quit") { NSApp.terminate(nil) }
                .buttonStyle(.plain)
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .keyboardShortcut("q")
        }
        .padding(.horizontal, 16)
        .frame(height: 38)
    }

    private func sendTab() {
        guard let item = TabSender.current() else {
            sendStatus = "Nothing open in a browser or player."
            return
        }
        sendStatus = "Sending from \(item.source)…"
        Task {
            let opened = await model.send(item.url, time: item.time)
            sendStatus = opened.map { "\(item.source) → \($0 == "Player" ? "TV" : $0)" } ?? "The TV couldn't open it."
        }
    }

    /// Keyboard shortcuts, only once paired (so typing the pairing code still works).
    private func keyDown(_ press: KeyPress) -> KeyPress.Result {
        guard model.phase == .connected || model.phase == .offline else { return .ignored }
        let key: RemoteKey?
        switch press.key {
        case .upArrow: key = .up
        case .downArrow: key = .down
        case .leftArrow: key = .left
        case .rightArrow: key = .right
        case .return: key = .select
        case .escape, .delete: key = .back
        case .space: key = .playpause
        default:
            switch press.characters {
            case "h": key = .home
            case "c": key = .control
            case "m": key = .mute
            case "=", "+": key = .volup
            case "-": key = .voldown
            default: key = nil
            }
        }
        guard let key else { return .ignored }
        model.press(key)
        return .handled
    }
}

enum ManagerWindow {
    @MainActor
    static func show() {
        _ = Windows.show("manager", title: "TV Manager", size: NSSize(width: 860, height: 600)) { ManagerView() }
    }
}

/// One app: clicking OpenTV Companion in the Dock or opening it again from Finder or Launchpad shows the TV Manager.
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        MainActor.assumeIsolated { ManagerWindow.show() }
        return true
    }
}
