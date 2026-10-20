import AppKit
import ApplicationServices

/// Universal Control for the TV: push the cursor past the top edge of the Mac's screen and it continues onto
/// the TV (the TV shows its own cursor, hovering highlights, clicking selects, scrolling swipes, typing goes
/// into the TV's text field). Push it back down off the bottom of the TV and it returns to the Mac where it
/// left. ⌘Esc always brings it back.
///
/// Needs Accessibility access, for the event tap that takes over the mouse and keyboard while on the TV.
@MainActor
final class PointerBridge: ObservableObject {

    static let shared = PointerBridge()

    @Published var enabled = UserDefaults.standard.object(forKey: "pointerBridge") as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(enabled, forKey: "pointerBridge")
            enabled ? start() : stop()
        }
    }
    @Published private(set) var onTV = false
    @Published private(set) var needsPermission = false

    var channel: ControlChannel?

    /// While the TV shows this Mac's screen (TV as Display), the cursor already reaches the TV as a real display,
    /// and its clicks belong to the Mac: crossing over to the TV's own cursor would swallow them.
    var suspended = false {
        didSet { if suspended && onTV { channel?.pointerExit(); returnToMac(at: lastAlong) } }
    }
    private var tap: CFMachPort?
    private var source: CFRunLoopSource?
    private var pressure: Double = 0
    private var leftFrom = CGRect.zero        // the display edge the cursor left by
    private var lastAlong = 0.5
    private var residual = CGPoint.zero

    func start() {
        guard enabled, tap == nil else { return }
        let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        guard AXIsProcessTrustedWithOptions(opts) else {
            needsPermission = true
            // Try again once access is granted in System Settings.
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in self?.start() }
            return
        }
        needsPermission = false
        var mask: CGEventMask = 0
        for t: CGEventType in [.mouseMoved, .leftMouseDragged, .rightMouseDragged, .leftMouseDown, .leftMouseUp,
                               .rightMouseDown, .rightMouseUp, .scrollWheel, .keyDown, .keyUp] {
            mask |= 1 << t.rawValue
        }
        let me = Unmanaged.passUnretained(self).toOpaque()
        guard let tap = CGEvent.tapCreate(tap: .cgSessionEventTap, place: .headInsertEventTap, options: .defaultTap,
                                          eventsOfInterest: mask, callback: { _, type, event, refcon in
            guard let refcon else { return Unmanaged.passUnretained(event) }
            let bridge = Unmanaged<PointerBridge>.fromOpaque(refcon).takeUnretainedValue()
            return MainActor.assumeIsolated { bridge.handle(type, event) }
        }, userInfo: me) else { return }
        self.tap = tap
        source = CFMachPortCreateRunLoopSource(nil, tap, 0)
        CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
        CGEvent.tapEnable(tap: tap, enable: true)
    }

    func stop() {
        if onTV { returnToMac(at: lastAlong) }
        if let tap { CGEvent.tapEnable(tap: tap, enable: false) }
        if let source { CFRunLoopRemoveSource(CFRunLoopGetMain(), source, .commonModes) }
        tap = nil
        source = nil
    }

    /// The connection dropped: whatever the TV had, the cursor is the Mac's again.
    func connectionLost() {
        if onTV { returnToMac(at: lastAlong) }
    }

    /// The TV says the cursor left its screen at `along` (0..1) of the edge.
    func tvReleased(at along: Double) {
        guard onTV else { return }
        returnToMac(at: along)
    }

    // MARK: Events

    private func handle(_ type: CGEventType, _ event: CGEvent) -> Unmanaged<CGEvent>? {
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let tap { CGEvent.tapEnable(tap: tap, enable: true) }
            return Unmanaged.passUnretained(event)
        }
        guard onTV else {
            if !suspended && (type == .mouseMoved || type == .leftMouseDragged) { watchTopEdge(event) }
            return Unmanaged.passUnretained(event)
        }
        guard let channel, channel.isReady else {
            returnToMac(at: lastAlong)
            return Unmanaged.passUnretained(event)
        }
        switch type {
        case .mouseMoved, .leftMouseDragged, .rightMouseDragged:
            // Fractional movement, with the remainder carried over, so slow moves stay smooth.
            residual.x += event.getDoubleValueField(.mouseEventDeltaX)
            residual.y += event.getDoubleValueField(.mouseEventDeltaY)
            let dx = Int(residual.x.rounded(.towardZero)), dy = Int(residual.y.rounded(.towardZero))
            residual.x -= Double(dx)
            residual.y -= Double(dy)
            if dx != 0 || dy != 0 { channel.pointerMove(dx: dx, dy: dy) }
        case .leftMouseDown: channel.pointerButton(0, down: true)
        case .leftMouseUp: channel.pointerButton(0, down: false)
        case .rightMouseDown: channel.pointerButton(1, down: true)
        case .rightMouseUp: channel.pointerButton(1, down: false)
        case .scrollWheel:
            scroll(event, channel)
        case .keyDown:
            let code = event.getIntegerValueField(.keyboardEventKeycode)
            let flags = event.flags
            if flags.contains(.maskCommand) {
                if code == 53 {                       // ⌘Esc: back to the Mac now
                    channel.pointerExit()
                    returnToMac(at: lastAlong)
                    return nil
                }
                return Unmanaged.passUnretained(event)   // other ⌘ shortcuts still work on the Mac
            }
            key(code, event, channel)
        case .keyUp:
            break
        default:
            return Unmanaged.passUnretained(event)
        }
        return nil   // the TV has it
    }

    /**
     * Two-finger scrolling: the trackpad's gesture (began, moved, ended) and its glide after a flick move the
     * TV's selection through rows and shelves. A mouse wheel has no gesture: each click is a step of its own.
     */
    private func scroll(_ event: CGEvent, _ channel: ControlChannel) {
        let dy = Int(event.getIntegerValueField(.scrollWheelEventPointDeltaAxis1))
        let dx = Int(event.getIntegerValueField(.scrollWheelEventPointDeltaAxis2))
        let phase = event.getIntegerValueField(.scrollWheelEventScrollPhase)
        let momentum = event.getIntegerValueField(.scrollWheelEventMomentumPhase)
        if momentum != 0 {
            // The glide: 1 begin, 2 continue, 3 end.
            if momentum == 3 { channel.pointerScroll(dx: dx, dy: dy, phase: 4) }
            else if dx != 0 || dy != 0 { channel.pointerScroll(dx: dx, dy: dy, phase: 2) }
            return
        }
        switch phase {
        case 1: channel.pointerScroll(dx: dx, dy: dy, phase: 1)           // began
        case 2: if dx != 0 || dy != 0 { channel.pointerScroll(dx: dx, dy: dy, phase: 2) }
        case 4, 8: channel.pointerScroll(dx: dx, dy: dy, phase: 2)        // fingers lifted; the glide may follow
        case 0: if dx != 0 || dy != 0 { channel.pointerScroll(dx: dx, dy: dy, phase: 0) }   // mouse wheel
        default: break                                                      // "may begin": fingers resting
        }
    }

    private func key(_ code: Int64, _ event: CGEvent, _ channel: ControlChannel) {
        switch code {
        case 126: channel.key(.up)
        case 125: channel.key(.down)
        case 123: channel.key(.left)
        case 124: channel.key(.right)
        case 36, 76: channel.key(.select)
        case 53: channel.key(.back)
        case 51: channel.type("\u{8}")
        case 48: break                                 // Tab
        default:
            var length = 0
            var chars = [UniChar](repeating: 0, count: 8)
            event.keyboardGetUnicodeString(maxStringLength: 8, actualStringLength: &length, unicodeString: &chars)
            if length > 0 { channel.type(String(utf16CodeUnits: chars, count: length)) }
        }
    }

    /// Pressing on against the top of the screen (where no other display continues) crosses to the TV.
    private func watchTopEdge(_ event: CGEvent) {
        let p = event.location
        var id: CGDirectDisplayID = 0
        var count: UInt32 = 0
        CGGetDisplaysWithPoint(p, 1, &id, &count)
        guard count > 0 else { pressure = 0; return }
        let b = CGDisplayBounds(id)
        var above: UInt32 = 0
        var other: CGDirectDisplayID = 0
        CGGetDisplaysWithPoint(CGPoint(x: p.x, y: b.minY - 2), 1, &other, &above)
        let dy = event.getDoubleValueField(.mouseEventDeltaY)
        guard p.y <= b.minY + 1, above == 0, dy < 0 else { pressure = max(0, pressure - 4); return }
        pressure += -dy
        if pressure > 40 {
            pressure = 0
            goToTV(from: b, along: (p.x - b.minX) / b.width)
        }
    }

    private func goToTV(from display: CGRect, along: Double) {
        guard let channel, channel.isReady else { return }
        onTV = true
        leftFrom = display
        lastAlong = along
        CGAssociateMouseAndMouseCursorPosition(0)   // the Mac's cursor stays put while the TV's moves
        CursorVisibility.hide()
        channel.pointerEnter(along: along, edge: "bottom")
    }

    private func returnToMac(at along: Double) {
        onTV = false
        lastAlong = along
        let b = leftFrom == .zero ? CGDisplayBounds(CGMainDisplayID()) : leftFrom
        CGWarpMouseCursorPosition(CGPoint(x: b.minX + along * b.width, y: b.minY + 6))
        CGAssociateMouseAndMouseCursorPosition(1)
        CursorVisibility.show()
    }
}

/// Hiding the cursor while another app is in front needs the window server's "SetsCursorInBackground"
/// connection property (what Universal Control-style apps use); looked up at run time.
enum CursorVisibility {
    private typealias DefaultConnection = @convention(c) () -> Int32
    private typealias SetProperty = @convention(c) (Int32, Int32, CFString, CFTypeRef) -> Int32

    private static let allowed: Bool = {
        guard let h = dlopen(nil, RTLD_NOW),
              let c = dlsym(h, "_CGSDefaultConnection"),
              let s = dlsym(h, "CGSSetConnectionProperty") else { return false }
        let conn = unsafeBitCast(c, to: DefaultConnection.self)()
        _ = unsafeBitCast(s, to: SetProperty.self)(conn, conn, "SetsCursorInBackground" as CFString, kCFBooleanTrue)
        return true
    }()

    static func hide() {
        _ = allowed
        CGDisplayHideCursor(CGMainDisplayID())
    }

    static func show() {
        CGDisplayShowCursor(CGMainDisplayID())
    }
}

/// Posts keys and clicks on this Mac for the TV remote while the TV is showing the Mac's screen.
@MainActor
final class InputInjector {
    static let shared = InputInjector()

    /// The iPhone Mirroring window, in screen coordinates (iPhone mode).
    var iPhoneWindow: CGRect?
    private var lastMove = Date.distantPast
    private var step: CGFloat = 36

    func remoteKey(_ name: String, mode: DisplayStreamer.Mode) {
        guard AXIsProcessTrusted() else { return }
        if mode == .iPhone { iPhoneKey(name); return }
        switch name {
        case "up": key(126)
        case "down": key(125)
        case "left": key(123)
        case "right": key(124)
        case "select": key(36)
        case "back": key(53)
        case "playpause": media(16)
        case "next": media(17)
        case "previous": media(18)
        case "menu": key(48, flags: .maskCommand)    // app switcher
        default: break
        }
    }

    /// iPhone Mirroring: the remote moves the pointer over the iPhone and clicks; Back is Home (⌘1),
    /// Play/Pause the App Switcher (⌘2), Menu is Spotlight (⌘3).
    private func iPhoneKey(_ name: String) {
        let now = Date()
        step = now.timeIntervalSince(lastMove) < 0.25 ? min(step * 1.35, 160) : 36
        lastMove = now
        var p = NSEvent.mouseLocation
        p.y = (NSScreen.screens.first?.frame.height ?? 0) - p.y   // to top-left coordinates
        let w = iPhoneWindow ?? CGRect(x: p.x - 200, y: p.y - 400, width: 400, height: 800)
        switch name {
        case "up": p.y -= step
        case "down": p.y += step
        case "left": p.x -= step
        case "right": p.x += step
        case "select": click(at: p); return
        case "back": key(18, flags: .maskCommand); return
        case "playpause": key(19, flags: .maskCommand); return
        case "menu": key(20, flags: .maskCommand); return
        default: return
        }
        p.x = min(max(p.x, w.minX + 4), w.maxX - 4)
        p.y = min(max(p.y, w.minY + 30), w.maxY - 4)
        CGWarpMouseCursorPosition(p)
    }

    private func key(_ code: CGKeyCode, flags: CGEventFlags = []) {
        let src = CGEventSource(stateID: .hidSystemState)
        for down in [true, false] {
            let e = CGEvent(keyboardEventSource: src, virtualKey: code, keyDown: down)
            e?.flags = flags
            e?.post(tap: .cghidEventTap)
        }
    }

    private func click(at p: CGPoint) {
        let src = CGEventSource(stateID: .hidSystemState)
        CGEvent(mouseEventSource: src, mouseType: .leftMouseDown, mouseCursorPosition: p, mouseButton: .left)?.post(tap: .cghidEventTap)
        CGEvent(mouseEventSource: src, mouseType: .leftMouseUp, mouseCursorPosition: p, mouseButton: .left)?.post(tap: .cghidEventTap)
    }

    /// Play/pause, next, previous: the keyboard's media keys.
    private func media(_ key: Int32) {
        for down in [true, false] {
            let flags = NSEvent.ModifierFlags(rawValue: down ? 0xA00 : 0xB00)
            let data1 = Int((key << 16) | ((down ? 0xA : 0xB) << 8))
            NSEvent.otherEvent(with: .systemDefined, location: .zero, modifierFlags: flags, timestamp: 0,
                               windowNumber: 0, context: nil, subtype: 8, data1: data1, data2: -1)?
                .cgEvent?.post(tap: .cghidEventTap)
        }
    }
}
