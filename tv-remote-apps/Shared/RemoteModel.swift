import Foundation
import Network
import SwiftUI

/// Finds TVs (Bonjour "_tvremote._tcp", and on the Mac a USB-connected one), keeps the chosen TV connected
/// over the live channel, and sends buttons. Reconnects by itself after the TV sleeps or the app relaunches.
@MainActor
final class RemoteModel: ObservableObject {

    enum Phase: Equatable {
        case searching          // no TV chosen yet
        case pairing            // TV chosen, waiting for the code
        case connected
        case offline            // TV chosen and paired but not answering (asleep, off, other network)
    }

    @Published private(set) var found: [TV] = []
    @Published private(set) var tv: TV? = TVStore.tv
    @Published private(set) var phase: Phase = .searching
    @Published private(set) var nowPlaying: NowPlaying?
    /// The TV has HDMI or other inputs (not a Chromecast): the remote shows an Inputs button.
    @Published private(set) var hasInputs = UserDefaults.standard.bool(forKey: "tvHasInputs") {
        didSet { UserDefaults.standard.set(hasInputs, forKey: "tvHasInputs") }
    }
    @Published var error: String?

    /// The live connection; the Mac's cursor bridge uses it too.
    let channel = ControlChannel()
    /// A TV reached over USB (Mac only), preferred over Wi-Fi while plugged in.
    @Published var usbTV: TV? {
        didSet { if usbTV != oldValue { reconnect() } }
    }

    private var browser: NWBrowser?
    private var resolving: [NWBrowser.Result: NWConnection] = [:]
    private var active = false

    init() {
        if let tv { phase = TVStore.token(for: tv) == nil ? .pairing : .offline }
        channel.onState = { [weak self] s in self?.channelState(s) }
        channel.onNowPlaying = { [weak self] np in self?.nowPlaying = np }
        channel.onWelcome = { [weak self] name, id in self?.welcomed(name: name, id: id) }
    }

    /// The TV as currently reachable: over USB when plugged in, otherwise over Wi-Fi.
    var route: TV? {
        guard var t = tv else { return nil }
        if let u = usbTV, u.tvID == nil || u.tvID == t.tvID || t.tvID == nil {
            t.homeHost = t.host
            t.host = u.host
            t.port = u.port
            t.controlPort = u.controlPort
            t.displayPort = u.displayPort
            t.usb = true
        }
        return t
    }

    // MARK: Lifecycle

    /// Call when the app (or the Mac's menu) becomes active. The connection stays up in between on the Mac.
    func activate() {
        active = true
        startBrowsing()
        Task { await refresh() }
    }

    /// Call when the remote is hidden for good (iPhone app in the background).
    func deactivate() {
        active = false
        browser?.cancel()
        browser = nil
        #if os(iOS)
        channel.disconnect()
        #endif
    }

    private func reconnect() {
        guard let route, let token = TVStore.token(for: route) else {
            channel.disconnect()
            return
        }
        channel.disconnect()
        channel.connect(to: route, token: token)
    }

    private func refresh() async {
        guard let route else { phase = .searching; return }
        do {
            let hello = try await RemoteClient(tv: route).hello()
            if hasInputs != hello.inputs { hasInputs = hello.inputs }
            if var t = tv, hello.name != t.name || (hello.id != nil && hello.id != t.tvID) {
                let token = TVStore.token(for: t)
                t.name = hello.name
                t.tvID = hello.id ?? t.tvID
                update(t)
                if let token, TVStore.token(for: t) == nil { TVStore.setToken(token, for: t) }
            }
            if hello.paired {
                if !channel.isReady { reconnect() }
            } else {
                phase = .pairing
                channel.disconnect()
            }
        } catch {
            // Not answering now: the channel keeps retrying and flips to connected when the TV is back.
            if TVStore.token(for: route) == nil { phase = .pairing } else { phase = .offline; reconnect() }
        }
    }

    private func channelState(_ s: ControlChannel.State) {
        switch s {
        case .ready: phase = .connected
        case .denied:
            if let tv { TVStore.setToken(nil, for: tv) }
            phase = .pairing
        case .connecting, .idle:
            if phase == .connected { phase = .offline }
        }
    }

    private func welcomed(name: String, id: String?) {
        guard var t = tv else { return }
        if t.name != name || (id != nil && t.tvID != id) {
            let token = TVStore.token(for: t)
            if !name.isEmpty { t.name = name }
            t.tvID = id ?? t.tvID
            update(t)
            if let token { TVStore.setToken(token, for: t) }
        }
    }

    // MARK: Choosing and pairing

    func choose(_ tv: TV) {
        hasInputs = false
        update(tv)
        phase = TVStore.token(for: tv) == nil ? .pairing : .offline
        reconnect()
        Task { await refresh() }
    }

    func forget() {
        if let tv { TVStore.setToken(nil, for: tv) }
        channel.disconnect()
        TVStore.tv = nil
        tv = nil
        nowPlaying = nil
        hasInputs = false
        phase = .searching
        startBrowsing()
    }

    /// Adds a TV by its address, for networks that block Bonjour.
    func add(host: String) {
        let clean = host.trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: "http://", with: "")
            .split(separator: "/").first.map(String.init) ?? host
        let parts = clean.split(separator: ":")
        choose(TV(name: "TV", host: String(parts.first ?? ""), port: parts.count > 1 ? Int(parts[1]) ?? 8800 : 8800))
    }

    func showCode() async {
        guard let route else { return }
        do {
            try await RemoteClient(tv: route).startPairing()
        } catch {
            self.error = error.localizedDescription
        }
    }

    func pair(code: String) async -> Bool {
        guard let route, var tv else { return false }
        do {
            try await RemoteClient(tv: route).finishPairing(code: code)
            // Store the token under the TV's ID as well as the route it was made on.
            if let token = TVStore.token(for: route) {
                if let id = try? await RemoteClient(tv: route).hello().id { tv.tvID = id; update(tv) }
                TVStore.setToken(token, for: tv)
            }
            reconnect()
            return true
        } catch {
            self.error = error.localizedDescription
            return false
        }
    }

    private func update(_ tv: TV) {
        self.tv = tv
        TVStore.tv = tv
    }

    // MARK: Buttons

    func press(_ key: RemoteKey) {
        Haptics.tap()
        if channel.isReady {
            channel.key(key)
            if key == .playpause, var np = nowPlaying {
                np.playing.toggle()
                nowPlaying = np
            }
            return
        }
        // Not connected yet (TV waking up): fall back to a one-off request.
        guard let route else { return }
        Task {
            do {
                try await RemoteClient(tv: route).press(key)
            } catch RemoteError.notPaired {
                phase = .pairing
            } catch {
                phase = .offline
            }
        }
    }

    func type(_ text: String) {
        if channel.isReady { channel.setText(text); return }
        guard let route else { return }
        Task { try? await RemoteClient(tv: route).type(text) }
    }

    /// "Send to TV" for a link.
    func send(_ url: URL, time: Double? = nil) async -> String? {
        guard let route else { return nil }
        return try? await RemoteClient(tv: route).open(url, time: time)
    }

    // MARK: Bonjour

    private func startBrowsing() {
        guard browser == nil else { return }
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: "_tvremote._tcp", domain: nil), using: .tcp)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            Task { @MainActor in self?.resolve(results) }
        }
        b.start(queue: .main)
        browser = b
    }

    /// Bonjour gives a service name; a short connection gives its IPv4 address, which the API calls use.
    private func resolve(_ results: Set<NWBrowser.Result>) {
        for r in results where resolving[r] == nil {
            var name = "TV"
            var id: String?
            if case let .bonjour(txt) = r.metadata {
                if let n = txt["name"] { name = n }
                id = txt["id"]
            } else if case let .service(service, _, _, _) = r.endpoint {
                name = service
            }
            let params = NWParameters.tcp
            if let ip = params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options { ip.version = .v4 }
            let c = NWConnection(to: r.endpoint, using: params)
            resolving[r] = c
            c.stateUpdateHandler = { [weak self] state in
                guard case .ready = state else {
                    if case .failed = state { c.cancel() }
                    return
                }
                if case let .hostPort(host, port) = c.currentPath?.remoteEndpoint {
                    var h = "\(host)"
                    if let pct = h.firstIndex(of: "%") { h = String(h[..<pct]) }
                    let tv = TV(name: name, host: h, port: Int(port.rawValue), tvID: id)
                    Task { @MainActor in self?.foundTV(tv) }
                }
                c.cancel()
            }
            c.start(queue: .main)
        }
    }

    private func foundTV(_ found: TV) {
        self.found.removeAll { $0.host == found.host || ($0.tvID != nil && $0.tvID == found.tvID) }
        self.found.append(found)
        // The TV's address can change (DHCP); follow it by its ID (or name for older TVs).
        if var current = tv, current.host != found.host,
           (found.tvID != nil && found.tvID == current.tvID) || (current.tvID == nil && current.name == found.name) {
            let token = TVStore.token(for: current)
            current.host = found.host
            current.tvID = found.tvID ?? current.tvID
            update(current)
            if let token { TVStore.setToken(token, for: current) }
            reconnect()
        }
        // First run with one TV on the network: pick it.
        if tv == nil && self.found.count == 1 { choose(found) }
    }
}

enum Haptics {
    static func tap() {
        #if os(iOS)
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        #endif
    }
}
