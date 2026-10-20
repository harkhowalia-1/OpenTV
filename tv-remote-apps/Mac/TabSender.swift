import AppKit
import ApplicationServices
import Network

/// "Send to TV": whatever is playing or open on the Mac carries on on the TV, and the Mac is free again.
///
/// - **Browsers**: the front tab of Safari, Chrome, Edge, Brave, Arc, Vivaldi, Opera or any Chromium browser
///   (Apple Events), and of Firefox or any other browser (read from its address bar with Accessibility).
///   A YouTube video passes its current time and pauses here (needs the browser's "Allow JavaScript from
///   Apple Events"; without it the video starts from the top).
/// - **QuickTime Player** and **VLC**: the open file is served to the TV from this Mac and starts at the
///   same moment; the Mac pauses.
///
/// The app used most recently wins, so it works from the menu bar whichever window is in front.
@MainActor
enum TabSender {

    struct Item {
        var url: URL
        var time: Double?
        var source: String
    }

    private static let chromium: Set<String> = [
        "com.google.Chrome", "com.google.Chrome.beta", "com.google.Chrome.canary", "org.chromium.Chromium",
        "com.microsoft.edgemac", "com.microsoft.edgemac.Beta", "com.microsoft.edgemac.Dev",
        "com.brave.Browser", "com.vivaldi.Vivaldi", "com.operasoftware.Opera", "company.thebrowser.Browser",
    ]
    private static let safari: Set<String> = ["com.apple.Safari", "com.apple.SafariTechnologyPreview"]
    private static let otherBrowsers: Set<String> = [
        "org.mozilla.firefox", "org.mozilla.firefoxdeveloperedition", "org.mozilla.nightly", "app.zen-browser.zen",
        "com.kagi.kagimacOS", "org.torproject.torbrowser",
    ]
    private static let players: Set<String> = ["com.apple.QuickTimePlayerX", "org.videolan.vlc"]

    /// Apps in the order they were last active (most recent first), excluding this one.
    private static var recent: [String] = []
    private static var watching = false

    static func startWatching() {
        guard !watching else { return }
        watching = true
        if let id = NSWorkspace.shared.frontmostApplication?.bundleIdentifier { recent = [id] }
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didActivateApplicationNotification,
                                                          object: nil, queue: .main) { note in
            guard let app = note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication,
                  let id = app.bundleIdentifier, id != Bundle.main.bundleIdentifier else { return }
            MainActor.assumeIsolated {
                recent.removeAll { $0 == id }
                recent.insert(id, at: 0)
                if recent.count > 20 { recent.removeLast() }
            }
        }
    }

    /// What to send: the most recently used browser tab or media player that has something open.
    static func current() -> Item? {
        let running = Dictionary(NSWorkspace.shared.runningApplications.compactMap { app in
            app.bundleIdentifier.map { ($0, app) }
        }, uniquingKeysWith: { a, _ in a })
        let known = chromium.union(safari).union(otherBrowsers).union(players)
        var order = recent.filter { known.contains($0) && running[$0] != nil }
        for id in known where running[id] != nil && !order.contains(id) { order.append(id) }
        for id in order {
            guard let app = running[id] else { continue }
            if let item = item(from: app) { return item }
        }
        return nil
    }

    private static func item(from app: NSRunningApplication) -> Item? {
        let id = app.bundleIdentifier ?? ""
        let name = app.localizedName ?? id
        if players.contains(id) { return mediaFile(from: app) }
        var url: URL?
        if safari.contains(id) {
            url = run("tell application id \"\(id)\" to get URL of current tab of front window").flatMap(URL.init)
        } else if chromium.contains(id) {
            url = run("tell application id \"\(id)\" to get URL of active tab of front window").flatMap(URL.init)
        }
        if url == nil { url = addressBar(of: app) }
        guard var url else { return nil }
        if url.isFileURL { return serve(url, time: nil, source: name) }
        if url.scheme == nil, let fixed = URL(string: "https://" + url.absoluteString) { url = fixed }
        guard url.scheme?.hasPrefix("http") == true else { return nil }
        var time: Double?
        if url.host?.contains("youtube.com") == true || url.host == "youtu.be" {
            // The current time, then pause here.
            let js = "(function(){var v=document.querySelector('video');if(!v)return '';v.pause();return String(v.currentTime);})()"
            if safari.contains(id) {
                time = run("tell application id \"\(id)\" to do JavaScript \"\(js)\" in current tab of front window").flatMap(Double.init)
            } else if chromium.contains(id) {
                time = run("tell application id \"\(id)\" to execute active tab of front window javascript \"\(js)\"").flatMap(Double.init)
            }
        }
        return Item(url: url, time: time, source: name)
    }

    /// QuickTime Player or VLC: the open file and where it's up to, then pause it here.
    private static func mediaFile(from app: NSRunningApplication) -> Item? {
        let id = app.bundleIdentifier ?? ""
        var path: String?
        var time: Double?
        if id == "com.apple.QuickTimePlayerX" {
            path = run("tell application id \"\(id)\" to get POSIX path of (file of front document as alias)")
            time = run("tell application id \"\(id)\" to get current time of front document").flatMap(Double.init)
            _ = run("tell application id \"\(id)\" to pause front document")
        } else {
            path = run("tell application id \"\(id)\" to get path of current item")
            time = run("tell application id \"\(id)\" to get current time").flatMap(Double.init)
            if run("tell application id \"\(id)\" to get playing") == "true" {
                _ = run("tell application id \"\(id)\" to play")   // VLC's play toggles pause
            }
        }
        guard let path, !path.isEmpty else { return nil }
        return serve(URL(fileURLWithPath: path), time: time, source: app.localizedName ?? "Player")
    }

    private static func serve(_ file: URL, time: Double?, source: String) -> Item? {
        guard let url = LocalMediaServer.shared.share(file) else { return nil }
        return Item(url: url, time: time, source: source)
    }

    /// Any other browser: the URL in its address bar, read with Accessibility.
    private static func addressBar(of app: NSRunningApplication) -> URL? {
        guard AXIsProcessTrusted() else { return nil }
        let ax = AXUIElementCreateApplication(app.processIdentifier)
        var window: CFTypeRef?
        guard AXUIElementCopyAttributeValue(ax, kAXFocusedWindowAttribute as CFString, &window) == .success
                || AXUIElementCopyAttributeValue(ax, kAXMainWindowAttribute as CFString, &window) == .success,
              let window else { return nil }
        var found: URL?
        func search(_ el: AXUIElement, depth: Int) {
            guard found == nil, depth < 14 else { return }
            var role: CFTypeRef?
            AXUIElementCopyAttributeValue(el, kAXRoleAttribute as CFString, &role)
            if let r = role as? String, r == kAXTextFieldRole as String || r == kAXComboBoxRole as String {
                var value: CFTypeRef?
                AXUIElementCopyAttributeValue(el, kAXValueAttribute as CFString, &value)
                if let v = value as? String, looksLikeURL(v) {
                    found = URL(string: v.contains("://") ? v : "https://" + v)
                    return
                }
            }
            var children: CFTypeRef?
            AXUIElementCopyAttributeValue(el, kAXChildrenAttribute as CFString, &children)
            for c in (children as? [AXUIElement]) ?? [] { search(c, depth: depth + 1) }
        }
        search(window as! AXUIElement, depth: 0)
        return found
    }

    private static func looksLikeURL(_ s: String) -> Bool {
        let t = s.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty, !t.contains(" ") else { return false }
        return t.hasPrefix("http://") || t.hasPrefix("https://") || t.hasPrefix("file://")
            || (t.contains(".") && t.split(separator: "/").first?.contains(".") == true)
    }

    private static func run(_ source: String) -> String? {
        var error: NSDictionary?
        let result = NSAppleScript(source: source)?.executeAndReturnError(&error)
        guard error == nil, let s = result?.stringValue, !s.isEmpty else { return nil }
        return s
    }
}

/// Serves files from this Mac to the TV (HTTP with byte ranges, so the TV can seek), each under a random
/// path that only the TV is given. Uses the Mac's address on the TV's network.
final class LocalMediaServer: @unchecked Sendable {
    static let shared = LocalMediaServer()

    private let queue = DispatchQueue(label: "tv.media-server")
    private var listener: NWListener?
    private var files: [String: URL] = [:]
    private var port: UInt16 = 0

    /// A URL the TV can open for this file, or nil if the Mac isn't on a network.
    func share(_ file: URL) -> URL? {
        guard FileManager.default.isReadableFile(atPath: file.path) else { return nil }
        startIfNeeded()
        guard port != 0, let ip = Self.lanAddress() else { return nil }
        let key = UUID().uuidString.lowercased()
        queue.sync { files[key] = file }
        let name = file.lastPathComponent.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? "video.mp4"
        return URL(string: "http://\(ip):\(port)/\(key)/\(name)")
    }

    private func startIfNeeded() {
        guard listener == nil else { return }
        guard let l = try? NWListener(using: .tcp, on: .any) else { return }
        let ready = DispatchSemaphore(value: 0)
        l.stateUpdateHandler = { [weak self] s in
            if case .ready = s { self?.port = l.port?.rawValue ?? 0; ready.signal() }
            if case .failed = s { ready.signal() }
        }
        l.newConnectionHandler = { [weak self] c in self?.handle(c) }
        l.start(queue: queue)
        listener = l
        _ = ready.wait(timeout: .now() + 2)
    }

    private func handle(_ c: NWConnection) {
        c.start(queue: queue)
        c.receive(minimumIncompleteLength: 1, maximumLength: 16 * 1024) { [weak self] data, _, _, _ in
            guard let self, let data, let request = String(data: data, encoding: .utf8) else { c.cancel(); return }
            let lines = request.components(separatedBy: "\r\n")
            let parts = lines.first?.split(separator: " ") ?? []
            guard parts.count >= 2 else { c.cancel(); return }
            let key = parts[1].split(separator: "/").first.map(String.init) ?? ""
            guard let file = self.files[key], let handle = try? FileHandle(forReadingFrom: file) else {
                self.respond(c, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", body: nil)
                return
            }
            let size = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? Int) ?? 0
            var start = 0, end = size - 1, partial = false
            if let range = lines.first(where: { $0.lowercased().hasPrefix("range:") }),
               let spec = range.split(separator: "=").last {
                let bounds = spec.split(separator: "-", omittingEmptySubsequences: false)
                start = Int(bounds.first ?? "0") ?? 0
                if bounds.count > 1, let e = Int(bounds[1]) { end = min(e, size - 1) }
                partial = true
            }
            let length = max(0, end - start + 1)
            let type = Self.mime(file.pathExtension)
            var head = partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n"
            head += "Content-Type: \(type)\r\nContent-Length: \(length)\r\nAccept-Ranges: bytes\r\n"
            if partial { head += "Content-Range: bytes \(start)-\(end)/\(size)\r\n" }
            head += "Connection: close\r\n\r\n"
            if parts[0] == "HEAD" { self.respond(c, head, body: nil); return }
            c.send(content: Data(head.utf8), completion: .contentProcessed { _ in })
            try? handle.seek(toOffset: UInt64(start))
            self.stream(handle, remaining: length, to: c)
        }
    }

    /// Sends the file in 1 MB pieces, each after the last has gone out.
    private func stream(_ handle: FileHandle, remaining: Int, to c: NWConnection) {
        guard remaining > 0, let chunk = try? handle.read(upToCount: min(remaining, 1 << 20)), !chunk.isEmpty else {
            try? handle.close()
            c.send(content: nil, isComplete: true, completion: .contentProcessed { _ in c.cancel() })
            return
        }
        c.send(content: chunk, completion: .contentProcessed { [weak self] error in
            guard error == nil else { try? handle.close(); c.cancel(); return }
            self?.stream(handle, remaining: remaining - chunk.count, to: c)
        })
    }

    private func respond(_ c: NWConnection, _ head: String, body: Data?) {
        var d = Data(head.utf8)
        if let body { d.append(body) }
        c.send(content: d, isComplete: true, completion: .contentProcessed { _ in c.cancel() })
    }

    private static func mime(_ ext: String) -> String {
        switch ext.lowercased() {
        case "mp4", "m4v": return "video/mp4"
        case "mov": return "video/quicktime"
        case "mkv": return "video/x-matroska"
        case "webm": return "video/webm"
        case "mp3": return "audio/mpeg"
        case "m4a", "aac": return "audio/mp4"
        default: return "application/octet-stream"
        }
    }

    /// This Mac's IPv4 address on Wi-Fi or Ethernet (the network the TV is on).
    static func lanAddress() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }
        var best: String?
        for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = p.pointee
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
                  (Int32(ifa.ifa_flags) & IFF_UP) != 0, (Int32(ifa.ifa_flags) & IFF_LOOPBACK) == 0 else { continue }
            let name = String(cString: ifa.ifa_name)
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            let ip = String(cString: host)
            if ip.hasPrefix("169.254.") { continue }
            if name == "en0" { return ip }
            if best == nil, name.hasPrefix("en") { best = ip }
        }
        return best
    }
}
