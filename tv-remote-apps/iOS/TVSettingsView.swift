import PhotosUI
import SwiftUI

/// The TV's settings, from the phone: switch HDMI inputs, choose or upload a wallpaper, connect Bluetooth
/// accessories, set up the screensaver, and update OpenTV. Opened with the gear beside the power button.
struct TVSettingsView: View {
    @ObservedObject var model: RemoteModel
    @Environment(\.dismiss) private var dismiss
    @State private var signingInXbox = false

    var body: some View {
        NavigationStack {
            List {
                if let client {
                    Section {
                        NavigationLink { InputsView(client: client) } label: {
                            Label("Inputs", systemImage: "rectangle.connected.to.line.below")
                        }
                        NavigationLink { WallpapersView(client: client, model: model) } label: {
                            Label("Wallpaper", systemImage: "photo.on.rectangle")
                        }
                        NavigationLink { ScreenSaverView(client: client) } label: {
                            Label("Screen Saver", systemImage: "sparkles.tv")
                        }
                    }
                    Section {
                        Button { signingInXbox = true } label: {
                            Label("Sign In to Xbox Cloud Gaming", systemImage: "gamecontroller")
                        }
                        .sheet(isPresented: $signingInXbox) { XboxSignInView(client: client) }
                    } header: {
                        Text("Xbox Cloud Gaming")
                    } footer: {
                        Text("Sign in here with your saved passwords; the TV signs itself in. Nothing to type on the TV.")
                    }
                    Section {
                        NavigationLink { BluetoothView(client: client) } label: {
                            Label("Bluetooth", systemImage: "wave.3.right")
                        }
                    } footer: {
                        Text("Pair headphones, speakers, game controllers and keyboards with the TV.")
                    }
                    Section {
                        NavigationLink { UpdateView(client: client) } label: {
                            Label("Software Update", systemImage: "arrow.down.circle")
                        }
                    }
                } else {
                    ContentUnavailableView("No TV", systemImage: "tv", description: Text("Connect to a TV first."))
                }
            }
            .navigationTitle(model.tv?.name ?? "TV Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private var client: RemoteClient? { model.route.map { RemoteClient(tv: $0) } }
}

// MARK: - Inputs

private struct InputsView: View {
    let client: RemoteClient
    @State private var inputs: [[String: Any]] = []
    @State private var loaded = false
    @State private var switched: String?

    var body: some View {
        List {
            ForEach(inputs.indices, id: \.self) { i in
                let id = inputs[i]["id"] as? String ?? ""
                Button {
                    Task {
                        try? await client.tv("input", ["id": id])
                        switched = id
                        Haptics.tap()
                    }
                } label: {
                    HStack {
                        Label(inputs[i]["name"] as? String ?? "Input", systemImage: "cable.connector.horizontal")
                        Spacer()
                        if switched == id { Image(systemName: "checkmark").foregroundStyle(.tint) }
                    }
                }
                .foregroundStyle(.primary)
            }
        }
        .overlay {
            if !loaded {
                ProgressView()
            } else if inputs.isEmpty {
                ContentUnavailableView("No Inputs", systemImage: "cable.connector.horizontal",
                                       description: Text("This TV has no HDMI inputs OpenTV can switch to."))
            }
        }
        .navigationTitle("Inputs")
        .task {
            inputs = (try? await client.tv("inputs"))?["inputs"] as? [[String: Any]] ?? []
            loaded = true
        }
    }
}

// MARK: - Wallpaper

private struct WallpaperSection: Identifiable {
    let id: String
    let items: [WallpaperItem]
}

private struct WallpaperItem: Identifiable, Hashable {
    let id: String   // the kind
    let name: String
    let dynamic: Bool
}

private struct WallpapersView: View {
    let client: RemoteClient
    @ObservedObject var model: RemoteModel
    @State private var sections: [WallpaperSection] = []
    @State private var current = ""
    @State private var setting: String?
    @State private var fraction: Double = -1
    @State private var downloading = false
    @State private var failed: String?
    @State private var picking = false
    @State private var photo: PhotosPickerItem?

    private let columns = [GridItem(.adaptive(minimum: 150), spacing: 12)]

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 22) {
                ForEach(sections) { section in
                    VStack(alignment: .leading, spacing: 10) {
                        if !section.id.isEmpty {
                            Text(section.id).font(.title3.weight(.semibold)).padding(.horizontal)
                        }
                        LazyVGrid(columns: columns, spacing: 14) {
                            ForEach(section.items) { item in
                                WallpaperTile(client: client, item: item, current: item.id == current,
                                              progress: setting == item.id ? fraction : nil) {
                                    choose(item)
                                }
                            }
                        }
                        .padding(.horizontal)
                    }
                }
            }
            .padding(.vertical)
        }
        .overlay {
            if sections.isEmpty { ProgressView("Loading wallpapers…") }
        }
        .safeAreaInset(edge: .bottom) {
            if let status {
                Text(status)
                    .font(.footnote.weight(.medium))
                    .padding(.horizontal, 14).padding(.vertical, 9)
                    .background(.regularMaterial, in: Capsule())
                    .padding(.bottom, 8)
            }
        }
        .navigationTitle("Wallpaper")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { picking = true } label: { Label("Import from iPhone", systemImage: "square.and.arrow.down") }
                    .disabled(model.sendingWallpaper)
            }
        }
        .photosPicker(isPresented: $picking, selection: $photo, matching: .images)
        .onChange(of: photo) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self) {
                    model.sendWallpaper(data, name: "iPhone \(Date().formatted(.dateTime.month(.abbreviated).day().hour().minute()))")
                    while model.sendingWallpaper { try? await Task.sleep(for: .milliseconds(300)) }
                    await load()
                }
                photo = nil
            }
        }
        .task { await load() }
    }

    private var status: String? {
        if model.sendingWallpaper { return "Sending your picture to the TV…" }
        if let failed { return failed }
        guard let setting, let name = sections.flatMap(\.items).first(where: { $0.id == setting })?.name else { return nil }
        if downloading && fraction >= 0 { return "Downloading \(name)… \(Int(fraction * 100))%" }
        return "Setting \(name)…"
    }

    private func load() async {
        guard let o = try? await client.tv("wallpapers") else { return }
        current = o["current"] as? String ?? ""
        sections = (o["sections"] as? [[String: Any]] ?? []).map { s in
            WallpaperSection(id: s["title"] as? String ?? "", items: (s["items"] as? [[String: Any]] ?? []).map {
                WallpaperItem(id: $0["kind"] as? String ?? "", name: $0["name"] as? String ?? "", dynamic: $0["dynamic"] as? Bool ?? false)
            })
        }
    }

    private func choose(_ item: WallpaperItem) {
        guard setting == nil else { return }
        failed = nil
        setting = item.id
        fraction = -1
        Task {
            _ = try? await client.tv("wallpaper/set", ["kind": item.id])
            // Follow the TV as it downloads and prepares the wallpaper.
            while true {
                try? await Task.sleep(for: .milliseconds(400))
                guard let p = try? await client.tv("wallpaper/progress") else { continue }
                fraction = p["fraction"] as? Double ?? -1
                downloading = p["downloading"] as? Bool ?? false
                if !(p["busy"] as? Bool ?? false) {
                    if let e = p["error"] as? String, !e.isEmpty { failed = e } else { current = item.id; Haptics.tap() }
                    break
                }
            }
            setting = nil
        }
    }
}

private struct WallpaperTile: View {
    let client: RemoteClient
    let item: WallpaperItem
    let current: Bool
    let progress: Double?
    let action: () -> Void
    @State private var image: UIImage?

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                ZStack {
                    RoundedRectangle(cornerRadius: 14, style: .continuous).fill(.quaternary)
                    if let image {
                        Image(uiImage: image).resizable().aspectRatio(contentMode: .fill)
                    } else if item.id.isEmpty {
                        LinearGradient(colors: [Color(red: 0.48, green: 0.04, blue: 0.08), .black],
                                       startPoint: .topLeading, endPoint: .bottomTrailing)
                    }
                    if let progress {
                        Color.black.opacity(0.4)
                        DownloadRing(fraction: progress)
                    }
                }
                .aspectRatio(16 / 9, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(alignment: .topTrailing) {
                    if current {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.title3)
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, .blue)
                            .padding(6)
                    }
                }
                HStack(spacing: 4) {
                    Text(item.name).font(.footnote).lineLimit(1)
                    if item.dynamic { Image(systemName: "circle.lefthalf.filled").font(.caption2).foregroundStyle(.secondary) }
                }
                .foregroundStyle(current ? .primary : .secondary)
            }
        }
        .buttonStyle(.plain)
        .task(id: item.id) {
            guard !item.id.isEmpty, image == nil, let data = await client.wallpaperThumb(item.id) else { return }
            image = UIImage(data: data)
        }
    }
}

/// Apple's download ring: a thin ring that fills clockwise, or spins while the size isn't known.
private struct DownloadRing: View {
    let fraction: Double
    @State private var spin = false

    var body: some View {
        ZStack {
            Circle().fill(.black.opacity(0.35)).frame(width: 46, height: 46)
            Circle().stroke(.white.opacity(0.25), lineWidth: 3).frame(width: 34, height: 34)
            if fraction >= 0 {
                Circle().trim(from: 0, to: max(0.02, fraction))
                    .stroke(.white, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .frame(width: 34, height: 34)
                    .animation(.easeOut(duration: 0.35), value: fraction)
            } else {
                Circle().trim(from: 0, to: 0.22)
                    .stroke(.white, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .frame(width: 34, height: 34)
                    .rotationEffect(.degrees(spin ? 360 : 0))
                    .animation(.linear(duration: 0.9).repeatForever(autoreverses: false), value: spin)
                    .onAppear { spin = true }
            }
        }
    }
}

// MARK: - Bluetooth

private struct BluetoothView: View {
    let client: RemoteClient
    @State private var devices: [[String: Any]] = []
    @State private var scanning = false
    @State private var available = true
    @State private var enabled = true

    var body: some View {
        List {
            if !available || !enabled {
                Text(available ? "Bluetooth is off on the TV." : "This TV has no Bluetooth.")
                    .foregroundStyle(.secondary)
            }
            Section("My Devices") {
                ForEach(paired.indices, id: \.self) { i in
                    row(paired[i])
                        .swipeActions {
                            Button("Forget", role: .destructive) {
                                Task {
                                    try? await client.tv("bluetooth/forget", ["address": paired[i]["address"] as? String ?? ""])
                                    await refresh()
                                }
                            }
                        }
                }
                if paired.isEmpty { Text("None").foregroundStyle(.secondary) }
            }
            Section {
                ForEach(others.indices, id: \.self) { i in
                    Button {
                        Task {
                            try? await client.tv("bluetooth/pair", ["address": others[i]["address"] as? String ?? ""])
                            Haptics.tap()
                            await refresh()
                        }
                    } label: { row(others[i]) }
                    .foregroundStyle(.primary)
                }
            } header: {
                HStack {
                    Text("Other Devices")
                    if scanning { ProgressView().controlSize(.mini).padding(.leading, 4) }
                }
            } footer: {
                Text("Put the accessory in pairing mode, then tap it here. To pair a controller, hold its pairing button until its light flashes.")
            }
        }
        .navigationTitle("Bluetooth")
        .toolbar {
            Button("Search") { Task { try? await client.tv("bluetooth/scan", [:]) } }
                .disabled(scanning)
        }
        .task {
            _ = try? await client.tv("bluetooth/scan", [:])
            while !Task.isCancelled {
                await refresh()
                try? await Task.sleep(for: .seconds(2))
            }
        }
    }

    private var paired: [[String: Any]] { devices.filter { $0["paired"] as? Bool ?? false } }
    private var others: [[String: Any]] { devices.filter { !($0["paired"] as? Bool ?? false) } }

    private func row(_ d: [String: Any]) -> some View {
        HStack {
            Label(d["name"] as? String ?? "Device", systemImage: symbol(d["type"] as? String))
            Spacer()
            if d["pairing"] as? Bool ?? false {
                ProgressView()
            } else if d["paired"] as? Bool ?? false {
                Text(d["connected"] as? Bool ?? false ? "Connected" : "Not Connected").foregroundStyle(.secondary)
            }
        }
    }

    private func symbol(_ type: String?) -> String {
        switch type {
        case "audio": return "headphones"
        case "input": return "gamecontroller"
        case "phone": return "iphone"
        default: return "dot.radiowaves.left.and.right"
        }
    }

    private func refresh() async {
        guard let o = try? await client.tv("bluetooth") else { return }
        available = o["available"] as? Bool ?? false
        enabled = o["enabled"] as? Bool ?? false
        scanning = o["scanning"] as? Bool ?? false
        devices = o["devices"] as? [[String: Any]] ?? []
    }
}

// MARK: - Screen Saver

private struct ScreenSaverView: View {
    let client: RemoteClient
    @State private var type = "aerials"
    @State private var theme = "All"
    @State private var clock = true
    @State private var music = true
    @State private var startAfter = -1
    @State private var canSetStartAfter = false
    @State private var themes: [String] = ["All"]
    @State private var choices: [Int] = []
    @State private var loaded = false

    var body: some View {
        Form {
            Section("Type") {
                Picker("Type", selection: $type) {
                    Text("Aerials").tag("aerials")
                    Text("Wallpapers").tag("wallpapers")
                    Text("Clock").tag("clock")
                }
                .pickerStyle(.inline)
                .labelsHidden()
            }
            Section {
                if type == "aerials" {
                    Picker("Themes", selection: $theme) {
                        ForEach(themes, id: \.self) { Text($0).tag($0) }
                    }
                }
                if type != "clock" { Toggle("Show Clock", isOn: $clock) }
                Toggle("Show During Music and Podcasts", isOn: $music)
                if canSetStartAfter {
                    Picker("Start After", selection: $startAfter) {
                        ForEach(choices, id: \.self) { m in Text(m == 0 ? "Never" : "\(m) Minutes").tag(m) }
                    }
                }
            }
            Section {
                Button("Preview on TV") { Task { try? await client.tv("screensaver/start", [:]) } }
            }
        }
        .navigationTitle("Screen Saver")
        .disabled(!loaded)
        .task { await apply(nil) }
        .onChange(of: type) { _, v in if loaded { Task { await apply(["type": v]) } } }
        .onChange(of: theme) { _, v in if loaded { Task { await apply(["theme": v]) } } }
        .onChange(of: clock) { _, v in if loaded { Task { await apply(["clock": v]) } } }
        .onChange(of: music) { _, v in if loaded { Task { await apply(["music": v]) } } }
        .onChange(of: startAfter) { _, v in if loaded && v >= 0 { Task { await apply(["startAfter": v]) } } }
    }

    private func apply(_ change: [String: Any]?) async {
        guard let o = try? await client.tv("screensaver", change ?? [:]) else { return }
        loaded = false
        type = o["type"] as? String ?? type
        theme = o["theme"] as? String ?? theme
        clock = o["clock"] as? Bool ?? clock
        music = o["music"] as? Bool ?? music
        themes = o["themes"] as? [String] ?? themes
        choices = o["startAfterChoices"] as? [Int] ?? choices
        canSetStartAfter = o["canSetStartAfter"] as? Bool ?? false
        let after = o["startAfter"] as? Int ?? -1
        startAfter = choices.contains(after) ? after : (choices.first ?? 0)
        await Task.yield()
        loaded = true
    }
}

// MARK: - Software Update

private struct UpdateView: View {
    let client: RemoteClient
    @State private var info: [String: Any] = [:]
    @State private var checking = true
    @State private var installing = false

    var body: some View {
        List {
            Section {
                LabeledContent("Installed", value: info["installed"] as? String ?? "–")
                if let latest = info["latest"] as? String { LabeledContent("Latest", value: latest) }
            }
            Section {
                if checking {
                    HStack { ProgressView(); Text("Checking for updates…").foregroundStyle(.secondary) }
                } else if info["available"] as? Bool ?? false {
                    Button(installing ? "Installing on the TV…" : "Update Now") {
                        installing = true
                        Task { try? await client.tv("update", ["install": true]) }
                    }
                    .disabled(installing)
                    if let notes = info["notes"] as? String, !notes.isEmpty {
                        Text(notes).font(.footnote).foregroundStyle(.secondary)
                    }
                } else if info["error"] != nil {
                    Text("Couldn't check for updates. Is the TV online?").foregroundStyle(.secondary)
                } else {
                    Text("OpenTV is up to date.").foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("Software Update")
        .task {
            info = (try? await client.tv("update", [:])) ?? [:]
            checking = false
        }
    }
}
