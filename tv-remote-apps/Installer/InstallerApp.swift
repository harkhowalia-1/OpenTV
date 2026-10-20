import SwiftUI

/// OpenTV Installer: sets up OpenTV on an Android TV (and OpenTV Companion on this Mac), one page at a time.
@main
struct InstallerApp: App {
    @StateObject private var setup = Setup()

    var body: some Scene {
        Window("OpenTV Installer", id: "installer") {
            InstallerView(setup: setup)
                .frame(width: 760, height: 520)
        }
        .windowResizability(.contentSize)
        .windowStyle(.hiddenTitleBar)
    }
}

struct InstallerView: View {
    @ObservedObject var setup: Setup

    var body: some View {
        HStack(spacing: 0) {
            sidebar
            Divider()
            VStack(spacing: 0) {
                ScrollView { page.padding(28).frame(maxWidth: .infinity, alignment: .leading) }
                Divider()
                buttons.padding(.horizontal, 20).padding(.vertical, 14)
            }
        }
        .task { await setup.checkForUpdate() }
    }

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 14) {
            Image(nsImage: NSApp.applicationIconImage).resizable().frame(width: 84, height: 84)
                .padding(.top, 30).padding(.bottom, 10).frame(maxWidth: .infinity)
            ForEach(Setup.Step.allCases, id: \.self) { s in
                HStack(spacing: 10) {
                    Circle()
                        .fill(s == setup.step ? Color.accentColor : s.rawValue < setup.step.rawValue ? Color.secondary : Color.secondary.opacity(0.3))
                        .frame(width: 9, height: 9)
                    Text(s.title).font(.system(size: 13, weight: s == setup.step ? .semibold : .regular))
                        .foregroundStyle(s == setup.step ? .primary : .secondary)
                }
            }
            Spacer()
        }
        .padding(.horizontal, 22)
        .frame(width: 210)
        .background(.regularMaterial)
    }

    @ViewBuilder
    private var page: some View {
        switch setup.step {
        case .intro: IntroPage(setup: setup)
        case .connect: ConnectPage(setup: setup)
        case .components: ComponentsPage(setup: setup)
        case .install: InstallPage(setup: setup)
        case .done: DonePage(setup: setup)
        }
    }

    private var buttons: some View {
        HStack {
            if setup.step == .intro {
                Button("Restore Original TV…") { setup.restoring = true; setup.step = .connect }
                    .help("Turns the TV's own home screen back on and removes OpenTV")
            }
            Spacer()
            if setup.step != .intro && setup.step != .install && setup.step != .done {
                Button("Go Back") { setup.step = Setup.Step(rawValue: setup.step.rawValue - 1) ?? .intro }
            }
            switch setup.step {
            case .intro:
                Button("Continue") { setup.restoring = false; setup.step = .connect }.keyboardShortcut(.defaultAction)
            case .connect:
                if setup.restoring {
                    Button("Restore") { Task { await setup.restore() } }.keyboardShortcut(.defaultAction).disabled(setup.device == nil)
                } else {
                    Button("Continue") { setup.step = .components }.keyboardShortcut(.defaultAction).disabled(setup.device == nil)
                }
            case .components:
                Button("Install") { Task { await setup.install() } }.keyboardShortcut(.defaultAction)
            case .install:
                Button(setup.failed && setup.finished ? "Try Again" : "Installing…") { Task { await setup.install() } }
                    .disabled(!(setup.failed && setup.finished))
            case .done:
                Button("Close") { NSApp.terminate(nil) }.keyboardShortcut(.defaultAction)
            }
        }
    }
}

private struct IntroPage: View {
    @ObservedObject var setup: Setup
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Welcome to OpenTV").font(.largeTitle.bold())
            Text("OpenTV turns an Android TV or Google TV into something that looks and works like an Apple TV, with an iPhone remote and a Mac that works with it.")
                .font(.title3).foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 10) {
                Feature(icon: "tv", title: "OtvOS", text: "A tvOS-style home screen, Control Center, profiles, Apple Home, and the TV as a display for your Mac.")
                Feature(icon: "antenna.radiowaves.left.and.right", title: "IPTV", text: "Free live channels from around the world, with favourites.")
                Feature(icon: "airplayvideo", title: "AirPlay", text: "Play from your iPhone, iPad or Mac.")
                Feature(icon: "menubar.rectangle", title: "OpenTV Companion", text: "Remote, TV as Display, Send to TV and the TV Manager, in this Mac's menu bar.")
            }
            .padding(.top, 6)
            Text("You need the TV and this Mac on the same network, or a USB-C cable. Nothing on the TV is deleted; the installer can put it back the way it was.")
                .font(.callout).foregroundStyle(.secondary).padding(.top, 6)
            if let v = setup.newerInstaller {
                Label("A newer installer (\(v)) is available.", systemImage: "arrow.down.circle")
                    .font(.callout)
                Link("Download it", destination: URL(string: "https://github.com/\(Setup.repo)/releases/latest")!)
            }
        }
    }
}

private struct Feature: View {
    let icon: String, title: String, text: String
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon).font(.title2).frame(width: 30).foregroundStyle(Color.accentColor)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(text).foregroundStyle(.secondary)
            }
        }
    }
}

private struct ConnectPage: View {
    @ObservedObject var setup: Setup
    @State private var address = ""
    @State private var code = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(setup.restoring ? "Choose the TV to restore" : "Connect your TV").font(.title.bold())
            GroupBox {
                VStack(alignment: .leading, spacing: 8) {
                    Text("1.  On the TV: Settings › System › About, press Android TV OS build 7 times.")
                    Text("2.  Settings › System › Developer options: turn on USB debugging, and Wireless debugging (Android 11 and later) or Network debugging (older TVs).")
                    Text("3.  Plug the TV into this Mac with USB-C, or keep both on the same Wi-Fi, then press Look Again. Allow the connection on the TV if it asks.")
                }
                .font(.callout).fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading).padding(4)
            }

            HStack {
                Text("TVs found").font(.headline)
                Spacer()
                if setup.scanning { ProgressView().controlSize(.small) }
                Button("Look Again") { Task { await setup.scan() } }
            }
            if setup.devices.isEmpty {
                Text(setup.connectMessage.isEmpty ? "Looking…" : setup.connectMessage).foregroundStyle(.secondary)
            } else {
                ForEach(setup.devices) { d in
                    HStack {
                        Image(systemName: setup.device?.id == d.id ? "largecircle.fill.circle" : "circle")
                            .foregroundStyle(Color.accentColor)
                        Image(systemName: "tv").font(.title2)
                        VStack(alignment: .leading) {
                            Text(d.title).font(.body.weight(.medium))
                            Text(d.detail).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { setup.device = d }
                }
            }

            if setup.adb.pairingAvailable {
                HStack {
                    Text("Wireless debugging pairing code:")
                    TextField("123456", text: $code).frame(width: 90)
                    Button("Pair") { Task { await setup.pair(code: code) } }.disabled(code.count < 6)
                }
            }
            HStack {
                Text("Or the TV's address:")
                TextField("192.168.1.50", text: $address).frame(width: 150)
                Button("Connect") { Task { await setup.connect(address: address) } }.disabled(address.isEmpty)
            }
            .font(.callout)
            if !setup.connectMessage.isEmpty && !setup.devices.isEmpty {
                Text(setup.connectMessage).font(.caption).foregroundStyle(.secondary)
            }
        }
        .task { await setup.scan() }
    }
}

private struct ComponentsPage: View {
    @ObservedObject var setup: Setup
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("What to install").font(.title.bold())
            Text("On \(setup.device?.title ?? "the TV"). The newest version of everything is downloaded.")
                .foregroundStyle(.secondary)
            Toggle(isOn: .constant(true)) { Choice(title: "OtvOS", text: "The home screen and everything that makes the TV work like an Apple TV. Required.") }
                .disabled(true)
            Toggle(isOn: $setup.installIPTV) { Choice(title: "IPTV", text: "Free live TV channels from every country.") }
            Toggle(isOn: $setup.installAirPlay) { Choice(title: "AirPlay receiver", text: "Play and mirror from Apple devices (open-source UxPlay, from F-Droid).") }
            Toggle(isOn: $setup.speedUp) { Choice(title: "Speed up the TV", text: "Turns off Google TV extras that run in the background: feedback, logging, the stock screensaver, Play Games, TalkBack, Calendar sync, Settings suggestions, remote firmware updates, and the Play Store's background updates (it still opens). Frees about 100 MB. Restore turns them back on.") }
            Toggle(isOn: $setup.installCompanion) { Choice(title: "OpenTV Companion for this Mac", text: "Goes in Applications and the menu bar.") }
        }
        .toggleStyle(.checkbox)
    }
}

private struct Choice: View {
    let title: String, text: String
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.headline)
            Text(text).font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
        .padding(.leading, 4)
    }
}

private struct InstallPage: View {
    @ObservedObject var setup: Setup
    @State private var showLog = false
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(setup.restoring ? "Restoring the TV" : "Installing OpenTV").font(.title.bold())
            ForEach(setup.jobs) { j in
                HStack(spacing: 10) {
                    Group {
                        switch j.state {
                        case .waiting: Image(systemName: "circle").foregroundStyle(.tertiary)
                        case .running: ProgressView().controlSize(.small)
                        case .done: Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                        case .skipped: Image(systemName: "minus.circle").foregroundStyle(.secondary)
                        case .failed: Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                        }
                    }
                    .frame(width: 18)
                    VStack(alignment: .leading) {
                        Text(j.title)
                        if case .failed(let m) = j.state { Text(m).font(.caption).foregroundStyle(.secondary).lineLimit(3) }
                    }
                }
            }
            DisclosureGroup("Details", isExpanded: $showLog) {
                ScrollView {
                    Text(setup.log).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(height: 140)
            }
        }
    }
}

private struct DonePage: View {
    @ObservedObject var setup: Setup
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Image(systemName: "checkmark.seal.fill").font(.system(size: 48)).foregroundStyle(.green)
            Text(setup.restoring ? "Your TV is back to normal" : "OpenTV is ready").font(.largeTitle.bold())
            if setup.restoring {
                Text("The TV's own home screen is back, and OpenTV has been removed.").foregroundStyle(.secondary)
            } else {
                Text("Press Home on the TV remote to see OtvOS.").font(.title3)
                Text("To use your iPhone as the remote, get Otv Remote and pick the TV. OpenTV Companion is in this Mac's menu bar: click it and pair with the code the TV shows.")
                    .foregroundStyle(.secondary)
                Text("Run this installer again any time to update to the newest version.").foregroundStyle(.secondary)
            }
        }
    }
}
