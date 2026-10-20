import AppKit
import SwiftUI
import UniformTypeIdentifiers

/**
 * The TV Manager window: everything about the TV from the Mac. Apps (open, stop, clear, turn off, uninstall,
 * install from this Mac's files), tweaks (animations, screen saver and sleep, resolution, interface size,
 * private DNS, HDMI-CEC), files, screenshots, and the device itself.
 *
 * Changes go through adb (USB-C or Wireless debugging), which can do them without prompts on the TV; app names
 * and pictures come from the OpenTV launcher.
 */
@MainActor
final class TVManager: ObservableObject {

    static let shared = TVManager()

    @Published var apps: [TVApp] = []
    @Published var icons: [String: NSImage] = [:]
    @Published var busy: String?
    @Published var message: String?
    @Published var info: [(String, String)] = []
    @Published var settings: [String: String] = [:]
    @Published var modes: [DisplayMode] = []
    @Published var currentMode = ""

    struct DisplayMode: Hashable { let width: Int, height: Int, fps: Double
        var label: String { "\(width)×\(height) · \(Int(fps.rounded())) Hz" }
    }

    let adb = ADB.shared
    var model: RemoteModel { Services.model }

    private var client: RemoteClient? { model.route.map { RemoteClient(tv: $0) } }

    // MARK: Loading

    func load() async {
        adb.start()
        await adb.refresh()
        await loadApps()
        await loadDevice()
        await loadSettings()
    }

    func loadApps() async {
        guard let client else { return }
        if let list = try? await client.apps() {
            apps = list.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        }
    }

    func icon(for app: TVApp) {
        guard icons[app.id] == nil, let client else { return }
        icons[app.id] = NSImage()   // requested
        Task {
            if let data = await client.icon(app.id), let img = NSImage(data: data) { icons[app.id] = img }
        }
    }

    func loadDevice() async {
        guard adb.serial != nil else { info = []; return }
        let props = await adb.shell("getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.security_patch")
            .split(separator: "\n").map(String.init)
        let mem = await adb.shell("grep -E 'MemTotal|MemAvailable' /proc/meminfo").split(separator: "\n")
            .map { $0.split(separator: " ").dropFirst().first.flatMap { Double($0) } ?? 0 }
        let df = await adb.shell("df -h /data | tail -1").split(separator: " ").map(String.init)
        let up = await adb.shell("cat /proc/uptime").split(separator: " ").first.flatMap { Double($0) } ?? 0
        let ip = await adb.shell("ip -4 addr show wlan0 | grep inet | head -1").split(separator: " ").dropFirst().first
            .map { String($0.split(separator: "/")[0]) } ?? "—"
        var rows: [(String, String)] = []
        rows.append(("Model", props.first.map { "\(Brand.model) (\($0))" } ?? Brand.model))
        if props.count > 1 { rows.append(("Android", "\(props[1]) · security patch \(props.count > 2 ? props[2] : "—")")) }
        if mem.count == 2 { rows.append(("Memory", String(format: "%.0f MB free of %.1f GB", mem[1] / 1024, mem[0] / 1024 / 1024))) }
        if df.count >= 4 { rows.append(("Storage", "\(df[3]) free of \(df[1])")) }
        rows.append(("Up for", Duration.seconds(up).formatted(.units(allowed: [.days, .hours, .minutes], width: .wide))))
        rows.append(("Address", ip))
        rows.append(("Connected by", adb.link == .usb ? "USB-C" : "Wi-Fi (Wireless debugging)"))
        info = rows
    }

    // MARK: Apps

    private func run(_ what: String, _ action: () async -> (String, Bool)) async {
        busy = what
        let (out, ok) = await action()
        busy = nil
        message = ok ? nil : out.trimmingCharacters(in: .whitespacesAndNewlines)
        await loadApps()
    }

    func open(_ app: TVApp) async {
        await run("Opening \(app.name)…") {
            let r = await adb.tv(["shell", "monkey -p \(app.id) -c android.intent.category.LEANBACK_LAUNCHER 1 || monkey -p \(app.id) -c android.intent.category.LAUNCHER 1"])
            return (r.out, true)
        }
    }

    func forceStop(_ app: TVApp) async {
        await run("Stopping \(app.name)…") { await adb.tv(["shell", "am", "force-stop", app.id]) }
    }

    func clearCache(_ app: TVApp) async {
        await run("Clearing \(app.name)'s cache…") { await adb.tv(["shell", "pm", "clear", "--cache-only", app.id]) }
    }

    func clearData(_ app: TVApp) async {
        await run("Clearing \(app.name)'s data…") { await adb.tv(["shell", "pm", "clear", app.id]) }
    }

    func setEnabled(_ app: TVApp, _ on: Bool) async {
        await run(on ? "Turning on \(app.name)…" : "Turning off \(app.name)…") {
            await adb.tv(on ? ["shell", "pm", "enable", app.id] : ["shell", "pm", "disable-user", "--user", "0", app.id])
        }
    }

    /// Apps you installed are removed completely; built-in ones are removed for this user (and can come back).
    func uninstall(_ app: TVApp) async {
        await run("Removing \(app.name)…") {
            let r = app.system ? await adb.tv(["shell", "pm", "uninstall", "-k", "--user", "0", app.id])
                               : await adb.tv(["uninstall", app.id])
            return (r.out, r.out.contains("Success"))
        }
    }

    /// Installs .apk, .apks, .xapk and .apkm files from this Mac.
    func install(_ urls: [URL]) async {
        for url in urls {
            await run("Installing \(url.lastPathComponent)…") {
                let ext = url.pathExtension.lowercased()
                if ext == "apk" {
                    let r = await adb.tv(["install", "-r", "-g", url.path], timeout: 600)
                    return (r.out, r.out.contains("Success"))
                }
                // Split APK bundles: unpack and install every part together.
                let dir = FileManager.default.temporaryDirectory.appendingPathComponent("otv-\(UUID().uuidString)")
                defer { try? FileManager.default.removeItem(at: dir) }
                try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let unzip = Process()
                unzip.executableURL = URL(fileURLWithPath: "/usr/bin/unzip")
                unzip.arguments = ["-q", url.path, "*.apk", "-d", dir.path]
                try? unzip.run()
                unzip.waitUntilExit()
                let parts = (FileManager.default.enumerator(at: dir, includingPropertiesForKeys: nil)?.allObjects as? [URL] ?? [])
                    .filter { $0.pathExtension == "apk" }.map(\.path)
                guard !parts.isEmpty else { return ("\(url.lastPathComponent) has no apps inside.", false) }
                let r = await adb.tv(["install-multiple", "-r", "-g"] + parts, timeout: 600)
                return (r.out, r.out.contains("Success"))
            }
        }
    }

    /// Copies files into the TV's Download folder.
    func send(_ urls: [URL]) async {
        for url in urls {
            await run("Copying \(url.lastPathComponent)…") { await adb.tv(["push", url.path, "/sdcard/Download/"], timeout: 1200) }
        }
        message = message ?? "Copied to the TV's Download folder."
    }

    // MARK: Device

    func screenshot() async {
        busy = "Taking a screenshot…"
        defer { busy = nil }
        guard let png = await adb.execOut("screencap -p"), !png.isEmpty else { message = "Couldn't take a screenshot."; return }
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd 'at' HH.mm.ss"
        let url = FileManager.default.urls(for: .desktopDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("TV Screenshot \(f.string(from: Date())).png")
        try? png.write(to: url)
        NSWorkspace.shared.activateFileViewerSelecting([url])
    }

    func reboot() async {
        await run("Restarting the TV…") { await adb.tv(["reboot"]) }
    }

    /// Stops every app that isn't needed: what Control Center's Close Background Apps does, without the tour.
    func freeMemory() async {
        busy = "Closing background apps…"
        let keep: Set = ["com.gathin.tvhome", "io.github.jqssun.airplay"]
        let running = Set(await adb.shell("ps -A -o NAME").split(separator: "\n").map { String($0.split(separator: ":")[0]) })
        let targets = apps.filter { $0.launchable && !keep.contains($0.id) && running.contains($0.id) }.map(\.id)
        if !targets.isEmpty { _ = await adb.shell(targets.map { "am force-stop \($0)" }.joined(separator: "; ")) }
        _ = await adb.shell("pm trim-caches 999G")
        busy = nil
        message = targets.isEmpty ? "Nothing was running." : "Closed \(targets.count) app\(targets.count == 1 ? "" : "s")."
        await loadDevice()
    }

    // MARK: Tweaks

    static let tweakKeys = [
        "global window_animation_scale", "global transition_animation_scale", "global animator_duration_scale",
        "system screen_off_timeout", "secure sleep_timeout", "global hdmi_control_enabled",
        "global private_dns_mode", "global private_dns_specifier",
    ]

    func loadSettings() async {
        guard adb.serial != nil else { return }
        let out = await adb.shell(Self.tweakKeys.map { "settings get \($0)" }.joined(separator: "; ")).split(separator: "\n", omittingEmptySubsequences: false)
        var s: [String: String] = [:]
        for (i, k) in Self.tweakKeys.enumerated() where i < out.count { s[k] = String(out[i]).trimmingCharacters(in: .whitespaces) }
        s["density"] = await adb.shell("wm density")
        settings = s
        let dump = await adb.shell("dumpsys display | grep -oE 'mSupportedModes=\\\\[.*\\\\]|mActiveModeId=[0-9]+' | head -2")
        modes = parseModes(dump)
        currentMode = await adb.shell("dumpsys display | grep -oE 'width=[0-9]+, height=[0-9]+, fps=[0-9.]+' | head -1")
    }

    private func parseModes(_ s: String) -> [DisplayMode] {
        let re = try! NSRegularExpression(pattern: "width=(\\d+), height=(\\d+), fps=([0-9.]+)")
        var seen = Set<String>()
        return re.matches(in: s, range: NSRange(s.startIndex..., in: s)).compactMap { m in
            func g(_ i: Int) -> String { String(s[Range(m.range(at: i), in: s)!]) }
            let d = DisplayMode(width: Int(g(1)) ?? 0, height: Int(g(2)) ?? 0, fps: Double(g(3)) ?? 0)
            return seen.insert(d.label).inserted ? d : nil
        }.sorted { ($0.width, $0.fps) > ($1.width, $1.fps) }
    }

    func put(_ key: String, _ value: String) async {
        let parts = key.split(separator: " ")
        _ = await adb.tv(["shell", "settings", "put", String(parts[0]), String(parts[1]), value])
        settings[key] = value
    }

    func setAnimations(_ scale: String) async {
        for k in ["global window_animation_scale", "global transition_animation_scale", "global animator_duration_scale"] {
            await put(k, scale)
        }
    }

    func setDNS(_ host: String?) async {
        if let host, !host.isEmpty {
            await put("global private_dns_specifier", host)
            await put("global private_dns_mode", "hostname")
        } else {
            await put("global private_dns_mode", "opportunistic")
        }
    }

    func setDensity(_ dpi: Int?) async {
        _ = await adb.tv(["shell", "wm", "density", dpi.map(String.init) ?? "reset"])
        settings["density"] = await adb.shell("wm density")
    }

    func setMode(_ m: DisplayMode?) async {
        if let m {
            _ = await adb.tv(["shell", "cmd", "display", "set-user-preferred-display-mode", "\(m.width)", "\(m.height)", "\(m.fps)"])
        } else {
            _ = await adb.tv(["shell", "cmd", "display", "clear-user-preferred-display-mode"])
        }
        try? await Task.sleep(for: .seconds(2))
        await loadSettings()
    }
}

// MARK: - Window

enum Windows {
    private static var windows: [String: NSWindow] = [:]

    @MainActor
    static func show<V: View>(_ id: String, title: String, size: NSSize, resizable: Bool = true, @ViewBuilder _ content: () -> V) -> NSWindow {
        if let w = windows[id] {
            NSApp.setActivationPolicy(.regular)
            NSApp.activate(ignoringOtherApps: true)
            w.makeKeyAndOrderFront(nil)
            return w
        }
        var style: NSWindow.StyleMask = [.titled, .closable, .miniaturizable, .fullSizeContentView]
        if resizable { style.insert(.resizable) }
        let w = NSWindow(contentRect: NSRect(origin: .zero, size: size), styleMask: style,
                         backing: .buffered, defer: false)
        w.title = title
        w.isReleasedWhenClosed = false
        w.contentViewController = NSHostingController(rootView: content())
        w.setContentSize(size)
        w.center()
        w.setFrameAutosaveName(id)
        windows[id] = w
        // While a window is open the app is a regular one: in the Dock and the app switcher.
        NSApp.setActivationPolicy(.regular)
        NotificationCenter.default.addObserver(forName: NSWindow.willCloseNotification, object: w, queue: .main) { _ in
            MainActor.assumeIsolated {
                windows[id] = nil
                if windows.isEmpty { NSApp.setActivationPolicy(.accessory) }
            }
        }
        NSApp.activate(ignoringOtherApps: true)
        w.makeKeyAndOrderFront(nil)
        return w
    }

    @MainActor
    static func window(_ id: String) -> NSWindow? { windows[id] }

    @MainActor
    static func forget(_ id: String) { windows[id] = nil }
}

struct ManagerView: View {
    enum Section: String, CaseIterable, Identifiable {
        case overview = "Overview", apps = "Apps", tweaks = "Tweaks", files = "Files"
        var id: String { rawValue }
        var icon: String {
            switch self {
            case .overview: "tv"
            case .apps: "square.grid.2x2"
            case .tweaks: "slider.horizontal.3"
            case .files: "folder"
            }
        }
    }

    @ObservedObject var m = TVManager.shared
    @ObservedObject var adb = ADB.shared
    @State private var section: Section? = .overview

    var body: some View {
        NavigationSplitView {
            List(Section.allCases, selection: $section) { s in
                Label(s.rawValue, systemImage: s.icon).tag(s)
            }
            .navigationSplitViewColumnWidth(min: 170, ideal: 180)
            .safeAreaInset(edge: .bottom) { connection.padding(12) }
        } detail: {
            VStack(spacing: 0) {
                if adb.serial == nil { NotConnectedBanner() }
                Group {
                    switch section ?? .overview {
                    case .overview: OverviewPane()
                    case .apps: AppsPane()
                    case .tweaks: TweaksPane()
                    case .files: FilesPane()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                if let busy = m.busy {
                    HStack { ProgressView().controlSize(.small); Text(busy) }
                        .font(.callout).padding(8).frame(maxWidth: .infinity).background(.bar)
                } else if let msg = m.message {
                    HStack {
                        Text(msg).lineLimit(3)
                        Spacer()
                        Button("OK") { m.message = nil }.buttonStyle(.borderless)
                    }
                    .font(.callout).padding(8).background(.bar)
                }
            }
        }
        .task { await m.load() }
        .onChange(of: adb.serial) { _, _ in Task { await m.loadDevice(); await m.loadSettings() } }
    }

    private var connection: some View {
        HStack(spacing: 6) {
            Circle().fill(adb.serial == nil ? Color.orange : .green).frame(width: 7, height: 7)
            Text(adb.link == .usb ? "USB-C" : adb.link == .wifi ? "Wi-Fi" : "Not connected")
                .font(.caption).foregroundStyle(.secondary)
            Spacer()
        }
    }
}

private struct NotConnectedBanner: View {
    @ObservedObject var adb = ADB.shared
    @State private var code = ""
    @State private var pairing = false
    @State private var failed = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Full control needs adb", systemImage: "cable.connector").font(.headline)
            Text(adb.status.isEmpty ? "Plug the TV into this Mac by USB-C, or turn on Wireless debugging on the TV." : adb.status)
                .font(.callout).foregroundStyle(.secondary)
            if adb.pairingAvailable {
                HStack {
                    TextField("Pairing code", text: $code).frame(width: 120)
                    Button(pairing ? "Pairing…" : "Pair") {
                        pairing = true
                        Task {
                            failed = !(await adb.pair(code: code))
                            pairing = false
                        }
                    }
                    .disabled(code.count < 6 || pairing)
                    if failed { Text("That didn't work. Check the code on the TV.").foregroundStyle(.red).font(.caption) }
                }
            }
            Button("Try Again") { Task { await adb.refresh() } }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.orange.opacity(0.1))
    }
}

private struct OverviewPane: View {
    @ObservedObject var m = TVManager.shared
    @State private var confirmReboot = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(spacing: 16) {
                    Image(systemName: "appletv.fill").font(.system(size: 44)).foregroundStyle(.secondary)
                    VStack(alignment: .leading) {
                        Text(m.model.route?.name ?? "TV").font(.title.bold())
                        Text("\(Brand.os) on \(Brand.model)").foregroundStyle(.secondary)
                    }
                }
                Grid(alignment: .leading, horizontalSpacing: 24, verticalSpacing: 8) {
                    ForEach(m.info, id: \.0) { row in
                        GridRow {
                            Text(row.0).foregroundStyle(.secondary)
                            Text(row.1).textSelection(.enabled)
                        }
                    }
                }
                HStack {
                    Button { Task { await m.freeMemory() } } label: { Label("Close Background Apps", systemImage: "xmark.app") }
                    Button { Task { await m.screenshot() } } label: { Label("Screenshot", systemImage: "camera") }
                    Button { confirmReboot = true } label: { Label("Restart", systemImage: "arrow.clockwise") }
                }
                .disabled(m.adb.serial == nil)
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .confirmationDialog("Restart the TV?", isPresented: $confirmReboot) {
            Button("Restart") { Task { await m.reboot() } }
        }
    }
}

private struct AppsPane: View {
    enum Filter: String, CaseIterable { case yours = "Your Apps", builtIn = "Built-in", off = "Turned Off" }
    @ObservedObject var m = TVManager.shared
    @State private var filter = Filter.yours
    @State private var search = ""
    @State private var dropping = false
    @State private var confirm: (TVApp, String)?

    private var shown: [TVApp] {
        m.apps.filter { a in
            let f: Bool = switch filter {
            case .yours: !a.system || a.updatedSystem && a.launchable
            case .builtIn: a.system
            case .off: !a.enabled
            }
            return f && (search.isEmpty || a.name.localizedCaseInsensitiveContains(search) || a.id.contains(search.lowercased()))
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Picker("", selection: $filter) { ForEach(Filter.allCases, id: \.self) { Text($0.rawValue) } }
                    .pickerStyle(.segmented).labelsHidden().frame(width: 300)
                Spacer()
                TextField("Search", text: $search).textFieldStyle(.roundedBorder).frame(width: 180)
                Button { pickAPKs() } label: { Label("Install…", systemImage: "plus") }
                    .disabled(m.adb.serial == nil)
            }
            .padding(12)
            List(shown) { app in AppRow(app: app, confirm: $confirm) }
                .overlay {
                    if dropping {
                        RoundedRectangle(cornerRadius: 12).stroke(Color.accentColor, style: StrokeStyle(lineWidth: 3, dash: [8]))
                            .overlay(Text("Drop to install on the TV").font(.title3.bold()))
                            .padding(8)
                    } else if m.apps.isEmpty {
                        ContentUnavailableView("Loading apps…", systemImage: "square.grid.2x2")
                    }
                }
        }
        .onDrop(of: [.fileURL], isTargeted: $dropping) { providers in
            Task {
                var urls: [URL] = []
                for p in providers {
                    if let u = try? await p.loadItem(forTypeIdentifier: UTType.fileURL.identifier) as? Data,
                       let url = URL(dataRepresentation: u, relativeTo: nil) { urls.append(url) }
                }
                await m.install(urls.filter { ["apk", "apks", "xapk", "apkm"].contains($0.pathExtension.lowercased()) })
            }
            return true
        }
        .confirmationDialog(confirm?.1 ?? "", isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }),
                            presenting: confirm) { c in
            Button(c.1.hasPrefix("Uninstall") ? "Uninstall" : "Clear Data", role: .destructive) {
                Task { c.1.hasPrefix("Uninstall") ? await m.uninstall(c.0) : await m.clearData(c.0) }
            }
        }
    }

    private func pickAPKs() {
        let p = NSOpenPanel()
        p.allowsMultipleSelection = true
        p.allowedContentTypes = ["apk", "apks", "xapk", "apkm"].compactMap { UTType(filenameExtension: $0) }
        p.message = "Choose Android apps to install on the TV"
        if p.runModal() == .OK { Task { await m.install(p.urls) } }
    }
}

private struct AppRow: View {
    let app: TVApp
    @Binding var confirm: (TVApp, String)?
    @ObservedObject var m = TVManager.shared

    var body: some View {
        HStack(spacing: 12) {
            Group {
                if let img = m.icons[app.id], img.size.width > 0 {
                    Image(nsImage: img).resizable().aspectRatio(contentMode: .fit)
                } else {
                    RoundedRectangle(cornerRadius: 6).fill(.quaternary)
                }
            }
            .frame(width: 64, height: 36)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .onAppear { m.icon(for: app) }
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(app.name).font(.body.weight(.medium))
                    if !app.enabled { Text("Off").font(.caption2.bold()).padding(.horizontal, 5).background(.orange.opacity(0.25), in: Capsule()) }
                }
                Text("\(app.id)\(app.version.isEmpty ? "" : " · \(app.version)")").font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if app.launchable && app.enabled {
                Button("Open") { Task { await m.open(app) } }.disabled(m.adb.serial == nil)
            }
            Menu {
                Button("Force Stop") { Task { await m.forceStop(app) } }
                Button("Clear Cache") { Task { await m.clearCache(app) } }
                Button("Clear Data…") { confirm = (app, "Clear all of \(app.name)'s data? Sign-ins and downloads are lost.") }
                Divider()
                Button(app.enabled ? "Turn Off" : "Turn On") { Task { await m.setEnabled(app, !app.enabled) } }
                Button(app.system ? "Uninstall for This TV…" : "Uninstall…") {
                    confirm = (app, "Uninstall \(app.name)?" + (app.system ? " It's built in and can be restored by a factory reset." : ""))
                }
                .disabled(app.id == "com.gathin.tvhome")
            } label: { Image(systemName: "ellipsis.circle") }
                .menuStyle(.borderlessButton).fixedSize()
                .disabled(m.adb.serial == nil)
        }
        .padding(.vertical, 3)
    }
}

private struct TweaksPane: View {
    @ObservedObject var m = TVManager.shared
    @State private var customDNS = ""

    private var animations: String { m.settings["global window_animation_scale"].flatMap { $0 == "null" ? nil : $0 } ?? "1.0" }

    var body: some View {
        Form {
            Section("Speed") {
                Picker("Animations", selection: Binding(get: { animations }, set: { v in Task { await m.setAnimations(v) } })) {
                    Text("Off (fastest)").tag("0.0")
                    Text("Quick").tag("0.5")
                    Text("Normal").tag("1.0")
                }
                LabeledContent("Memory") {
                    Button("Close Background Apps") { Task { await m.freeMemory() } }
                }
            }
            Section("Picture") {
                Picker("Resolution", selection: Binding<TVManager.DisplayMode?>(get: { nil }, set: { v in Task { await m.setMode(v) } })) {
                    Text("Automatic").tag(TVManager.DisplayMode?.none)
                    ForEach(m.modes, id: \.self) { Text($0.label).tag(Optional($0)) }
                }
                if !m.currentMode.isEmpty {
                    Text("Now: \(m.currentMode.replacingOccurrences(of: "width=", with: "").replacingOccurrences(of: ", height=", with: "×").replacingOccurrences(of: ", fps=", with: " · ")) Hz")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Picker("Interface size", selection: Binding(get: { densityTag }, set: { v in Task { await m.setDensity(v == 0 ? nil : v) } })) {
                    Text("Smaller").tag(280)
                    Text("Default").tag(0)
                    Text("Larger").tag(360)
                }
            }
            Section("Screen saver and sleep") {
                Picker("Screen saver after", selection: setting("system screen_off_timeout")) {
                    Text("1 minute").tag("60000"); Text("5 minutes").tag("300000"); Text("15 minutes").tag("900000")
                    Text("30 minutes").tag("1800000"); Text("Never").tag("2147483647")
                }
                Picker("Turn off TV after", selection: setting("secure sleep_timeout")) {
                    Text("30 minutes").tag("1800000"); Text("1 hour").tag("3600000"); Text("4 hours").tag("14400000")
                    Text("Never").tag("-1")
                }
            }
            Section("Network") {
                Picker("Private DNS", selection: dnsBinding) {
                    ForEach(Self.dnsPresets, id: \.1) { p in Text(p.0).tag(p.1) }
                    if !Self.dnsPresets.contains(where: { $0.1 == dnsTag }) { Text(dnsTag).tag(dnsTag) }
                }
                HStack {
                    TextField("Custom DNS host", text: $customDNS)
                    Button("Use") { Task { await m.setDNS(customDNS) } }.disabled(customDNS.isEmpty)
                }
            }
            Section("TV") {
                Toggle("Control the TV with HDMI-CEC", isOn: Binding(get: { m.settings["global hdmi_control_enabled"] != "0" },
                                                                    set: { v in Task { await m.put("global hdmi_control_enabled", v ? "1" : "0") } }))
            }
        }
        .formStyle(.grouped)
        .disabled(m.adb.serial == nil)
        .task { await m.loadSettings() }
    }

    private var densityTag: Int {
        let s = m.settings["density"] ?? ""
        guard s.contains("Override density:") else { return 0 }
        return Int(s.components(separatedBy: "Override density:").last?.trimmingCharacters(in: .whitespaces) ?? "") ?? 0
    }

    private static let dnsPresets: [(String, String)] = [
        ("Automatic", "auto"), ("AdGuard (blocks ads)", "dns.adguard-dns.com"), ("Cloudflare", "one.one.one.one"),
        ("Quad9 (blocks malware)", "dns.quad9.net"),
    ]

    private var dnsBinding: Binding<String> {
        Binding(get: { dnsTag }, set: { v in Task { await m.setDNS(v == "auto" ? nil : v) } })
    }

    private var dnsTag: String {
        m.settings["global private_dns_mode"] == "hostname" ? (m.settings["global private_dns_specifier"] ?? "auto") : "auto"
    }

    private func setting(_ key: String) -> Binding<String> {
        Binding(get: { m.settings[key] ?? "" }, set: { v in Task { await m.put(key, v) } })
    }
}

private struct FilesPane: View {
    @ObservedObject var m = TVManager.shared
    @State private var dropping = false

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "square.and.arrow.down.on.square").font(.system(size: 46)).foregroundStyle(.secondary)
            Text("Drop files here to copy them to the TV").font(.title3.bold())
            Text("They go to the TV's Download folder. Android apps (.apk) dropped on Apps are installed instead.")
                .foregroundStyle(.secondary).multilineTextAlignment(.center)
            Button("Choose Files…") {
                let p = NSOpenPanel()
                p.allowsMultipleSelection = true
                if p.runModal() == .OK { Task { await m.send(p.urls) } }
            }
            .disabled(m.adb.serial == nil)
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(RoundedRectangle(cornerRadius: 14).stroke(dropping ? Color.accentColor : .secondary.opacity(0.3),
                                                              style: StrokeStyle(lineWidth: 2, dash: [8])).padding(20))
        .onDrop(of: [.fileURL], isTargeted: $dropping) { providers in
            Task {
                var urls: [URL] = []
                for p in providers {
                    if let u = try? await p.loadItem(forTypeIdentifier: UTType.fileURL.identifier) as? Data,
                       let url = URL(dataRepresentation: u, relativeTo: nil) { urls.append(url) }
                }
                await m.send(urls)
            }
            return true
        }
    }
}

/// The project's names, as the TV shows them.
enum Brand {
    static let model = "OpenTV"
    static let os = "OtvOS"
}
