import AVFoundation
import AppKit
import CoreMedia
import SwiftUI

/**
 * Set aside for now (not reachable from the menu or the Manager): kept for a later release.
 *
 * "TV on Mac": the TV's screen in a resizable window on this Mac, controlled with the trackpad, mouse and
 * keyboard, like a monitor plugged in over HDMI.
 *
 * Built on scrcpy's device server (Genymobile/scrcpy, Apache 2.0, bundled as `scrcpy-server`): it captures
 * the TV's screen and sound as shell over adb (USB-C, or Wireless debugging) and takes input back. This app
 * decodes the H.264 in the media engine straight onto the window, and plays the sound as raw PCM, so nothing
 * buffers on the way.
 *
 * Two picture modes: **Mirror** shows the TV as it is; **Fit Window** gives the TV a second screen of exactly
 * this window's size (re-made when the window settles at a new size), so it fills any Mac display natively.
 */
@MainActor
final class TVMirror: ObservableObject {

    static let shared = TVMirror()

    enum Picture: String, CaseIterable, Identifiable {
        case mirror = "Mirror TV", fit = "Fit Window"
        var id: String { rawValue }
    }

    @Published var picture: Picture = .mirror
    @Published var sound = true
    @Published private(set) var status = ""
    @Published private(set) var running = false
    @Published private(set) var videoSize = CGSize(width: 1920, height: 1080)

    let view = MirrorView()
    private var session: ScrcpySession?
    private var resizeWork: DispatchWorkItem?
    private var retries = 0

    func show() {
        let w = Windows.show("mirror", title: Services.model.route?.name ?? "TV", size: NSSize(width: 1280, height: 760)) {
            MirrorWindowContent()
        }
        w.contentAspectRatio = NSSize(width: 16, height: 9)
        w.collectionBehavior.insert(.fullScreenPrimary)
        NotificationCenter.default.addObserver(forName: NSWindow.willCloseNotification, object: w, queue: .main) { _ in
            MainActor.assumeIsolated { TVMirror.shared.stop() }
        }
        NotificationCenter.default.addObserver(forName: NSWindow.didEndLiveResizeNotification, object: w, queue: .main) { _ in
            MainActor.assumeIsolated { TVMirror.shared.windowResized() }
        }
        NotificationCenter.default.addObserver(forName: NSWindow.didEnterFullScreenNotification, object: w, queue: .main) { _ in
            MainActor.assumeIsolated { TVMirror.shared.windowResized() }
        }
        if !running { Task { await start() } }
    }

    func start() async {
        stop()
        let adb = ADB.shared
        adb.start()
        if adb.serial == nil { await adb.refresh() }
        guard let serial = adb.serial else {
            status = adb.status.isEmpty ? "Plug the TV in by USB-C, or turn on Wireless debugging." : adb.status
            return
        }
        guard let server = Bundle.main.url(forResource: "scrcpy-server", withExtension: nil) else {
            status = "The screen server is missing from the app."
            return
        }
        status = "Connecting…"
        running = true
        var size: CGSize?
        if picture == .fit, let w = Windows.window("mirror") {
            let px = w.convertToBacking(w.contentLayoutRect).size
            size = CGSize(width: Int(px.width) / 8 * 8, height: Int(px.height) / 8 * 8)
        }
        let s = ScrcpySession(serial: serial, server: server, usb: adb.link == .usb, sound: sound, newDisplay: size,
                              layer: view.renderer)
        session = s
        view.session = s
        s.onVideoSize = { [weak self] sz in
            self?.videoSize = sz
            if self?.picture == .mirror { Windows.window("mirror")?.contentAspectRatio = sz }
        }
        s.onEnded = { [weak self] reason in
            guard let self, self.session === s else { return }
            self.running = false
            self.session = nil
            self.view.session = nil
            self.status = reason ?? ""
            // A dropped link (Wi-Fi hiccup, adb reconnecting): pick the picture back up by itself.
            if self.retries < 3, Windows.window("mirror")?.isVisible == true {
                self.retries += 1
                self.status = "Reconnecting…"
                Task {
                    try? await Task.sleep(for: .seconds(1.5))
                    await ADB.shared.refresh()
                    await self.start()
                }
            }
        }
        do {
            let name = try await s.start()
            retries = 0
            status = "\(name) · \(adb.link == .usb ? "USB-C" : "Wi-Fi")"
        } catch {
            running = false
            session = nil
            status = error.localizedDescription
        }
    }

    func stop() {
        session?.stop()
        session = nil
        view.session = nil
        running = false
    }

    private func windowResized() {
        guard picture == .fit, running else { return }
        resizeWork?.cancel()
        let work = DispatchWorkItem { Task { @MainActor in await TVMirror.shared.start() } }
        resizeWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6, execute: work)
    }

    /// After a change of picture mode or sound: Mirror keeps the TV's shape, Fit Window lets any shape through.
    func restart() {
        if let w = Windows.window("mirror") {
            if picture == .mirror { w.contentAspectRatio = videoSize } else { w.contentResizeIncrements = NSSize(width: 1, height: 1) }
        }
        Task { await start() }
    }

    func key(_ code: Int32) { session?.key(code) }
}

private struct MirrorWindowContent: View {
    @ObservedObject var mirror = TVMirror.shared

    var body: some View {
        ZStack {
            Color.black
            MirrorRepresentable(view: mirror.view)
            if !mirror.running {
                VStack(spacing: 12) {
                    Text(mirror.status).foregroundStyle(.white.opacity(0.8))
                    Button("Connect") { Task { await mirror.start() } }
                }
            }
        }
        .ignoresSafeArea()
        .toolbar {
            ToolbarItemGroup {
                Button { mirror.key(4) } label: { Image(systemName: "chevron.backward") }.help("Back")
                Button { mirror.key(3) } label: { Image(systemName: "house") }.help("Home")
                Button { mirror.key(25) } label: { Image(systemName: "speaker.minus") }.help("Volume down")
                Button { mirror.key(24) } label: { Image(systemName: "speaker.plus") }.help("Volume up")
                Picker("Picture", selection: Binding(get: { mirror.picture }, set: { mirror.picture = $0; mirror.restart() })) {
                    ForEach(TVMirror.Picture.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .help("Fit Window gives the TV a second screen the size of this window")
                Toggle(isOn: Binding(get: { mirror.sound }, set: { mirror.sound = $0; mirror.restart() })) {
                    Image(systemName: mirror.sound ? "speaker.wave.2.fill" : "speaker.slash")
                }
                .help("Play the TV's sound on this Mac")
            }
        }
        .navigationSubtitle(mirror.status)
    }
}

private struct MirrorRepresentable: NSViewRepresentable {
    let view: MirrorView
    func makeNSView(context: Context) -> MirrorView { view }
    func updateNSView(_ nsView: MirrorView, context: Context) {}
}

// MARK: - The view: picture and input

final class MirrorView: NSView {

    let display = AVSampleBufferDisplayLayer()
    var renderer: AVSampleBufferDisplayLayer { display }
    weak var session: ScrcpySession?
    private var scrollX: CGFloat = 0, scrollY: CGFloat = 0

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor
        display.videoGravity = .resizeAspect
        display.backgroundColor = NSColor.black.cgColor
        layer?.addSublayer(display)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layout() {
        super.layout()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        display.frame = bounds
        CATransaction.commit()
    }

    override var acceptsFirstResponder: Bool { true }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        trackingAreas.forEach(removeTrackingArea)
        addTrackingArea(NSTrackingArea(rect: bounds, options: [.mouseMoved, .activeInKeyWindow, .inVisibleRect], owner: self))
    }

    /// Window point → TV pixel, inside the letterboxed picture.
    private func tvPoint(_ e: NSEvent) -> (Int32, Int32, CGSize)? {
        guard let s = session else { return nil }
        let size = s.frameSize
        guard size.width > 0 else { return nil }
        let p = convert(e.locationInWindow, from: nil)
        let k = min(bounds.width / size.width, bounds.height / size.height)
        let ox = (bounds.width - size.width * k) / 2, oy = (bounds.height - size.height * k) / 2
        let x = (p.x - ox) / k, y = size.height - (p.y - oy) / k
        guard x >= 0, y >= 0, x < size.width, y < size.height else { return nil }
        return (Int32(x), Int32(y), size)
    }

    private func mouse(_ action: UInt8, _ e: NSEvent, buttons: Int32, actionButton: Int32) {
        guard let (x, y, size) = tvPoint(e) else { return }
        session?.touch(action: action, x: x, y: y, size: size, actionButton: actionButton, buttons: buttons)
    }

    override func mouseDown(with e: NSEvent) {
        window?.makeFirstResponder(self)
        mouse(0, e, buttons: 1, actionButton: 1)
    }
    override func mouseDragged(with e: NSEvent) { mouse(2, e, buttons: 1, actionButton: 0) }
    override func mouseUp(with e: NSEvent) { mouse(1, e, buttons: 0, actionButton: 1) }
    override func mouseMoved(with e: NSEvent) { mouse(7, e, buttons: 0, actionButton: 0) }   // hover
    // Right click (two-finger click) is Back, as in scrcpy; the middle button is Home.
    override func rightMouseDown(with e: NSEvent) { session?.backOrScreenOn(down: true) }
    override func rightMouseUp(with e: NSEvent) { session?.backOrScreenOn(down: false) }
    override func otherMouseDown(with e: NSEvent) { session?.key(3) }

    override func scrollWheel(with e: NSEvent) {
        guard let (x, y, size) = tvPoint(e) else { return }
        // Trackpads send pixel deltas; scrcpy wants wheel "clicks" (±16).
        let k: CGFloat = e.hasPreciseScrollingDeltas ? 1 / 24 : 1
        scrollX += e.scrollingDeltaX * k
        scrollY += e.scrollingDeltaY * k
        if abs(scrollX) >= 0.05 || abs(scrollY) >= 0.05 {
            session?.scroll(x: x, y: y, size: size, h: Float(-scrollX), v: Float(scrollY))
            scrollX = 0
            scrollY = 0
        }
    }

    override func keyDown(with e: NSEvent) {
        if e.modifierFlags.contains(.command) {
            switch e.charactersIgnoringModifiers {
            case "h": session?.key(3)                     // ⌘H Home
            case "b", "[": session?.key(4)                // ⌘B / ⌘[ Back
            case "v": if let t = NSPasteboard.general.string(forType: .string) { session?.text(t) }
            default: super.keyDown(with: e)
            }
            return
        }
        if let code = Self.keycodes[e.keyCode] {
            session?.key(code, down: true, repeatCount: e.isARepeat ? 1 : 0)
            return
        }
        if let chars = e.characters, !chars.isEmpty, chars.unicodeScalars.allSatisfy({ $0.value >= 32 }) {
            session?.text(chars)
        }
    }

    override func keyUp(with e: NSEvent) {
        if let code = Self.keycodes[e.keyCode] { session?.key(code, down: false) }
    }

    /// Mac key codes → Android key codes, for keys that aren't text.
    private static let keycodes: [UInt16: Int32] = [
        126: 19, 125: 20, 123: 21, 124: 22,   // arrows
        36: 66, 76: 66, 51: 67, 117: 112, 53: 4, 48: 61,   // return, delete, forward delete, esc = back, tab
        115: 122, 119: 123, 116: 92, 121: 93,   // home, end, page up/down
    ]

    override func performKeyEquivalent(with e: NSEvent) -> Bool {
        guard window?.firstResponder === self, e.modifierFlags.contains(.command),
              ["h", "b", "[", "v"].contains(e.charactersIgnoringModifiers ?? "") else { return super.performKeyEquivalent(with: e) }
        keyDown(with: e)
        return true
    }
}

// MARK: - The scrcpy session

final class ScrcpySession: @unchecked Sendable {

    static let serverVersion = "3.3.3"
    private static let remotePath = "/data/local/tmp/otv-scrcpy-server.jar"

    let serial: String
    let server: URL
    let usb: Bool
    let sound: Bool
    let newDisplay: CGSize?
    private let layer: AVSampleBufferDisplayLayer

    var onVideoSize: (@MainActor (CGSize) -> Void)?
    var onEnded: (@MainActor (String?) -> Void)?

    private let scid = String(format: "%08x", UInt32.random(in: 1..<0x7fffffff))
    private var port = 0
    private var process: Process?
    private var video: StreamSocket?
    private var audio: StreamSocket?
    private var control: StreamSocket?
    private let lock = NSLock()
    private var _frameSize = CGSize.zero
    private var stopped = false
    private var format: CMVideoFormatDescription?
    private var sps = Data(), pps = Data()
    private let audioPlayer = PCMPlayer()

    var frameSize: CGSize {
        lock.lock(); defer { lock.unlock() }
        return _frameSize
    }

    init(serial: String, server: URL, usb: Bool, sound: Bool, newDisplay: CGSize?, layer: AVSampleBufferDisplayLayer) {
        self.serial = serial
        self.server = server
        self.usb = usb
        self.sound = sound
        self.newDisplay = newDisplay
        self.layer = layer
    }

    /// Pushes and starts the server, connects the sockets and starts streaming. Returns the TV's name.
    func start() async throws -> String {
        let adb = { (args: [String]) async in await ADB.run(["-s", self.serial] + args) }
        _ = await adb(["push", server.path, Self.remotePath])
        port = Int.random(in: 27200..<27900)
        guard await adb(["forward", "tcp:\(port)", "localabstract:scrcpy_\(scid)"]).ok else {
            throw StreamError("Couldn't open a tunnel to the TV.")
        }
        var args = [
            "CLASSPATH=\(Self.remotePath)", "app_process", "/", "com.genymobile.scrcpy.Server", Self.serverVersion,
            "scid=\(scid)", "log_level=warn", "tunnel_forward=true", "video_codec=h264",
            "video_bit_rate=\(usb ? 24_000_000 : 10_000_000)", "max_fps=60", "control=true",
            "audio=\(sound)", "audio_codec=raw", "clipboard_autosync=false", "power_off_on_close=false",
            "stay_awake=false", "cleanup=true",
        ]
        if let d = newDisplay {
            args.append("new_display=\(Int(d.width))x\(Int(d.height))/\(Int(220 * d.height / 1080))")
        }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: ADB.path ?? "/usr/bin/false")
        p.arguments = ["-s", serial, "shell", args.joined(separator: " ")]
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        p.terminationHandler = { [weak self] _ in self?.end("The TV stopped sharing its screen.") }
        try p.run()
        process = p

        // The server takes a moment to listen: the first socket gets a dummy byte once it does.
        var first: StreamSocket?
        for _ in 0..<60 {
            if let s = try? StreamSocket(host: "127.0.0.1", port: port), s.read(1) != nil { first = s; break }
            try await Task.sleep(for: .milliseconds(100))
        }
        guard let v = first else { stop(); throw StreamError("The TV's screen server didn't start.") }
        video = v
        if sound { audio = try StreamSocket(host: "127.0.0.1", port: port) }
        control = try StreamSocket(host: "127.0.0.1", port: port)

        guard let nameData = v.read(64) else { stop(); throw StreamError("Lost the TV.") }
        let name = String(decoding: nameData.prefix { $0 != 0 }, as: UTF8.self)
        guard let meta = v.read(12) else { stop(); throw StreamError("Lost the TV.") }
        setFrameSize(CGSize(width: Int(meta.u32(4)), height: Int(meta.u32(8))))

        let vt = Thread { [weak self] in self?.videoLoop(v) }
        vt.name = "tv.mirror.video"
        vt.qualityOfService = .userInteractive
        vt.start()
        if let a = audio {
            let at = Thread { [weak self] in self?.audioLoop(a) }
            at.name = "tv.mirror.audio"
            at.qualityOfService = .userInteractive
            at.start()
        }
        if let c = control {
            let ct = Thread { [weak self] in self?.controlLoop(c) }   // device messages (clipboard): drained
            ct.start()
        }
        return name.isEmpty ? "TV" : name
    }

    private func setFrameSize(_ s: CGSize) {
        lock.lock()
        let changed = s != _frameSize
        _frameSize = s
        lock.unlock()
        if changed { Task { @MainActor in self.onVideoSize?(s) } }
    }

    // MARK: Video

    private func videoLoop(_ s: StreamSocket) {
        while let head = s.read(12) {
            let ptsFlags = head.u64(0)
            let size = Int(head.u32(8))
            guard let packet = s.read(size) else { break }
            let config = ptsFlags & (1 << 63) != 0
            if config {
                parseConfig(packet)
                continue
            }
            enqueue(packet, pts: Int64(ptsFlags & 0x3FFF_FFFF_FFFF_FFFF))
        }
        end("Lost the TV's screen.")
    }

    /// SPS and PPS (Annex B) → a format description, and the picture size from the SPS.
    private func parseConfig(_ d: Data) {
        for nal in Self.nals(d) {
            guard let t = nal.first.map({ $0 & 0x1F }) else { continue }
            if t == 7 { sps = nal } else if t == 8 { pps = nal }
        }
        guard !sps.isEmpty, !pps.isEmpty else { return }
        var fmt: CMVideoFormatDescription?
        sps.withUnsafeBytes { s in
            pps.withUnsafeBytes { p in
                let ptrs = [s.bindMemory(to: UInt8.self).baseAddress!, p.bindMemory(to: UInt8.self).baseAddress!]
                let sizes = [sps.count, pps.count]
                CMVideoFormatDescriptionCreateFromH264ParameterSets(allocator: nil, parameterSetCount: 2,
                                                                    parameterSetPointers: ptrs, parameterSetSizes: sizes,
                                                                    nalUnitHeaderLength: 4, formatDescriptionOut: &fmt)
            }
        }
        guard let fmt else { return }
        format = fmt
        let dim = CMVideoFormatDescriptionGetPresentationDimensions(fmt, usePixelAspectRatio: false, useCleanAperture: true)
        setFrameSize(dim)
    }

    private func enqueue(_ annexB: Data, pts: Int64) {
        guard let format else { return }
        // Annex B → AVCC: each NAL unit prefixed by its 4-byte length.
        var avcc = Data(capacity: annexB.count + 16)
        for nal in Self.nals(annexB) {
            let t = nal.first.map { $0 & 0x1F } ?? 0
            if t == 7 || t == 8 { continue }
            var len = UInt32(nal.count).bigEndian
            avcc.append(Data(bytes: &len, count: 4))
            avcc.append(nal)
        }
        guard !avcc.isEmpty else { return }
        var block: CMBlockBuffer?
        let count = avcc.count
        let mem = UnsafeMutableRawPointer.allocate(byteCount: count, alignment: 16)
        avcc.copyBytes(to: mem.assumingMemoryBound(to: UInt8.self), count: count)
        guard CMBlockBufferCreateWithMemoryBlock(allocator: nil, memoryBlock: mem, blockLength: count,
                                                 blockAllocator: kCFAllocatorDefault, customBlockSource: nil,
                                                 offsetToData: 0, dataLength: count, flags: 0,
                                                 blockBufferOut: &block) == noErr, let block else {
            mem.deallocate()
            return
        }
        var sb: CMSampleBuffer?
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: CMTime(value: pts, timescale: 1_000_000),
                                        decodeTimeStamp: .invalid)
        var sampleSize = count
        guard CMSampleBufferCreateReady(allocator: nil, dataBuffer: block, formatDescription: format, sampleCount: 1,
                                        sampleTimingEntryCount: 1, sampleTimingArray: &timing, sampleSizeEntryCount: 1,
                                        sampleSizeArray: &sampleSize, sampleBufferOut: &sb) == noErr, let sb else { return }
        if let att = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: true), CFArrayGetCount(att) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(att, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        let r = layer.sampleBufferRenderer
        if r.status == .failed { r.flush() }
        r.enqueue(sb)
    }

    /// Splits Annex B data at its start codes.
    static func nals(_ d: Data) -> [Data] {
        var out: [Data] = []
        let b = [UInt8](d)
        var i = 0, start = -1
        while i + 3 <= b.count {
            if b[i] == 0, b[i + 1] == 0, b[i + 2] == 1 || (i + 3 < b.count && b[i + 2] == 0 && b[i + 3] == 1) {
                let codeLen = b[i + 2] == 1 ? 3 : 4
                if start >= 0 {
                    var end = i
                    while end > start, b[end - 1] == 0 { end -= 1 }
                    out.append(Data(b[start..<end]))
                }
                i += codeLen
                start = i
            } else {
                i += 1
            }
        }
        if start >= 0, start < b.count { out.append(Data(b[start...])) }
        return out
    }

    // MARK: Audio

    private func audioLoop(_ s: StreamSocket) {
        guard s.read(4) != nil else { return }   // codec id: raw
        audioPlayer.start()
        while let head = s.read(12) {
            let size = Int(head.u32(8))
            guard let packet = s.read(size) else { break }
            if head.u64(0) & (1 << 63) != 0 { continue }
            audioPlayer.play(packet)
        }
        audioPlayer.stop()
    }

    private func controlLoop(_ s: StreamSocket) {
        while s.read(1) != nil {}   // the TV's clipboard etc.: not used
    }

    // MARK: Control messages

    private func sendControl(_ d: Data) { control?.send(d, urgent: true) }

    func touch(action: UInt8, x: Int32, y: Int32, size: CGSize, actionButton: Int32, buttons: Int32) {
        var d = Data([2, action])
        d.be(UInt64.max)                           // the mouse pointer
        d.be(UInt32(bitPattern: x)); d.be(UInt32(bitPattern: y))
        d.be(UInt16(size.width)); d.be(UInt16(size.height))
        d.be(UInt16(action == 1 ? 0 : 0xFFFF))     // pressure
        d.be(UInt32(bitPattern: actionButton)); d.be(UInt32(bitPattern: buttons))
        sendControl(d)
    }

    func scroll(x: Int32, y: Int32, size: CGSize, h: Float, v: Float) {
        func fp(_ f: Float) -> UInt16 {
            let n = max(-1, min(1, f / 16))
            return UInt16(bitPattern: Int16(n >= 1 ? 0x7FFF : n * 0x8000))
        }
        var d = Data([3])
        d.be(UInt32(bitPattern: x)); d.be(UInt32(bitPattern: y))
        d.be(UInt16(size.width)); d.be(UInt16(size.height))
        d.be(fp(h)); d.be(fp(v))
        d.be(UInt32(0))
        sendControl(d)
    }

    func key(_ code: Int32, down: Bool, repeatCount: Int32 = 0) {
        var d = Data([0, down ? 0 : 1])
        d.be(UInt32(bitPattern: code)); d.be(UInt32(bitPattern: repeatCount)); d.be(UInt32(0))
        sendControl(d)
    }

    /// A full press.
    func key(_ code: Int32) {
        key(code, down: true)
        key(code, down: false)
    }

    func text(_ t: String) {
        let utf8 = Data(t.utf8.prefix(300))
        var d = Data([1])
        d.be(UInt32(utf8.count))
        d.append(utf8)
        sendControl(d)
    }

    func backOrScreenOn(down: Bool) { sendControl(Data([4, down ? 0 : 1])) }

    // MARK: Stopping

    func stop() {
        lock.lock()
        if stopped { lock.unlock(); return }
        stopped = true
        lock.unlock()
        video?.close(); audio?.close(); control?.close()
        if let p = process, p.isRunning { p.terminationHandler = nil; p.terminate() }
        audioPlayer.stop()
        let serial = serial, port = port
        Task.detached { _ = await ADB.run(["-s", serial, "forward", "--remove", "tcp:\(port)"]) }
        layer.sampleBufferRenderer.flush(removingDisplayedImage: true, completionHandler: nil)
    }

    private func end(_ reason: String?) {
        lock.lock()
        let was = stopped
        lock.unlock()
        guard !was else { return }
        stop()
        Task { @MainActor in self.onEnded?(reason) }
    }
}

/// The TV's sound (48 kHz stereo 16-bit PCM), played with about 60 ms of buffer.
final class PCMPlayer: @unchecked Sendable {
    private let engine = AVAudioEngine()
    private let node = AVAudioPlayerNode()
    private let format = AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 2)!
    private var queued = 0
    private let lock = NSLock()

    func start() {
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
        try? engine.start()
        node.play()
    }

    func play(_ pcm: Data) {
        let frames = pcm.count / 4
        guard frames > 0, let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames)) else { return }
        lock.lock()
        let backlog = queued
        lock.unlock()
        if backlog > 48_000 / 6 { return }   // over ~160 ms behind: skip to catch up
        buf.frameLength = AVAudioFrameCount(frames)
        pcm.withUnsafeBytes { raw in
            let s = raw.bindMemory(to: Int16.self)
            let l = buf.floatChannelData![0], r = buf.floatChannelData![1]
            for i in 0..<frames {
                l[i] = Float(Int16(littleEndian: s[i * 2])) / 32768
                r[i] = Float(Int16(littleEndian: s[i * 2 + 1])) / 32768
            }
        }
        lock.lock(); queued += frames; lock.unlock()
        node.scheduleBuffer(buf) { [weak self] in
            guard let self else { return }
            self.lock.lock(); self.queued -= frames; self.lock.unlock()
        }
    }

    func stop() {
        node.stop()
        engine.stop()
    }
}

private extension Data {
    func u32(_ at: Int) -> UInt32 { self[startIndex + at..<startIndex + at + 4].reduce(0) { $0 << 8 | UInt32($1) } }
    func u64(_ at: Int) -> UInt64 { self[startIndex + at..<startIndex + at + 8].reduce(0) { $0 << 8 | UInt64($1) } }
    mutating func be<T: FixedWidthInteger>(_ v: T) { Swift.withUnsafeBytes(of: v.bigEndian) { append(contentsOf: $0) } }
}
