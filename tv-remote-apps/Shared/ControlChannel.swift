import Foundation
import Network

/// The live connection to the TV (port 8801): one open TCP socket with Nagle off, so a button press or a
/// mouse movement is a few bytes with no handshake. It reconnects on its own (TV asleep, restarted, moved to a
/// new address, app relaunched) and the TV pushes what's playing and when the cursor leaves its screen.
///
/// Frames are `u16 length, u8 type, payload`; the first one is HELLO with this device's token.
final class ControlChannel: @unchecked Sendable {

    enum State: Equatable { case idle, connecting, ready, denied }

    private enum Out: UInt8 {
        case hello = 0x01, key = 0x02, text = 0x03, pointerEnter = 0x04, pointerMove = 0x05
        case pointerButton = 0x06, pointerScroll = 0x07, pointerExit = 0x08, type = 0x0A, ping = 0x0F
    }

    private enum In: UInt8 {
        case welcome = 0x81, denied = 0x82, state = 0x83, pointerLeft = 0x84, pong = 0x8F
    }

    private let queue = DispatchQueue(label: "tv.control", qos: .userInteractive)
    private var connection: NWConnection?
    private var tv: TV?
    private var token: String?
    private var retry = 0.5
    private var pingTimer: DispatchSourceTimer?
    private var wanted = false

    /// Called on the main actor.
    var onState: (@MainActor (State) -> Void)?
    var onNowPlaying: (@MainActor (NowPlaying?) -> Void)?
    var onPointerLeft: (@MainActor (Double) -> Void)?
    var onWelcome: (@MainActor (_ name: String, _ id: String?) -> Void)?
    /// The connection was lost (it reconnects by itself).
    var onDisconnected: (@MainActor () -> Void)?

    private(set) var state: State = .idle {
        didSet {
            let s = state
            if s != oldValue { Task { @MainActor in self.onState?(s) } }
            if oldValue == .ready && s != .ready { Task { @MainActor in self.onDisconnected?() } }
        }
    }

    func connect(to tv: TV, token: String) {
        queue.async {
            if self.tv == tv, self.token == token, self.connection != nil { return }
            self.wanted = true
            self.tv = tv
            self.token = token
            self.retry = 0.5
            self.open()
        }
    }

    func disconnect() {
        queue.async {
            self.wanted = false
            self.teardown()
            self.state = .idle
        }
    }

    // MARK: Sending

    var isReady: Bool { state == .ready }

    func key(_ key: RemoteKey) { send(.key, Data(key.rawValue.utf8)) }
    func setText(_ text: String) { send(.text, Data(text.utf8)) }
    /// Typing: characters are added to the focused field; "\u{8}" deletes, "\n" submits.
    func type(_ chars: String) { send(.type, Data(chars.utf8)) }

    /// The Mac's cursor crossed onto the TV at `along` (0..1) of the edge facing the Mac.
    func pointerEnter(along: Double, edge: String) {
        var d = Data(u16(along))
        d.append(Data(edge.utf8))
        send(.pointerEnter, d)
    }

    func pointerMove(dx: Int, dy: Int) { send(.pointerMove, s16(dx) + s16(dy)) }
    func pointerButton(_ button: Int, down: Bool) { send(.pointerButton, Data([UInt8(button), down ? 1 : 0])) }
    /// Scrolling. `phase`: 0 a mouse-wheel click, 1 a two-finger gesture began, 2 moved, 4 ended.
    func pointerScroll(dx: Int, dy: Int, phase: UInt8) { send(.pointerScroll, s16(dx) + s16(dy) + Data([phase])) }
    func pointerExit() { send(.pointerExit, Data()) }

    private func send(_ type: Out, _ payload: Data) {
        queue.async {
            guard self.state == .ready, let c = self.connection else { return }
            var f = Data()
            f.append(UInt8(payload.count >> 8))
            f.append(UInt8(payload.count & 0xFF))
            f.append(type.rawValue)
            f.append(payload)
            c.send(content: f, completion: .contentProcessed { _ in })
        }
    }

    // MARK: Connection

    private func open() {
        teardown()
        guard wanted, let tv, let token else { return }
        state = .connecting
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 5
        tcp.connectionTimeout = 4
        let c = NWConnection(host: NWEndpoint.Host(tv.host), port: NWEndpoint.Port(integerLiteral: UInt16(tv.controlPort)),
                             using: NWParameters(tls: nil, tcp: tcp))
        connection = c
        c.stateUpdateHandler = { [weak self, weak c] s in
            guard let self, let c, c === self.connection else { return }
            switch s {
            case .ready:
                var hello = Data()
                let t = Data(token.utf8)
                hello.append(UInt8(t.count >> 8)); hello.append(UInt8(t.count & 0xFF)); hello.append(Out.hello.rawValue)
                hello.append(t)
                c.send(content: hello, completion: .contentProcessed { _ in })
                self.receive(on: c)
            case .failed, .cancelled:
                self.scheduleReconnect()
            case .waiting:
                // No route (TV off or Wi-Fi changing): keep trying.
                self.scheduleReconnect()
            default:
                break
            }
        }
        c.start(queue: queue)
    }

    private func teardown() {
        pingTimer?.cancel()
        pingTimer = nil
        let c = connection
        connection = nil
        c?.cancel()
    }

    private func scheduleReconnect() {
        guard wanted, state != .denied else { return }
        teardown()
        state = .connecting
        let delay = retry
        retry = min(retry * 2, 5)
        queue.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self, self.wanted, self.connection == nil else { return }
            self.open()
        }
    }

    private func receive(on c: NWConnection) {
        c.receive(minimumIncompleteLength: 3, maximumLength: 3) { [weak self] header, _, done, error in
            guard let self, c === self.connection else { return }
            guard let header, header.count == 3, error == nil else { self.scheduleReconnect(); return }
            let len = Int(header[header.startIndex]) << 8 | Int(header[header.startIndex + 1])
            let type = header[header.startIndex + 2]
            let handle: (Data) -> Void = { payload in
                self.handle(type, payload)
                if done { self.scheduleReconnect() } else { self.receive(on: c) }
            }
            if len == 0 { handle(Data()); return }
            c.receive(minimumIncompleteLength: len, maximumLength: len) { body, _, _, error in
                guard let body, error == nil else { self.scheduleReconnect(); return }
                handle(body)
            }
        }
    }

    private func handle(_ type: UInt8, _ payload: Data) {
        switch In(rawValue: type) {
        case .welcome:
            retry = 0.5
            state = .ready
            startPings()
            let o = (try? JSONSerialization.jsonObject(with: payload)) as? [String: Any]
            let name = o?["name"] as? String ?? ""
            let id = o?["id"] as? String
            Task { @MainActor in self.onWelcome?(name, id) }
        case .denied:
            state = .denied
            teardown()
        case .state:
            let o = (try? JSONSerialization.jsonObject(with: payload)) as? [String: Any]
            var np: NowPlaying?
            if let title = o?["title"] as? String, !title.isEmpty {
                np = NowPlaying(title: title, artist: o?["artist"] as? String ?? "", playing: o?["playing"] as? Bool ?? false)
            }
            Task { @MainActor in self.onNowPlaying?(np) }
        case .pointerLeft:
            guard payload.count >= 2 else { return }
            let v = Double(Int(payload[payload.startIndex]) << 8 | Int(payload[payload.startIndex + 1])) / 65535
            Task { @MainActor in self.onPointerLeft?(v) }
        case .pong, .none:
            break
        }
    }

    private func startPings() {
        pingTimer?.cancel()
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 10, repeating: 10)
        t.setEventHandler { [weak self] in self?.send(.ping, Data()) }
        t.resume()
        pingTimer = t
    }

    private func u16(_ v: Double) -> Data {
        let n = UInt16(max(0, min(1, v)) * 65535)
        return Data([UInt8(n >> 8), UInt8(n & 0xFF)])
    }

    private func s16(_ v: Int) -> Data {
        let n = UInt16(bitPattern: Int16(max(-32768, min(32767, v))))
        return Data([UInt8(n >> 8), UInt8(n & 0xFF)])
    }
}
