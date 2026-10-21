import Foundation
import Network

/**
 * adb, the way the TV Manager and "TV on Mac" reach the TV with full control (install, uninstall, settings,
 * screen streaming): over the USB-C cable when the Chromecast is plugged in, otherwise over Wi-Fi with the TV's
 * Wireless debugging.
 *
 * Wireless debugging is found by Bonjour and relayed through this app on localhost: macOS's Local Network
 * permission belongs to this app, and the adb tool on its own is usually not allowed to reach the TV.
 */
@MainActor
final class ADB: ObservableObject {

    static let shared = ADB()

    enum Link: Equatable { case none, usb, wifi }

    @Published private(set) var link: Link = .none
    @Published private(set) var serial: String?
    @Published private(set) var status = ""
    /// Wireless debugging is on and waiting to be paired with this Mac (it shows a code on the TV).
    @Published private(set) var pairingAvailable = false

    /// Where OpenTV Installer puts Google's platform-tools when this Mac has no adb of its own.
    nonisolated static let bundledTools = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("OpenTV/platform-tools")

    nonisolated static var path: String? {
        let home = FileManager.default.homeDirectoryForCurrentUser.path
        return ["/opt/homebrew/bin/adb", "/opt/homebrew/share/android-commandlinetools/platform-tools/adb",
                "/usr/local/bin/adb", "\(home)/Library/Android/sdk/platform-tools/adb",
                bundledTools.appendingPathComponent("adb").path]
            .first { FileManager.default.isExecutableFile(atPath: $0) }
    }

    private var connectBrowser: NWBrowser?
    private var pairBrowser: NWBrowser?
    private var connectService: NWEndpoint?
    private var pairService: NWEndpoint?
    private var relay: TCPRelay?
    private var refreshing = false

    var available: Bool { Self.path != nil }

    func start() {
        guard available, connectBrowser == nil else { return }
        connectBrowser = browse("_adb-tls-connect._tcp") { [weak self] e in
            self?.connectService = e
            Task { await self?.refresh() }
        }
        pairBrowser = browse("_adb-tls-pairing._tcp") { [weak self] e in
            self?.pairService = e
            self?.pairingAvailable = e != nil
        }
        Task { await refresh() }
        // Wi-Fi links drop (the TV sleeps, the app restarts): check again every few seconds.
        Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            Task { @MainActor in await self?.refresh() }
        }
    }

    private func browse(_ type: String, found: @escaping @MainActor (NWEndpoint?) -> Void) -> NWBrowser {
        let b = NWBrowser(for: .bonjour(type: type, domain: nil), using: .tcp)
        b.browseResultsChangedHandler = { results, _ in
            let e = results.first?.endpoint
            Task { @MainActor in found(e) }
        }
        b.start(queue: .main)
        return b
    }

    /// Picks the TV to talk to: the cable first, then Wireless debugging.
    func refresh() async {
        guard available, !refreshing else { return }
        refreshing = true
        defer { refreshing = false }
        let devices = await Self.run(["devices", "-l"]).out
        // Relays from earlier (this app restarted, or the link dropped) linger as "offline": forget them.
        for line in devices.split(separator: "\n") where line.hasPrefix("127.0.0.1:") && line.contains(" offline") {
            _ = await Self.run(["disconnect", String(line.split(separator: " ")[0])])
        }
        let lines = devices.split(separator: "\n").filter { $0.contains(" device ") }
        if let usb = lines.first(where: { $0.contains("usb:") }) {
            set(.usb, String(usb.split(separator: " ")[0]), "USB")
            return
        }
        // Already connected over Wi-Fi: adb's own discovery ("adb-<serial>-…._adb-tls-connect._tcp"), an
        // "ip:port", or this app's relay.
        if let wifi = lines.first(where: { $0.contains("_adb-tls-connect") || $0.split(separator: " ")[0].contains(":") }) {
            set(.wifi, String(wifi.split(separator: " ")[0]), "Wi-Fi (Wireless debugging)")
            return
        }
        guard let service = connectService else {
            set(.none, nil, "Plug the TV into this Mac by USB-C, or turn on Wireless debugging on the TV.")
            return
        }
        // Relay the TV's Wireless debugging to localhost and connect adb to it.
        relay?.stop()
        guard let r = try? TCPRelay(to: service) else { return }
        relay = r
        let out = await Self.run(["connect", "127.0.0.1:\(r.port)"]).out
        if out.contains("connected") {
            try? await Task.sleep(for: .milliseconds(500))
            let again = await Self.run(["devices"]).out
            if again.contains("127.0.0.1:\(r.port)\tdevice") {
                set(.wifi, "127.0.0.1:\(r.port)", "Wi-Fi (Wireless debugging)")
                return
            }
        }
        set(.none, nil, pairingAvailable ? "Enter the pairing code shown on the TV."
            : "On the TV: Settings > System > Developer options > Wireless debugging > Pair device with pairing code.")
    }

    private func set(_ l: Link, _ s: String?, _ text: String) {
        link = l
        serial = s
        status = text
    }

    /// Pairs this Mac with the TV's Wireless debugging ("Pair device with pairing code" on the TV).
    func pair(code: String) async -> Bool {
        guard let service = pairService, let r = try? TCPRelay(to: service) else { return false }
        defer { r.stop() }
        let out = await Self.run(["pair", "127.0.0.1:\(r.port)", code], timeout: 15).out
        let ok = out.lowercased().contains("successfully paired")
        if ok {
            try? await Task.sleep(for: .seconds(1))
            relay?.stop()
            relay = nil
            await refresh()
        }
        return ok
    }

    // MARK: Running adb

    /// `adb -s <tv> args…`
    func tv(_ args: [String], timeout: TimeInterval = 60) async -> (out: String, ok: Bool) {
        guard let serial else { return ("No TV connected over adb.", false) }
        return await Self.run(["-s", serial] + args, timeout: timeout)
    }

    func shell(_ command: String, timeout: TimeInterval = 30) async -> String {
        await tv(["shell", command], timeout: timeout).out.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Raw bytes from `adb exec-out` (screenshots).
    func execOut(_ command: String) async -> Data? {
        guard let serial, let path = Self.path else { return nil }
        return await Task.detached {
            let p = Process()
            p.executableURL = URL(fileURLWithPath: path)
            p.arguments = ["-s", serial, "exec-out", command]
            let pipe = Pipe()
            p.standardOutput = pipe
            p.standardError = FileHandle.nullDevice
            guard (try? p.run()) != nil else { return nil }
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            p.waitUntilExit()
            return p.terminationStatus == 0 ? data : nil
        }.value
    }

    nonisolated static func run(_ args: [String], timeout: TimeInterval = 60) async -> (out: String, ok: Bool) {
        guard let path else { return ("adb isn't installed.", false) }
        return await Task.detached {
            let p = Process()
            p.executableURL = URL(fileURLWithPath: path)
            p.arguments = args
            let pipe = Pipe()
            p.standardOutput = pipe
            p.standardError = pipe
            guard (try? p.run()) != nil else { return ("adb couldn't start.", false) }
            let killer = DispatchWorkItem { if p.isRunning { p.terminate() } }
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout, execute: killer)
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            p.waitUntilExit()
            killer.cancel()
            return (String(decoding: data, as: UTF8.self), p.terminationStatus == 0)
        }.value
    }
}

/// Forwards a localhost port to one network endpoint (a Bonjour service), connection by connection.
final class TCPRelay: @unchecked Sendable {
    private let listener: NWListener
    private let target: NWEndpoint
    private let queue = DispatchQueue(label: "tv.adb.relay")
    private(set) var port: UInt16 = 0

    init(to target: NWEndpoint) throws {
        self.target = target
        let params = NWParameters.tcp
        params.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: params)
        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { s in
            if case .ready = s { ready.signal() }
            if case .failed = s { ready.signal() }
        }
        listener.newConnectionHandler = { [weak self] c in self?.accept(c) }
        listener.start(queue: queue)
        _ = ready.wait(timeout: .now() + 3)
        guard let p = listener.port?.rawValue, p != 0 else { throw StreamError("No local port.") }
        port = p
    }

    private func accept(_ local: NWConnection) {
        let remote = NWConnection(to: target, using: .tcp)
        local.start(queue: queue)
        remote.start(queue: queue)
        pipe(local, remote)
        pipe(remote, local)
    }

    private func pipe(_ from: NWConnection, _ to: NWConnection) {
        from.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { [weak self] data, _, done, err in
            if let data, !data.isEmpty {
                to.send(content: data, completion: .contentProcessed { _ in })
            }
            if done || err != nil {
                to.cancel()
                from.cancel()
                return
            }
            self?.pipe(from, to)
        }
    }

    func stop() {
        listener.cancel()
    }
}

struct StreamError: LocalizedError {
    let message: String
    init(_ m: String) { message = m }
    var errorDescription: String? { message }
}
