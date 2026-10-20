import AppKit
import Foundation

/**
 * Everything OpenTV Installer does, step by step: get adb (Google's platform-tools, downloaded if this Mac
 * has none), find the TV (USB, Wireless debugging, or network debugging on older Android TVs), download the
 * newest OpenTV release from GitHub, and set the TV up (install, permissions, services, home screen).
 *
 * Every release of the project publishes the same files (OtvOS.apk, IPTV.apk, OpenTV-Companion.zip), so the
 * installer always installs the latest build without being updated itself.
 */
@MainActor
final class Setup: ObservableObject {

    static let repo = "harkhowalia-1/OpenTV"
    static let launcher = "com.gathin.tvhome"
    static let iptv = "com.gathin.otvlive"
    static let airplay = "io.github.jqssun.airplay"

    enum Step: Int, CaseIterable {
        case intro, connect, components, install, done
        var title: String {
            switch self {
            case .intro: "Introduction"
            case .connect: "Your TV"
            case .components: "What to Install"
            case .install: "Installation"
            case .done: "Summary"
            }
        }
    }

    struct Device: Identifiable, Hashable {
        let id: String   // adb serial
        var model = ""
        var android = ""
        var googleTV = false
        var usb = false
        var title: String { model.isEmpty ? id : model }
        var detail: String {
            [android.isEmpty ? nil : "Android \(android)", googleTV ? "Google TV" : "Android TV", usb ? "USB" : "Wi-Fi"]
                .compactMap { $0 }.joined(separator: " · ")
        }
    }

    struct Job: Identifiable {
        enum State { case waiting, running, done, skipped, failed(String) }
        let id = UUID()
        let title: String
        var state: State = .waiting
    }

    @Published var step: Step = .intro
    @Published var restoring = false
    @Published var devices: [Device] = []
    @Published var device: Device?
    @Published var scanning = false
    @Published var connectMessage = ""
    @Published var jobs: [Job] = []
    @Published var log = ""
    @Published var finished = false
    @Published var failed = false
    @Published var newerInstaller: String?

    // Components
    @Published var installIPTV = true
    @Published var installAirPlay = true
    @Published var installCompanion = true
    @Published var speedUp = true

    private var release: [String: URL] = [:]
    private var releaseTag = ""

    let adb = ADB.shared

    // MARK: Finding the TV

    func scan() async {
        scanning = true
        defer { scanning = false }
        if ADB.path == nil {
            connectMessage = "Getting Android's connection tools…"
            do { try await downloadPlatformTools() } catch {
                connectMessage = "Couldn't download Android's connection tools: \(error.localizedDescription)"
                return
            }
        }
        adb.start()
        await adb.refresh()
        let out = await ADB.run(["devices", "-l"]).out
        var found: [Device] = []
        for line in out.split(separator: "\n") where line.contains(" device ") {
            let serial = String(line.split(separator: " ")[0])
            var d = Device(id: serial, usb: line.contains("usb:"))
            let props = await ADB.run(["-s", serial, "shell",
                                       "getprop ro.product.model; getprop ro.build.version.release; pm list packages com.google.android.apps.tv.launcherx"]).out
                .split(separator: "\n").map { String($0).trimmingCharacters(in: .whitespaces) }
            d.model = props.first ?? ""
            d.android = props.count > 1 ? props[1] : ""
            d.googleTV = props.contains { $0.contains("launcherx") }
            found.append(d)
        }
        devices = found
        if device == nil || !found.contains(where: { $0.id == device?.id }) { device = found.first }
        connectMessage = found.isEmpty ? "No TV yet. Follow the steps on the left, then press Look Again." : ""
    }

    /// Older Android TVs (Android 10 and earlier): "Network debugging" listens on port 5555.
    func connect(address: String) async {
        var a = address.trimmingCharacters(in: .whitespaces)
        if !a.contains(":") { a += ":5555" }
        connectMessage = "Connecting to \(a)… Allow the connection on the TV if it asks."
        let out = await ADB.run(["connect", a], timeout: 20).out
        connectMessage = out.contains("connected") ? "" : "Couldn't connect to \(a). Check that network debugging is on."
        await scan()
    }

    func pair(code: String) async {
        connectMessage = "Pairing…"
        connectMessage = await adb.pair(code: code) ? "" : "That code didn't work. Check the code shown on the TV."
        await scan()
    }

    // MARK: Downloads

    /// Google's adb for macOS, into Application Support (nothing installed system-wide).
    private func downloadPlatformTools() async throws {
        let url = URL(string: "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip")!
        let (zip, _) = try await URLSession.shared.download(from: url)
        let dest = ADB.bundledTools.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: dest, withIntermediateDirectories: true)
        try? FileManager.default.removeItem(at: ADB.bundledTools)
        try run("/usr/bin/ditto", ["-x", "-k", zip.path, dest.path])
        try? FileManager.default.removeItem(at: zip)
    }

    /// The newest release's files, by name.
    private func fetchRelease() async throws {
        var r = URLRequest(url: URL(string: "https://api.github.com/repos/\(Self.repo)/releases/latest")!)
        r.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard (resp as? HTTPURLResponse)?.statusCode == 200,
              let o = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw StreamError("Couldn't reach the OpenTV releases on GitHub.")
        }
        releaseTag = o["tag_name"] as? String ?? ""
        for a in o["assets"] as? [[String: Any]] ?? [] {
            if let n = a["name"] as? String, let u = (a["browser_download_url"] as? String).flatMap(URL.init) { release[n] = u }
        }
    }

    /// Is there a newer OpenTV Installer than this one? (shown on the first page)
    func checkForUpdate() async {
        try? await fetchRelease()
        let mine = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        let latest = releaseTag.trimmingCharacters(in: CharacterSet(charactersIn: "v"))
        if !latest.isEmpty, latest.compare(mine, options: .numeric) == .orderedDescending { newerInstaller = latest }
    }

    private func download(_ name: String) async throws -> URL {
        if release.isEmpty { try await fetchRelease() }
        guard let url = release[name] else { throw StreamError("\(name) isn't in the latest release.") }
        let (tmp, _) = try await URLSession.shared.download(from: url)
        let dest = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: tmp, to: dest)
        return dest
    }

    /// The AirPlay receiver (UxPlay for Android, GPL-3.0) from F-Droid's newest suggested build.
    private func downloadAirPlay() async throws -> URL {
        let (data, _) = try await URLSession.shared.data(from: URL(string: "https://f-droid.org/api/v1/packages/\(Self.airplay)")!)
        guard let o = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let code = o["suggestedVersionCode"] as? Int else { throw StreamError("F-Droid didn't answer.") }
        let (tmp, _) = try await URLSession.shared.download(from: URL(string: "https://f-droid.org/repo/\(Self.airplay)_\(code).apk")!)
        let dest = FileManager.default.temporaryDirectory.appendingPathComponent("airplay.apk")
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: tmp, to: dest)
        return dest
    }

    @discardableResult
    private func run(_ tool: String, _ args: [String]) throws -> String {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: tool)
        p.arguments = args
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        try p.run()
        let out = String(decoding: pipe.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
        p.waitUntilExit()
        if p.terminationStatus != 0 { throw StreamError(out.isEmpty ? "\(tool) failed" : out) }
        return out
    }

    // MARK: Installing

    /// Background apps on Google TV / Android TV that cost memory and do nothing for OpenTV.
    static let extras = [
        "com.google.android.feedback", "com.android.tv.feedbackconsent", "com.google.android.tv.remotecontrol.logging",
        "android.autoinstalls.config.google.gtvpai", "com.google.android.apps.tv.dreamx", "com.google.android.backdrop",
        "com.google.android.play.games", "com.google.android.marvin.talkback",
        "com.google.android.syncadapters.calendar", "com.android.settings.intelligence", "com.google.android.tv.dfuservice",
    ]

    /// The stock home screens OtvOS replaces (Google TV, Android TV, older Android TV).
    static let stockHomes = [
        "com.google.android.apps.tv.launcherx", "com.google.android.tungsten.setupwraith",
        "com.google.android.tvlauncher", "com.google.android.leanbacklauncher",
    ]

    private func tv(_ args: [String], timeout: TimeInterval = 120) async -> (out: String, ok: Bool) {
        guard let d = device else { return ("No TV chosen.", false) }
        let r = await ADB.run(["-s", d.id] + args, timeout: timeout)
        log += "$ adb \(args.joined(separator: " "))\n\(r.out)"
        if !r.out.hasSuffix("\n") { log += "\n" }
        return r
    }

    private func shell(_ cmd: String) async -> String { await tv(["shell", cmd]).out }

    private func job(_ title: String, _ work: () async throws -> Bool) async {
        let i = jobs.firstIndex { $0.title == title } ?? { jobs.append(Job(title: title)); return jobs.count - 1 }()
        jobs[i].state = .running
        do {
            jobs[i].state = try await work() ? .done : .skipped
        } catch {
            jobs[i].state = .failed(error.localizedDescription)
            failed = true
        }
    }

    private func installAPK(_ url: URL) async throws {
        let r = await tv(["install", "-r", "-g", url.path], timeout: 600)
        guard r.out.contains("Success") else { throw StreamError(r.out.components(separatedBy: "\n").last { !$0.isEmpty } ?? "Install failed") }
    }

    func install() async {
        step = .install
        failed = false
        finished = false
        log = ""
        var plan = ["Download OpenTV", "Install OtvOS", "Set up OtvOS", "Make OtvOS the home screen"]
        if installIPTV { plan.append("Install IPTV") }
        if installAirPlay { plan.append("Install the AirPlay receiver") }
        if speedUp { plan.append("Turn off Google TV extras") }
        if installCompanion { plan.append("Install OpenTV Companion on this Mac") }
        jobs = plan.map { Job(title: $0) }

        var files: [String: URL] = [:]
        await job("Download OpenTV") {
            files["OtvOS.apk"] = try await download("OtvOS.apk")
            if installIPTV { files["IPTV.apk"] = try await download("IPTV.apk") }
            if installCompanion { files["OpenTV-Companion.zip"] = try await download("OpenTV-Companion.zip") }
            return true
        }
        if failed { return }
        await job("Install OtvOS") {
            try await installAPK(files["OtvOS.apk"]!)
            return true
        }
        if failed { return }
        await job("Set up OtvOS") {
            let p = Self.launcher
            for c in [
                "settings put secure enabled_accessibility_services \(p)/\(p).HomeKeyService",
                "settings put secure accessibility_enabled 1",
                "dpm set-active-admin --user 0 \(p)/.SleepAdmin",
                "cmd notification allow_dnd \(p)",
                "cmd notification allow_listener \(p)/\(p).MediaListener",
                "appops set \(p) TURN_SCREEN_ON allow",
                "pm grant \(p) android.permission.GET_ACCOUNTS",
                "pm grant \(p) android.permission.ACCESS_FINE_LOCATION",
            ] { _ = await shell(c) }
            return true
        }
        await job("Make OtvOS the home screen") {
            _ = await shell("cmd package set-home-activity --user 0 \(Self.launcher)/.MainActivity")
            let installed = Set(await shell("pm list packages").split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) })
            for home in Self.stockHomes where installed.contains("package:\(home)") {
                _ = await shell("pm disable-user --user 0 \(home)")
            }
            _ = await shell("input keyevent KEYCODE_HOME")
            return true
        }
        if installIPTV {
            await job("Install IPTV") {
                try await installAPK(files["IPTV.apk"]!)
                return true
            }
        }
        if installAirPlay {
            await job("Install the AirPlay receiver") {
                try await installAPK(try await downloadAirPlay())
                return true
            }
        }
        if speedUp {
            await job("Turn off Google TV extras") {
                let installed = Set(await shell("pm list packages -e").split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) })
                for p in Self.extras where installed.contains("package:\(p)") {
                    _ = await shell("pm disable-user --user 0 \(p)")
                }
                // The Play Store still opens; it just stops updating and running in the background.
                _ = await shell("cmd appops set com.android.vending RUN_ANY_IN_BACKGROUND ignore")
                _ = await shell("cmd appops set com.android.vending RUN_IN_BACKGROUND ignore")
                return true
            }
        }
        if installCompanion {
            await job("Install OpenTV Companion on this Mac") {
                let zip = files["OpenTV-Companion.zip"]!
                let dest = URL(fileURLWithPath: "/Applications/OpenTV Companion.app")
                if let running = NSRunningApplication.runningApplications(withBundleIdentifier: "com.harkhowalia.tvremote.mac").first {
                    running.terminate()
                    try? await Task.sleep(for: .seconds(1))
                }
                try? FileManager.default.removeItem(at: dest)
                try run("/usr/bin/ditto", ["-x", "-k", zip.path, "/Applications"])
                _ = try? await NSWorkspace.shared.openApplication(at: dest, configuration: NSWorkspace.OpenConfiguration())
                return true
            }
        }
        for f in files.values { try? FileManager.default.removeItem(at: f) }
        finished = true
        if !failed { step = .done }
    }

    /// Puts the TV back the way it came: stock home screen, extras on, OpenTV removed.
    func restore() async {
        step = .install
        restoring = true
        failed = false
        finished = false
        log = ""
        jobs = ["Turn the stock home screen back on", "Turn Google TV extras back on", "Remove OpenTV"].map { Job(title: $0) }
        await job("Turn the stock home screen back on") {
            for home in Self.stockHomes { _ = await shell("pm enable \(home)") }
            _ = await shell("cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity")
            return true
        }
        await job("Turn Google TV extras back on") {
            for p in Self.extras { _ = await shell("pm enable \(p)") }
            _ = await shell("cmd appops set com.android.vending RUN_ANY_IN_BACKGROUND allow")
            _ = await shell("cmd appops set com.android.vending RUN_IN_BACKGROUND allow")
            return true
        }
        await job("Remove OpenTV") {
            _ = await shell("dpm remove-active-admin --user 0 \(Self.launcher)/.SleepAdmin")
            _ = await shell("settings delete secure enabled_accessibility_services")
            for p in [Self.launcher, Self.iptv] { _ = await tv(["uninstall", p]) }
            _ = await shell("input keyevent KEYCODE_HOME")
            return true
        }
        finished = true
        if !failed { step = .done }
    }
}
