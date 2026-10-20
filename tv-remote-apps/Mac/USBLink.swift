import Foundation

/// Wired connection: when the TV's Chromecast is plugged into this Mac by USB-C (with USB debugging on), its
/// servers are forwarded to local ports with adb, and the remote, cursor and display stream use that instead
/// of Wi-Fi. Checks every few seconds; does nothing when adb isn't installed.
@MainActor
final class USBLink {

    static let localPorts = (http: 18800, control: 18801, display: 18802)

    private weak var model: RemoteModel?
    private var timer: Timer?
    private var serial: String?
    private var probing = false
    private let adb: String? = USBLink.findADB()

    init(model: RemoteModel) {
        self.model = model
    }

    var available: Bool { adb != nil }

    func start() {
        guard adb != nil, timer == nil else { return }
        check()
        timer = Timer.scheduledTimer(withTimeInterval: 4, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.check() }
        }
    }

    private static func findADB() -> String? {
        let home = FileManager.default.homeDirectoryForCurrentUser.path
        let candidates = [
            "/opt/homebrew/bin/adb",
            "/opt/homebrew/share/android-commandlinetools/platform-tools/adb",
            "/usr/local/bin/adb",
            "\(home)/Library/Android/sdk/platform-tools/adb",
        ]
        return candidates.first { FileManager.default.isExecutableFile(atPath: $0) }
    }

    private func check() {
        guard let adb else { return }
        Task.detached(priority: .utility) {
            let devices = Self.run(adb, ["devices", "-l"])
            // A USB device (not a wireless "ip:port" one) that is ready.
            let usb = devices.split(separator: "\n").first { line in
                line.contains(" device ") && line.contains("usb:") && !line.hasPrefix("List")
            }
            let serial = usb.map { String($0.split(separator: " ").first ?? "") }
            await MainActor.run { self.update(serial: serial, adb: adb) }
        }
    }

    private func update(serial: String?, adb: String) {
        // Same cable as before: nothing to do, unless the TV didn't answer last time (it was restarting, or its
        // app was being updated). Then keep trying, so the link doesn't silently fall back to Wi-Fi for good.
        if serial == self.serial && (serial == nil || model?.usbTV != nil || probing) { return }
        self.serial = serial
        guard let serial else {
            model?.usbTV = nil
            return
        }
        probing = true
        Task.detached(priority: .utility) {
            let p = Self.localPorts
            for (local, remote) in [(p.http, 8800), (p.control, 8801), (p.display, 8802)] {
                _ = Self.run(adb, ["-s", serial, "forward", "tcp:\(local)", "tcp:\(remote)"])
            }
            let probe = TV(name: "TV", host: "127.0.0.1", port: p.http, controlPort: p.control, displayPort: p.display, usb: true)
            let hello = try? await RemoteClient(tv: probe).hello()
            await MainActor.run {
                self.probing = false
                guard let hello else { self.model?.usbTV = nil; return }
                var tv = probe
                tv.name = hello.name
                tv.tvID = hello.id
                self.model?.usbTV = tv
            }
        }
    }

    nonisolated private static func run(_ tool: String, _ args: [String]) -> String {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: tool)
        p.arguments = args
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = Pipe()
        do {
            try p.run()
        } catch {
            return ""
        }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return String(decoding: data, as: UTF8.self)
    }
}
