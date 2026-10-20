import AppKit
import CoreMedia
import ScreenCaptureKit
import VideoToolbox

/// "Use TV as Display": streams a screen to the TV with as little delay as the hardware allows.
///
/// - **Extend** adds a real extra display (CoreGraphics' virtual display) sized to the TV's own resolution
///   and refresh rate, placed above this Mac's screen, so the cursor and windows simply move up onto the TV.
/// - **Mirror** shows this Mac's main screen.
/// - **iPhone** shows the iPhone Mirroring window; the TV remote then drives it.
///
/// Frames come from ScreenCaptureKit and go straight into the Apple silicon media engine with VideoToolbox's
/// low-latency rate control (no B-frames, no lookahead), then out over a Nagle-free TCP socket (USB when the
/// Chromecast is plugged in). The TV decodes in hardware and shows each picture as soon as it's decoded.
@MainActor
final class DisplayStreamer: ObservableObject {

    enum Mode: String, CaseIterable, Identifiable {
        case extend = "Extend", mirror = "Mirror", iPhone = "iPhone"
        var id: String { rawValue }
    }

    @Published var mode: Mode = .extend
    @Published var sound = true
    @Published private(set) var running = false
    @Published private(set) var status = ""

    private weak var model: RemoteModel?
    private var session: StreamSession?
    private var virtualDisplay: CGVirtualDisplay?
    private var capturedDisplay: CGDirectDisplayID?
    private var cursorTimer: DispatchSourceTimer?
    private var lastCursor: (x: Int, y: Int, visible: Bool) = (-1, -1, false)
    private var cursorLook = ""
    private var cursorTick = 0

    init(model: RemoteModel) {
        self.model = model
    }

    func toggle() {
        if running { stop() } else { Task { await start() } }
    }

    func start() async {
        guard !running, let model, let route = model.route, let token = TVStore.token(for: route) else {
            status = "Connect to the TV first."
            return
        }
        // Ask for Screen Recording before touching the TV, so it never waits on a blank screen.
        guard CGPreflightScreenCaptureAccess() else {
            CGRequestScreenCaptureAccess()
            status = "Allow OpenTV Companion in Privacy & Security > Screen Recording, then press Start."
            return
        }
        running = true
        status = "Connecting…"
        let s = StreamSession(tv: route, token: token, mode: mode, sound: sound)
        session = s
        s.onStatus = { [weak self] text in self?.status = text }
        s.onEnded = { [weak self] reason in self?.ended(reason) }
        do {
            let info = try await s.connect()
            let filter = try await makeFilter(info: info)
            try await s.capture(filter: filter, info: info)
            PointerBridge.shared.suspended = true
            startCursor()
            status = "\(info.height)p\(info.refresh) on \(info.name)" + (route.usb ? " · USB" : "")
        } catch {
            ended(error.localizedDescription)
        }
    }

    func stop() {
        session?.stop(sendBye: true)
        ended(nil)
    }

    private func ended(_ reason: String?) {
        session = nil
        running = false
        cursorTimer?.cancel()
        cursorTimer = nil
        capturedDisplay = nil
        cursorLook = ""
        lastCursor = (-1, -1, false)
        PointerBridge.shared.suspended = false
        virtualDisplay = nil          // releasing it removes the display
        status = reason ?? ""
    }

    // MARK: Cursor

    /**
     * The TV draws the cursor itself (like Parsec and Moonlight do), from its position sent at 250 Hz ahead of
     * any video. So it moves as fast as the network carries a few bytes, even while a picture is on its way.
     */
    private func startCursor() {
        guard capturedDisplay != nil, session?.info?.drawsCursor == true else { return }
        let t = DispatchSource.makeTimerSource(queue: .main)
        t.schedule(deadline: .now(), repeating: .milliseconds(4), leeway: .milliseconds(1))
        t.setEventHandler { [weak self] in MainActor.assumeIsolated { self?.trackCursor() } }
        t.resume()
        cursorTimer = t
    }

    private func trackCursor() {
        guard let s = session, let id = capturedDisplay, let info = s.info else { return }
        let loc = NSEvent.mouseLocation
        let p = CGPoint(x: loc.x, y: CGDisplayBounds(CGMainDisplayID()).height - loc.y)   // to top-left coordinates
        let b = CGDisplayBounds(id)
        // Where the captured screen sits inside the video (letterboxed when the shapes differ).
        let scale = min(CGFloat(info.width) / b.width, CGFloat(info.height) / b.height)
        let ox = (CGFloat(info.width) - b.width * scale) / 2, oy = (CGFloat(info.height) - b.height * scale) / 2
        let visible = b.contains(p)
        let x = Int(ox + (p.x - b.minX) * scale), y = Int(oy + (p.y - b.minY) * scale)
        if visible != lastCursor.visible || (visible && (x != lastCursor.x || y != lastCursor.y)) {
            lastCursor = (x, y, visible)
            s.sendCursor(x: x, y: y, visible: visible)
        }
        cursorTick += 1
        if visible && cursorTick % 12 == 0 { sendCursorLook(s, scale: scale) }
    }

    /// The cursor's current shape (arrow, I-beam, hand…), sent again only when it changes.
    private func sendCursorLook(_ s: StreamSession, scale: CGFloat) {
        guard let cursor = NSCursor.currentSystem, let tiff = cursor.image.tiffRepresentation else { return }
        let look = "\(cursor.hotSpot) \(cursor.image.size) \(tiff.count) \(tiff.hashValue) \(scale)"
        guard look != cursorLook else { return }
        cursorLook = look
        let size = cursor.image.size
        let w = max(1, Int((size.width * scale).rounded())), h = max(1, Int((size.height * scale).rounded()))
        guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: w, pixelsHigh: h, bitsPerSample: 8,
                                         samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                                         bytesPerRow: 0, bitsPerPixel: 0) else { return }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
        cursor.image.draw(in: NSRect(x: 0, y: 0, width: w, height: h))
        NSGraphicsContext.restoreGraphicsState()
        guard let png = rep.representation(using: .png, properties: [:]) else { return }
        s.sendCursorImage(png: png, hotX: Int(cursor.hotSpot.x * scale), hotY: Int(cursor.hotSpot.y * scale))
    }

    // MARK: What to capture

    private func makeFilter(info: StreamSession.TVInfo) async throws -> SCContentFilter {
        switch mode {
        case .extend:
            let id = try await createVirtualDisplay(info: info)
            // The new display takes a moment to come online. CoreGraphics caches this app's display list, so it
            // has to be enumerated again (with the run loop turning) before capture can see the new display.
            for _ in 0..<50 {
                _ = Self.activeDisplays()
                let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
                if let d = content.displays.first(where: { $0.displayID == id }) {
                    capturedDisplay = id
                    return SCContentFilter(display: d, excludingWindows: [])
                }
                try await Task.sleep(for: .milliseconds(100))
            }
            throw StreamError("The TV display didn't appear.")
        case .mirror:
            let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
            guard let d = content.displays.first(where: { $0.displayID == CGMainDisplayID() }) ?? content.displays.first else {
                throw StreamError("No screen to mirror.")
            }
            capturedDisplay = d.displayID
            return SCContentFilter(display: d, excludingWindows: [])
        case .iPhone:
            let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
            guard let w = content.windows.first(where: {
                $0.owningApplication?.bundleIdentifier == "com.apple.ScreenContinuity" && $0.frame.width > 100
            }) else {
                throw StreamError("Open iPhone Mirroring on this Mac first.")
            }
            NSRunningApplication.runningApplications(withBundleIdentifier: "com.apple.ScreenContinuity").first?
                .activate()
            InputInjector.shared.iPhoneWindow = w.frame
            return SCContentFilter(desktopIndependentWindow: w)
        }
    }

    private static func activeDisplays() -> [CGDirectDisplayID] {
        var n: UInt32 = 0
        CGGetActiveDisplayList(0, nil, &n)
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(n))
        CGGetActiveDisplayList(n, &ids, &n)
        return Array(ids.prefix(Int(n)))
    }

    private let displayQueue = DispatchQueue(label: "tv.virtual-display")

    /// Puts the TV display above the main screen, centred, matching "move the cursor up onto the TV". macOS
    /// first restores wherever this display was last time, so this is applied again once it has settled.
    private func placeAbove(_ id: CGDirectDisplayID) {
        for delay in [0.0, 0.6, 1.5] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                guard CGDisplayIsOnline(id) != 0 else { return }
                let main = CGDisplayBounds(CGMainDisplayID()), b = CGDisplayBounds(id)
                let x = Int32(main.midX - b.width / 2), y = Int32(main.minY - b.height)
                if Int32(b.minX) == x && Int32(b.minY) == y { return }
                var config: CGDisplayConfigRef?
                if CGBeginDisplayConfiguration(&config) == .success, let config {
                    CGConfigureDisplayOrigin(config, id, x, y)
                    CGCompleteDisplayConfiguration(config, .forSession)
                }
            }
        }
    }

    /**
     * An extra display with the TV's exact resolution and refresh rate, arranged above the main screen.
     *
     * macOS remembers each virtual display's setup by its identity (vendor, product, serial). If that saved
     * setup goes bad the display is created but never comes online, so when that happens this retries under a
     * fresh serial number and keeps the one that works.
     */
    private func createVirtualDisplay(info: StreamSession.TVInfo) async throws -> CGDirectDisplayID {
        let defaults = UserDefaults.standard
        var serial = UInt32(defaults.integer(forKey: "virtualDisplaySerial"))
        if serial == 0 { serial = 2 }
        for _ in 0..<4 {
            let desc = CGVirtualDisplayDescriptor()
            desc.queue = displayQueue
            desc.name = info.name
            desc.maxPixelsWide = UInt32(info.width)
            desc.maxPixelsHigh = UInt32(info.height)
            desc.sizeInMillimeters = CGSize(width: 1210, height: 680)   // a 55-inch TV, for sensible text size
            desc.productID = 0x5456
            desc.vendorID = 0x7476
            desc.serialNum = serial
            guard let display = CGVirtualDisplay(descriptor: desc) else { throw StreamError("Couldn't create the TV display.") }
            let settings = CGVirtualDisplaySettings()
            settings.hiDPI = 0
            settings.modes = [
                CGVirtualDisplayMode(width: UInt(info.width), height: UInt(info.height), refreshRate: CGFloat(info.refresh)),
                CGVirtualDisplayMode(width: UInt(info.width) * 2 / 3, height: UInt(info.height) * 2 / 3,
                                     refreshRate: CGFloat(info.refresh)),   // larger text
            ]
            guard display.apply(settings) else { throw StreamError("Couldn't create the TV display.") }
            let id = display.displayID
            for _ in 0..<30 where CGDisplayIsOnline(id) == 0 {
                try await Task.sleep(for: .milliseconds(50))
            }
            if CGDisplayIsOnline(id) != 0 {
                defaults.set(Int(serial), forKey: "virtualDisplaySerial")
                virtualDisplay = display
                placeAbove(id)
                return id
            }
            serial += 1   // this identity's saved setup is broken: try a fresh one
        }
        throw StreamError("macOS didn't bring the TV display online.")
    }
}


// MARK: - One streaming session

/**
 * Capture → encode → send for one stream, on its own queues (not the main thread).
 *
 * Latency is kept down the way Sunshine and Parsec do it: a picture is only encoded when the network has room
 * for it. When Wi-Fi slows, new pictures wait (only the newest is kept) instead of old ones queueing up, so what
 * the TV shows is never more than about a frame behind, and nothing has to be thrown away mid-stream (which
 * would need a costly fresh keyframe). The bitrate follows what the link actually carries.
 */
final class StreamSession: NSObject, SCStreamOutput, SCStreamDelegate, @unchecked Sendable {

    struct TVInfo {
        var name: String
        var width: Int
        var height: Int
        var refresh: Int
        var hevc: Bool
        var drawsCursor: Bool
    }

    private enum Out: UInt8 {
        case hello = 0x01, videoConfig = 0x10, videoFrame = 0x11, audioConfig = 0x20, audioPCM = 0x21
        case cursorPosition = 0x30, cursorImage = 0x31, bye = 0x40
    }
    private enum In: UInt8 { case welcome = 0x81, denied = 0x82, key = 0x50, needKeyframe = 0x51, stopped = 0x52 }

    let tv: TV
    let token: String
    let mode: DisplayStreamer.Mode
    let sound: Bool

    var onStatus: (@MainActor (String) -> Void)?
    var onEnded: (@MainActor (String?) -> Void)?

    private let videoQueue = DispatchQueue(label: "tv.stream.video", qos: .userInteractive)
    private let audioQueue = DispatchQueue(label: "tv.stream.audio", qos: .userInteractive)
    private var socket: StreamSocket?
    private var stream: SCStream?
    private var encoder: VTCompressionSession?
    private(set) var info: TVInfo?
    private var lastConfig = Data()
    private var forceKeyframe = true
    private var sentAudioConfig = false
    private let stateLock = NSLock()
    private var stopped = false

    // On videoQueue:
    private var waiting: CVPixelBuffer?          // the newest picture, held while the network catches up
    private var waitingPTS = CMTime.invalid
    private var inEncoder = 0
    private var bitrate = 0
    private var maxBitrate = 0
    private var heldThisSecond = 0
    private var cleanSeconds = 0
    private var bytesThisSecond = 0
    private var framesThisSecond = 0
    private var secondStart = CACurrentMediaTime()

    init(tv: TV, token: String, mode: DisplayStreamer.Mode, sound: Bool) {
        self.tv = tv
        self.token = token
        self.mode = mode
        self.sound = sound
    }

    private var isStopped: Bool {
        stateLock.lock(); defer { stateLock.unlock() }
        return stopped
    }

    // MARK: Connect

    func connect() async throws -> TVInfo {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<TVInfo, Error>) in
            let t = Thread { [self] in
                do {
                    let s = try StreamSocket(host: tv.host, port: tv.displayPort)
                    socket = s
                    s.onDrained = { [weak self] in self?.videoQueue.async { self?.encodeWaiting() } }
                    send(.hello, Data(token.utf8))
                    guard let (type, payload) = readFrame(s), type == In.welcome.rawValue,
                          let o = (try? JSONSerialization.jsonObject(with: payload)) as? [String: Any] else {
                        s.close()
                        throw StreamError("The TV refused the stream. Pair this Mac again.")
                    }
                    let codecs = o["codecs"] as? [String] ?? ["avc"]
                    let info = TVInfo(name: o["name"] as? String ?? tv.name,
                                      width: o["width"] as? Int ?? 1920, height: o["height"] as? Int ?? 1080,
                                      refresh: o["refresh"] as? Int ?? 60, hevc: codecs.contains("hevc"),
                                      drawsCursor: o["cursor"] as? Bool ?? false)
                    self.info = info
                    cont.resume(returning: info)
                    listen(s)
                } catch {
                    cont.resume(throwing: error)
                }
            }
            t.name = "tv.stream.read"
            t.qualityOfService = .userInteractive
            t.start()
        }
    }

    /// Messages from the TV while streaming (on the reader thread): remote buttons, keyframe requests, "stopped".
    private func listen(_ s: StreamSocket) {
        while let (type, payload) = readFrame(s) {
            switch In(rawValue: type) {
            case .key:
                let name = String(decoding: payload, as: UTF8.self)
                let mode = self.mode
                Task { @MainActor in InputInjector.shared.remoteKey(name, mode: mode) }
            case .needKeyframe:
                videoQueue.async { self.forceKeyframe = true }
            case .stopped:
                end("Stopped on the TV.")
                return
            default:
                break
            }
        }
        end("Lost the connection to the TV.")
    }

    private func readFrame(_ s: StreamSocket) -> (UInt8, Data)? {
        guard let head = s.read(5) else { return nil }
        let b = [UInt8](head)
        let len = Int(b[1]) << 24 | Int(b[2]) << 16 | Int(b[3]) << 8 | Int(b[4])
        if len == 0 { return (b[0], Data()) }
        guard let body = s.read(len) else { return nil }
        return (b[0], body)
    }

    // MARK: Capture

    func capture(filter: SCContentFilter, info: TVInfo) async throws {
        let config = SCStreamConfiguration()
        config.width = info.width
        config.height = info.height
        config.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(info.refresh))
        config.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        config.colorMatrix = CGDisplayStream.yCbCrMatrix_ITU_R_709_2
        config.queueDepth = 5
        // The TV draws the cursor itself from its position (sent separately), so it moves without waiting on
        // video. The iPhone window has no Mac cursor to follow.
        config.showsCursor = mode == .iPhone || !info.drawsCursor
        config.preservesAspectRatio = true
        config.scalesToFit = true
        if sound {
            config.capturesAudio = true
            config.sampleRate = 48_000
            config.channelCount = 2
            config.excludesCurrentProcessAudio = true
        }
        try makeEncoder(info: info)
        let s = SCStream(filter: filter, configuration: config, delegate: self)
        try s.addStreamOutput(self, type: .screen, sampleHandlerQueue: videoQueue)
        if sound { try s.addStreamOutput(self, type: .audio, sampleHandlerQueue: audioQueue) }
        stream = s
        try await s.startCapture()
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        end(error.localizedDescription)
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sb: CMSampleBuffer, of type: SCStreamOutputType) {
        switch type {
        case .screen: newPicture(sb)
        case .audio: sendAudio(sb)
        default: break
        }
    }

    // MARK: Cursor

    /// Where the cursor is, in the video's pixels (its hotspot), or hidden. Sent ahead of any queued video.
    func sendCursor(x: Int, y: Int, visible: Bool) {
        var d = Data(capacity: 5)
        withUnsafeBytes(of: Int16(clamping: x).bigEndian) { d.append(contentsOf: $0) }
        withUnsafeBytes(of: Int16(clamping: y).bigEndian) { d.append(contentsOf: $0) }
        d.append(visible ? 1 : 0)
        send(.cursorPosition, d, urgent: true)
    }

    /// The cursor's look (PNG at the video's scale) and its hotspot in those pixels.
    func sendCursorImage(png: Data, hotX: Int, hotY: Int) {
        var d = Data(capacity: png.count + 4)
        withUnsafeBytes(of: UInt16(clamping: hotX).bigEndian) { d.append(contentsOf: $0) }
        withUnsafeBytes(of: UInt16(clamping: hotY).bigEndian) { d.append(contentsOf: $0) }
        d.append(png)
        send(.cursorImage, d, urgent: true)
    }

    // MARK: Video

    private func makeEncoder(info: TVInfo) throws {
        // H.264 has the lowest-latency path in the media engine; the TV decodes it in hardware.
        let spec = [kVTVideoEncoderSpecification_EnableLowLatencyRateControl: kCFBooleanTrue] as CFDictionary
        var session: VTCompressionSession?
        let status = VTCompressionSessionCreate(allocator: nil, width: Int32(info.width), height: Int32(info.height),
                                                codecType: kCMVideoCodecType_H264, encoderSpecification: spec,
                                                imageBufferAttributes: nil, compressedDataAllocator: nil,
                                                outputCallback: nil, refcon: nil, compressionSessionOut: &session)
        guard status == noErr, let session else { throw StreamError("The video encoder isn't available.") }
        // Start where Wi-Fi is comfortable and let the rate control find the link's real ceiling.
        let pixels = Double(info.width * info.height * info.refresh) / Double(1920 * 1080 * 60)
        maxBitrate = Int((tv.usb ? 40_000_000 : 24_000_000) * max(0.5, min(2.5, pixels)))
        bitrate = tv.usb ? maxBitrate : maxBitrate / 2
        let props: [CFString: Any] = [
            kVTCompressionPropertyKey_RealTime: true,
            kVTCompressionPropertyKey_ProfileLevel: kVTProfileLevel_H264_ConstrainedHigh_AutoLevel,
            kVTCompressionPropertyKey_AllowFrameReordering: false,
            kVTCompressionPropertyKey_MaxFrameDelayCount: 0,
            kVTCompressionPropertyKey_ExpectedFrameRate: info.refresh,
            kVTCompressionPropertyKey_MaxKeyFrameInterval: Int32.max,   // keyframes only when the TV asks
            kVTCompressionPropertyKey_PrioritizeEncodingSpeedOverQuality: true,
        ]
        for (k, v) in props { VTSessionSetProperty(session, key: k, value: v as CFTypeRef) }
        encoder = session
        applyBitrate()
        VTCompressionSessionPrepareToEncodeFrames(session)
    }

    private func applyBitrate() {
        guard let encoder, let info else { return }
        VTSessionSetProperty(encoder, key: kVTCompressionPropertyKey_AverageBitRate, value: bitrate as CFNumber)
        // No single second may burst far past the average: bursts are what stall Wi-Fi.
        let limits = [Double(bitrate) / 8 * 1.5, 1.0] as CFArray
        VTSessionSetProperty(encoder, key: kVTCompressionPropertyKey_DataRateLimits, value: limits)
        _ = info
    }

    /// About one picture's worth of bytes: more than that waiting to go out means the link is behind.
    private var roomBytes: Int {
        let perFrame = bitrate / 8 / max(1, info?.refresh ?? 60)
        // The cable has bandwidth to spare: only Wi-Fi needs pacing this tight.
        return tv.usb ? max(250_000, perFrame * 4) : max(24_000, perFrame * 2)
    }

    private func newPicture(_ sb: CMSampleBuffer) {
        guard !isStopped, let pixels = CMSampleBufferGetImageBuffer(sb) else { return }
        // ScreenCaptureKit also sends "nothing changed" buffers; only complete frames carry a picture.
        if let att = (CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]])?.first,
           let raw = att[.status] as? Int, let st = SCFrameStatus(rawValue: raw), st != .complete {
            return
        }
        waiting = pixels
        waitingPTS = CMSampleBufferGetPresentationTimeStamp(sb)
        encodeWaiting()
    }

    /// Encodes the newest picture, if the network has room for it now; otherwise it waits for `onDrained`.
    private func encodeWaiting() {
        guard let pixels = waiting, let encoder, !isStopped else { return }
        if inEncoder > 0 || (socket?.backlog ?? 0) > roomBytes {
            heldThisSecond += 1
            return
        }
        waiting = nil
        var frameProps: CFDictionary?
        if forceKeyframe {
            forceKeyframe = false
            frameProps = [kVTEncodeFrameOptionKey_ForceKeyFrame: true] as CFDictionary
        }
        inEncoder += 1
        VTCompressionSessionEncodeFrame(encoder, imageBuffer: pixels, presentationTimeStamp: waitingPTS, duration: .invalid,
                                        frameProperties: frameProps, infoFlagsOut: nil) { [weak self] status, _, out in
            guard let self else { return }
            if status == noErr, let out { self.sendEncoded(out) }
            self.videoQueue.async {
                self.inEncoder -= 1
                self.encodeWaiting()
            }
        }
    }

    private func sendEncoded(_ sb: CMSampleBuffer) {
        guard let fmt = CMSampleBufferGetFormatDescription(sb), let block = CMSampleBufferGetDataBuffer(sb) else { return }
        let attachments = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[CFString: Any]]
        let key = !((attachments?.first?[kCMSampleAttachmentKey_NotSync] as? Bool) ?? false)

        var params = Data()
        var headerLength: Int32 = 4
        if key {
            var count = 0
            CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fmt, parameterSetIndex: 0, parameterSetPointerOut: nil,
                                                               parameterSetSizeOut: nil, parameterSetCountOut: &count,
                                                               nalUnitHeaderLengthOut: &headerLength)
            for i in 0..<count {
                var ptr: UnsafePointer<UInt8>?
                var size = 0
                CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fmt, parameterSetIndex: i, parameterSetPointerOut: &ptr,
                                                                   parameterSetSizeOut: &size, parameterSetCountOut: nil,
                                                                   nalUnitHeaderLengthOut: nil)
                if let ptr {
                    params.append(contentsOf: [0, 0, 0, 1])
                    params.append(ptr, count: size)
                }
            }
        }
        if key, params != lastConfig, let info {
            lastConfig = params
            var cfg = Data([0])   // 0 = H.264
            cfg.append(contentsOf: [UInt8(info.width >> 8), UInt8(info.width & 0xFF), UInt8(info.height >> 8), UInt8(info.height & 0xFF)])
            cfg.append(params)
            send(.videoConfig, cfg)
        }

        // AVCC (length-prefixed NAL units) to Annex-B (start codes), with the parameter sets in front of keyframes.
        var length = 0
        var pointer: UnsafeMutablePointer<CChar>?
        guard CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: &length,
                                          dataPointerOut: &pointer) == noErr, let pointer else { return }
        var frame = Data(capacity: length + params.count + 16)
        frame.append(key ? 1 : 0)
        let ptsUs = Int64(CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sb)) * 1_000_000)
        withUnsafeBytes(of: ptsUs.bigEndian) { frame.append(contentsOf: $0) }
        if key { frame.append(params) }
        let hl = Int(headerLength)
        pointer.withMemoryRebound(to: UInt8.self, capacity: length) { bytes in
            var offset = 0
            while offset + hl <= length {
                var nalLength = 0
                for i in 0..<hl { nalLength = nalLength << 8 | Int(bytes[offset + i]) }
                offset += hl
                guard offset + nalLength <= length else { break }
                frame.append(contentsOf: [0, 0, 0, 1])
                frame.append(bytes + offset, count: nalLength)
                offset += nalLength
            }
        }
        send(.videoFrame, frame)
        let n = frame.count
        videoQueue.async { self.count(n) }
    }

    /// Once a second: adapt the bitrate (down fast when pictures had to wait, up slowly when the link is clear)
    /// and show the stream's numbers in the menu.
    private func count(_ bytes: Int) {
        bytesThisSecond += bytes
        framesThisSecond += 1
        let now = CACurrentMediaTime()
        guard now - secondStart >= 1 else { return }
        let elapsed = now - secondStart
        let mbps = Double(bytesThisSecond) * 8 / elapsed / 1_000_000
        let fps = Double(framesThisSecond) / elapsed
        if heldThisSecond > 3 {
            bitrate = max(3_000_000, Int(Double(bitrate) * 0.7))
            cleanSeconds = 0
            applyBitrate()
        } else if heldThisSecond == 0 {
            cleanSeconds += 1
            if cleanSeconds >= 2 && bitrate < maxBitrate {
                bitrate = min(maxBitrate, Int(Double(bitrate) * 1.15))
                applyBitrate()
            }
        }
        bytesThisSecond = 0
        framesThisSecond = 0
        heldThisSecond = 0
        secondStart = now
        guard let info else { return }
        let text = String(format: "%dp · %.0f fps · %.0f Mb/s", info.height, fps, mbps) + (tv.usb ? " · USB" : "")
        Task { @MainActor in self.onStatus?(text) }
    }

    // MARK: Audio

    /// System sound as 16-bit PCM (48 kHz stereo): a few hundred kB/s, and no codec delay.
    private func sendAudio(_ sb: CMSampleBuffer) {
        guard !stopped else { return }
        var abl = AudioBufferList()
        var block: CMBlockBuffer?
        guard CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sb, bufferListSizeNeededOut: nil, bufferListOut: &abl, bufferListSize: MemoryLayout<AudioBufferList>.size,
            blockBufferAllocator: nil, blockBufferMemoryAllocator: nil, flags: 0, blockBufferOut: &block) == noErr else {
            // Two (non-interleaved) channels need a bigger list.
            sendAudioPlanar(sb)
            return
        }
        let list = UnsafeMutableAudioBufferListPointer(&abl)
        guard let first = list.first, let data = first.mData else { return }
        let frames = Int(first.mDataByteSize) / MemoryLayout<Float>.size / Int(max(1, first.mNumberChannels))
        var pcm = Data(capacity: frames * 4)
        let f = data.assumingMemoryBound(to: Float.self)
        let channels = Int(first.mNumberChannels)
        for i in 0..<frames {
            for ch in 0..<2 {
                let v = f[i * channels + min(ch, channels - 1)]
                appendSample(&pcm, v)
            }
        }
        sendPCM(pcm)
    }

    private func sendAudioPlanar(_ sb: CMSampleBuffer) {
        var size = 0
        CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(sb, bufferListSizeNeededOut: &size, bufferListOut: nil,
                                                                bufferListSize: 0, blockBufferAllocator: nil,
                                                                blockBufferMemoryAllocator: nil, flags: 0, blockBufferOut: nil)
        let raw = UnsafeMutableRawPointer.allocate(byteCount: size, alignment: 16)
        defer { raw.deallocate() }
        let ablPtr = raw.bindMemory(to: AudioBufferList.self, capacity: 1)
        var block: CMBlockBuffer?
        guard CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sb, bufferListSizeNeededOut: nil, bufferListOut: ablPtr, bufferListSize: size, blockBufferAllocator: nil,
            blockBufferMemoryAllocator: nil, flags: 0, blockBufferOut: &block) == noErr else { return }
        let list = UnsafeMutableAudioBufferListPointer(ablPtr)
        guard list.count > 0, let l = list[0].mData else { return }
        let r = list.count > 1 ? list[1].mData : l
        let frames = Int(list[0].mDataByteSize) / MemoryLayout<Float>.size
        let lf = l.assumingMemoryBound(to: Float.self)
        let rf = (r ?? l).assumingMemoryBound(to: Float.self)
        var pcm = Data(capacity: frames * 4)
        for i in 0..<frames {
            appendSample(&pcm, lf[i])
            appendSample(&pcm, rf[i])
        }
        sendPCM(pcm)
    }

    private func appendSample(_ d: inout Data, _ v: Float) {
        let s = Int16(max(-1, min(1, v)) * 32767)
        withUnsafeBytes(of: s.littleEndian) { d.append(contentsOf: $0) }
    }

    private func sendPCM(_ pcm: Data) {
        if !sentAudioConfig {
            sentAudioConfig = true
            var cfg = Data()
            withUnsafeBytes(of: UInt32(48_000).bigEndian) { cfg.append(contentsOf: $0) }
            cfg.append(2)
            send(.audioConfig, cfg)
        }
        // Sound is small; skip it only when the link is badly behind (it would arrive late anyway).
        if (socket?.backlog ?? 0) > 256_000 { return }
        send(.audioPCM, pcm)
    }

    // MARK: Sending and stopping

    private func send(_ type: Out, _ payload: Data, urgent: Bool = false) {
        guard let s = socket else { return }
        var f = Data(capacity: payload.count + 5)
        f.append(type.rawValue)
        withUnsafeBytes(of: UInt32(payload.count).bigEndian) { f.append(contentsOf: $0) }
        f.append(payload)
        s.send(f, urgent: urgent)
    }

    func stop(sendBye: Bool) {
        if sendBye { send(.bye, Data()) }
        // Give the goodbye a moment to leave before closing.
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.1) { self.teardown() }
    }

    private func end(_ reason: String?) {
        guard teardown() else { return }
        Task { @MainActor in self.onEnded?(reason) }
    }

    @discardableResult
    private func teardown() -> Bool {
        stateLock.lock()
        if stopped { stateLock.unlock(); return false }
        stopped = true
        stateLock.unlock()
        let s = stream
        stream = nil
        Task { try? await s?.stopCapture() }
        videoQueue.async {
            if let encoder = self.encoder { VTCompressionSessionInvalidate(encoder) }
            self.encoder = nil
            self.waiting = nil
        }
        socket?.close()
        return true
    }
}
