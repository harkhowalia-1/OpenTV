import Foundation

/// A TV running the launcher's servers: HTTP API (8800), live control channel (8801) and display
/// stream (8802). Over USB the same servers are reached through adb's forwarded local ports.
struct TV: Codable, Hashable, Identifiable, Sendable {
    var name: String
    var host: String
    var port: Int = 8800
    var controlPort: Int = 8801
    var displayPort: Int = 8802
    /// The TV's own stable ID (from Bonjour or /api/hello); pairing is kept per TV, not per address.
    var tvID: String?
    var usb: Bool = false
    /// For a USB route: the TV's Wi-Fi address, which older pairings are stored under.
    var homeHost: String?

    init(name: String, host: String, port: Int = 8800, controlPort: Int = 8801, displayPort: Int = 8802,
         tvID: String? = nil, usb: Bool = false) {
        self.name = name
        self.host = host
        self.port = port
        self.controlPort = controlPort
        self.displayPort = displayPort
        self.tvID = tvID
        self.usb = usb
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name)
        host = try c.decode(String.self, forKey: .host)
        port = try c.decodeIfPresent(Int.self, forKey: .port) ?? 8800
        controlPort = try c.decodeIfPresent(Int.self, forKey: .controlPort) ?? 8801
        displayPort = try c.decodeIfPresent(Int.self, forKey: .displayPort) ?? 8802
        tvID = try c.decodeIfPresent(String.self, forKey: .tvID)
        usb = try c.decodeIfPresent(Bool.self, forKey: .usb) ?? false
        homeHost = try c.decodeIfPresent(String.self, forKey: .homeHost)
    }

    var id: String { "\(host):\(port)" }
    /// What pairing is stored under: the TV itself, whichever way it's reached.
    var key: String { tvID ?? homeHost ?? host }
}

/// The buttons the TV understands (`/api/key/<name>`).
enum RemoteKey: String, CaseIterable, Sendable {
    case up, down, left, right, select, back, home, control, inputs
    case playpause, next, previous, volup, voldown, mute, power
}

struct NowPlaying: Equatable, Sendable {
    var title: String
    var artist: String
    var playing: Bool
}

enum RemoteError: Error, LocalizedError {
    case notPaired, wrongCode, unreachable

    var errorDescription: String? {
        switch self {
        case .notPaired: return "This device isn't connected to the TV yet."
        case .wrongCode: return "That code didn't match. Try again."
        case .unreachable: return "Can't reach the TV. Make sure it's on and on the same Wi-Fi."
        }
    }
}

/// The chosen TV and this device's token. Kept in the shared app group, so the Control Center
/// buttons and Siri can use them too.
enum TVStore {
    static let appGroup = "group.com.harkhowalia.tvremote"
    static let defaults = UserDefaults(suiteName: appGroup) ?? .standard

    static var tv: TV? {
        get { defaults.data(forKey: "tv").flatMap { try? JSONDecoder().decode(TV.self, from: $0) } }
        set { defaults.set(newValue.flatMap { try? JSONEncoder().encode($0) }, forKey: "tv") }
    }

    static func token(for tv: TV) -> String? {
        defaults.string(forKey: "token.\(tv.key)") ?? defaults.string(forKey: "token.\(tv.homeHost ?? tv.host)")
    }

    static func setToken(_ token: String?, for tv: TV) {
        defaults.set(token, forKey: "token.\(tv.key)")
        if tv.tvID != nil { defaults.removeObject(forKey: "token.\(tv.homeHost ?? tv.host)") }
    }
}

/// The TV's small JSON API.
struct RemoteClient: Sendable {
    let tv: TV

    private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 3
        c.timeoutIntervalForResource = 5
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    private func request(_ path: String, method: String = "POST", body: [String: Any]? = nil) -> URLRequest {
        var r = URLRequest(url: URL(string: "http://\(tv.host):\(tv.port)/api/\(path)")!)
        r.httpMethod = method
        if let token = TVStore.token(for: tv) { r.setValue(token, forHTTPHeaderField: "X-Token") }
        if let body {
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }
        return r
    }

    @discardableResult
    private func call(_ req: URLRequest) async throws -> [String: Any] {
        let data: Data, resp: URLResponse
        do {
            (data, resp) = try await Self.session.data(for: req)
        } catch {
            throw RemoteError.unreachable
        }
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if status == 401 { throw RemoteError.notPaired }
        if status == 403 { throw RemoteError.wrongCode }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    /// The TV's name, its stable ID, whether this device is paired, and whether it's a TV with inputs to
    /// switch between (HDMI, AV, Live TV), unlike a Chromecast.
    func hello() async throws -> (name: String, id: String?, paired: Bool, inputs: Bool) {
        let o = try await call(request("hello", method: "GET"))
        return (o["name"] as? String ?? tv.name, o["id"] as? String, o["paired"] as? Bool ?? false,
                o["inputs"] as? Bool ?? false)
    }

    /// "Send to TV": opens a link (YouTube, Netflix, Spotify, a video file, a web page) on the TV, at
    /// `time` seconds when known. Returns what opened it.
    @discardableResult
    func open(_ url: URL, time: Double? = nil) async throws -> String? {
        var body: [String: Any] = ["url": url.absoluteString]
        if let time { body["time"] = time }
        let o = try await call(request("open", body: body))
        return o["opened"] as? String
    }

    /// Puts a 4-digit code on the TV.
    func startPairing() async throws {
        try await call(request("pair/start"))
    }

    /// Sends the code shown on the TV and keeps the token it returns.
    func finishPairing(code: String) async throws {
        let o = try await call(request("pair/finish", body: ["code": code]))
        guard let token = o["token"] as? String else { throw RemoteError.wrongCode }
        TVStore.setToken(token, for: tv)
    }

    func press(_ key: RemoteKey) async throws {
        try await call(request("key/\(key.rawValue)"))
    }

    func type(_ text: String) async throws {
        try await call(request("text", body: ["text": text]))
    }

    func nowPlaying() async throws -> NowPlaying? {
        let o = try await call(request("state", method: "GET"))
        guard let title = o["title"] as? String, !title.isEmpty else { return nil }
        return NowPlaying(title: title, artist: o["artist"] as? String ?? "", playing: o["playing"] as? Bool ?? false)
    }

    /// Every app on the TV, for the Mac's TV Manager.
    func apps() async throws -> [TVApp] {
        let o = try await call(request("apps", method: "GET"))
        return (o["apps"] as? [[String: Any]] ?? []).compactMap { a in
            guard let id = a["id"] as? String else { return nil }
            return TVApp(id: id, name: a["name"] as? String ?? id, version: a["version"] as? String ?? "",
                         system: a["system"] as? Bool ?? false, updatedSystem: a["updatedSystem"] as? Bool ?? false,
                         enabled: a["enabled"] as? Bool ?? true, launchable: a["launchable"] as? Bool ?? false)
        }
    }

    /// An app's banner or icon (PNG).
    func icon(_ package: String) async -> Data? {
        let r = request("icon/\(package)", method: "GET")
        guard let (data, resp) = try? await Self.session.data(for: r),
              (resp as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return data
    }
}

struct TVApp: Identifiable, Hashable, Sendable {
    let id: String
    var name: String
    var version: String
    var system: Bool
    var updatedSystem: Bool
    var enabled: Bool
    var launchable: Bool
}
