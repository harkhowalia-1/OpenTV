import AppKit
import CoreImage

// Otv Remote's App Store screenshots and the website's device pictures: OpenTV running on a TV, with the
// iPhone in front of it, in a dark room lit by the TV.
//
//   swift make-screenshots.swift appstore <out dir> <width> <height>   (1320 2868 for 6.9", 1206 2622 for 6.3")
//   swift make-screenshots.swift web <out dir>
//
// Captures/TV holds 1920×1080 TV screenshots (adb exec-out screencap -p) and Captures/iPhone the Simulator's
// (xcrun simctl io booted screenshot, status bar set to 9:41). Captures/ stays on this Mac: anything personal in
// them (account names, the Wi-Fi network, a gamertag) is covered with placeholders below before a picture is made.

let here = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
let captures = here.appendingPathComponent("Captures")
let args = CommandLine.arguments
let mode = args.count > 1 ? args[1] : "appstore"
let outDir = args.count > 2 ? args[2] : here.appendingPathComponent("Screenshots").path
try? FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)

// ---- privacy: personal details are covered with placeholders, in TV pixels from the top left ----------------

/// What's personal on each screen, and what goes over it.
enum Placeholder {
    case avatar(center: CGPoint, radius: CGFloat, letter: String)          // the profile picture in the top-right pill
    case text(CGRect, sample: CGPoint, text: String, size: CGFloat, color: UInt32)   // e.g. the Wi-Fi network's name
    case gamerChip(CGRect)                                                   // Cloud Gaming's gamertag button
    case tile(CGRect, svg: String)                                           // a tile redrawn with OtvOS's current art
}
/// The Settings tile, sixth in the dock (captures made before its new art).
let settingsTile = Placeholder.tile(CGRect(x: 1572, y: 778, width: 240, height: 142),
                                    svg: here.appendingPathComponent("../../docs/images/tiles/settings.svg").path)
let avatar = Placeholder.avatar(center: CGPoint(x: 1792, y: 102), radius: 35, letter: "A")
let placeholders: [String: [Placeholder]] = [
    "home": [avatar],
    "wallpaper": [avatar],
    "xbox": [avatar],
    "pair": [.text(CGRect(x: 734, y: 722, width: 452, height: 40), sample: CGPoint(x: 722, y: 740),
                   text: "Or open http://192.168.1.50:8800 in a browser", size: 23, color: 0x8E8E93)],   // the TV's address
    "control-center": [avatar, .text(CGRect(x: 1680, y: 244, width: 125, height: 38), sample: CGPoint(x: 1800, y: 262),
                                     text: "Home Wi-Fi", size: 23, color: 0xA9A9AE)],
    "cloud": [.gamerChip(CGRect(x: 1470, y: 40, width: 380, height: 135))],
]
/// Anything else that's personal and has no placeholder is blurred.
let privateAreas: [String: [CGRect]] = [
    "cloud": [CGRect(x: 1470, y: 40, width: 380, height: 135)],
]

let ciContext = CIContext(options: [.useSoftwareRenderer: false])

func cgImage(_ path: String) -> CGImage {
    guard let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
          let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else { fatalError("missing \(path)") }
    return img
}

func blurred(_ image: CGImage, _ areas: [CGRect], sigma: Double = 26) -> CGImage {
    guard !areas.isEmpty else { return image }
    let h = CGFloat(image.height)
    let ci = CIImage(cgImage: image)
    var out = ci
    for r in areas {
        let flipped = CGRect(x: r.minX, y: h - r.maxY, width: r.width, height: r.height)
        // Pixellate first, then blur: nothing readable survives, and it still looks soft.
        let px = ci.clampedToExtent().applyingFilter("CIPixellate", parameters: [kCIInputScaleKey: 18,
                                                                                kCIInputCenterKey: CIVector(x: flipped.midX, y: flipped.midY)])
        let soft = px.clampedToExtent().applyingGaussianBlur(sigma: sigma).cropped(to: flipped)
        out = soft.composited(over: out)
    }
    return ciContext.createCGImage(out, from: ci.extent)!
}

/// The colour of one pixel (top-left coordinates).
func pixel(_ image: CGImage, _ p: CGPoint) -> NSColor {
    var px = [UInt8](repeating: 0, count: 4)
    let ctx = CGContext(data: &px, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4, space: CGColorSpaceCreateDeviceRGB(),
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.draw(image, in: CGRect(x: -p.x, y: -(CGFloat(image.height) - p.y - 1), width: CGFloat(image.width), height: CGFloat(image.height)))
    return NSColor(srgbRed: CGFloat(px[0]) / 255, green: CGFloat(px[1]) / 255, blue: CGFloat(px[2]) / 255, alpha: 1)
}

func withPlaceholders(_ image: CGImage, _ items: [Placeholder]) -> CGImage {
    guard !items.isEmpty else { return image }
    let w = image.width, h = image.height
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: w, pixelsHigh: h, bitsPerSample: 8, samplesPerPixel: 4,
                               hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    let g = NSGraphicsContext(bitmapImageRep: rep)!
    g.cgContext.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
    g.cgContext.translateBy(x: 0, y: CGFloat(h))
    g.cgContext.scaleBy(x: 1, y: -1)
    NSGraphicsContext.current = NSGraphicsContext(cgContext: g.cgContext, flipped: true)
    func label(_ s: String, _ size: CGFloat, _ weight: NSFont.Weight, _ c: NSColor, at p: CGPoint, center: Bool = false) {
        let a = NSAttributedString(string: s, attributes: [.font: NSFont.systemFont(ofSize: size, weight: weight), .foregroundColor: c])
        let b = a.size()
        a.draw(at: CGPoint(x: center ? p.x - b.width / 2 : p.x, y: p.y - b.height / 2))
    }
    for item in items {
        switch item {
        case let .avatar(c, r, letter):
            color(0x4FD1C5).setFill()
            NSBezierPath(ovalIn: NSRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)).fill()
            label(letter, r * 0.95, .medium, .white, at: c, center: true)
        case let .text(rect, sample, text, size, c):
            pixel(image, sample).setFill()
            NSBezierPath(rect: rect).fill()
            label(text, size, .regular, color(c), at: CGPoint(x: rect.minX + 6, y: rect.midY))
        case let .tile(rect, svg):
            if let art = NSImage(contentsOfFile: svg) {
                NSGraphicsContext.saveGraphicsState()
                rr(NSRect(x: rect.minX, y: rect.minY, width: rect.width, height: rect.height), 20).addClip()
                art.draw(in: rect, from: .zero, operation: .sourceOver, fraction: 1, respectFlipped: true, hints: nil)
                NSGraphicsContext.restoreGraphicsState()
            }
        case let .gamerChip(rect):
            // A fresh profile button over the blurred original: a generic picture, "Player One" and a Gamerscore.
            let chip = NSRect(x: rect.minX + 40, y: rect.minY + 22, width: rect.width - 70, height: 92)
            NSColor.white.withAlphaComponent(0.22).setFill()
            rr(chip, chip.height / 2).fill()
            let pic = NSRect(x: chip.minX + 12, y: chip.minY + 12, width: 68, height: 68)
            NSGradient(colors: [color(0x34C759), color(0x0B7A33)])!.draw(in: NSBezierPath(ovalIn: pic), angle: -60)
            label("P1", 26, .semibold, .white, at: CGPoint(x: pic.midX, y: pic.midY), center: true)
            label("Player One", 30, .medium, .white, at: CGPoint(x: pic.maxX + 18, y: chip.minY + 32))
            label("1,250 G", 22, .medium, NSColor.white.withAlphaComponent(0.72), at: CGPoint(x: pic.maxX + 18, y: chip.minY + 64))
        }
    }
    NSGraphicsContext.restoreGraphicsState()
    return rep.cgImage!
}

func tv(_ name: String) -> NSImage {
    let raw = cgImage(captures.appendingPathComponent("TV/\(name).png").path)
    let img = withPlaceholders(blurred(raw, privateAreas[name] ?? []), placeholders[name] ?? [])
    return NSImage(cgImage: img, size: NSSize(width: img.width, height: img.height))
}

func phone(_ name: String) -> NSImage {
    let img = cgImage(captures.appendingPathComponent("iPhone/\(name).png").path)
    return NSImage(cgImage: img, size: NSSize(width: img.width, height: img.height))
}

/// The TV picture blurred to a glow: the light it throws into a dark room.
func glow(_ image: NSImage, sigma: Double) -> NSImage {
    var rect = CGRect(origin: .zero, size: image.size)
    let cg = image.cgImage(forProposedRect: &rect, context: nil, hints: nil)!
    let ci = CIImage(cgImage: cg).clampedToExtent().applyingGaussianBlur(sigma: sigma)
        .applyingFilter("CIColorControls", parameters: [kCIInputSaturationKey: 1.35, kCIInputBrightnessKey: 0.0])
        .cropped(to: CIImage(cgImage: cg).extent)
    let out = ciContext.createCGImage(ci, from: CIImage(cgImage: cg).extent)!
    return NSImage(cgImage: out, size: image.size)
}

// ---- drawing ----------------------------------------------------------------------------------------------

func color(_ hex: UInt32, _ a: CGFloat = 1) -> NSColor {
    NSColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: a)
}
func rr(_ r: NSRect, _ radius: CGFloat) -> NSBezierPath { NSBezierPath(roundedRect: r, xRadius: radius, yRadius: radius) }

/// A slim, nearly borderless TV: the picture, a hairline black bezel, a dark aluminium edge and a soft shadow.
/// `frame` is the whole TV, top-left based on a flipped canvas.
func drawTV(_ screen: NSImage, in frame: NSRect, scale s: CGFloat) {
    let corner = frame.width * 0.008
    NSGraphicsContext.saveGraphicsState()
    let shadow = NSShadow()
    shadow.shadowColor = NSColor.black.withAlphaComponent(0.85)
    shadow.shadowBlurRadius = 60 * s
    shadow.shadowOffset = NSSize(width: 0, height: -30 * s)
    shadow.set()
    color(0x0A0A0B).setFill()
    rr(frame, corner).fill()
    NSGraphicsContext.restoreGraphicsState()
    // Aluminium edge.
    let edge = NSGradient(colors: [color(0x5A5A5E), color(0x2A2A2D), color(0x48484C)])!
    edge.draw(in: rr(frame, corner), angle: -90)
    let bezel = frame.insetBy(dx: 2.5 * s, dy: 2.5 * s)
    NSColor.black.setFill()
    rr(bezel, corner * 0.8).fill()
    let pic = bezel.insetBy(dx: frame.width * 0.0075, dy: frame.width * 0.0075)
    NSGraphicsContext.saveGraphicsState()
    rr(pic, 2 * s).addClip()
    screen.draw(in: pic)
    // A faint reflection across the glass.
    let sheen = NSGradient(colors: [NSColor.white.withAlphaComponent(0.07), NSColor.white.withAlphaComponent(0.0)])!
    sheen.draw(in: NSBezierPath(rect: pic), angle: -60)
    NSGraphicsContext.restoreGraphicsState()
}

/// An iPhone in dark titanium: rim, black border, the screen, Dynamic Island. `top` and `centerX` on a flipped canvas.
func drawPhone(_ screen: NSImage, screenWidth sw: CGFloat, centerX: CGFloat, top: CGFloat, scale s: CGFloat, shadowAlpha: CGFloat = 0.75) -> NSRect {
    let sh = sw * 2622 / 1206
    let border = sw * 0.030, rim = sw * 0.014
    let outer = NSRect(x: centerX - sw / 2 - border - rim, y: top, width: sw + 2 * (border + rim), height: sh + 2 * (border + rim))
    let radius = outer.width * 0.17
    let titanium = NSGradient(colors: [color(0x6B6B70), color(0x2E2E31), color(0x1E1E20), color(0x55555A)])!
    // Buttons, behind the frame.
    let bw = rim * 1.5
    for (y, h) in [(0.17, 0.045), (0.25, 0.075), (0.34, 0.075)] {
        let r = NSRect(x: outer.minX - bw * 0.7, y: outer.minY + outer.height * CGFloat(y), width: bw * 2, height: outer.height * CGFloat(h))
        titanium.draw(in: rr(r, bw), angle: 0)
    }
    titanium.draw(in: rr(NSRect(x: outer.maxX - bw * 1.3, y: outer.minY + outer.height * 0.27, width: bw * 2, height: outer.height * 0.11), bw), angle: 180)
    NSGraphicsContext.saveGraphicsState()
    let shadow = NSShadow()
    shadow.shadowColor = NSColor.black.withAlphaComponent(shadowAlpha)
    shadow.shadowBlurRadius = 90 * s
    shadow.shadowOffset = NSSize(width: 0, height: -40 * s)
    shadow.set()
    color(0x1C1C1E).setFill()
    rr(outer, radius).fill()
    NSGraphicsContext.restoreGraphicsState()
    titanium.draw(in: rr(outer, radius), angle: -40)
    let inner = outer.insetBy(dx: rim, dy: rim)
    NSColor.black.setFill()
    rr(inner, radius - rim).fill()
    let scr = inner.insetBy(dx: border, dy: border)
    NSGraphicsContext.saveGraphicsState()
    rr(scr, radius - rim - border).addClip()
    screen.draw(in: scr, from: .zero, operation: .sourceOver, fraction: 1, respectFlipped: true, hints: nil)
    NSGraphicsContext.restoreGraphicsState()
    // Dynamic Island.
    let iw = sw * 0.30, ih = sw * 0.088
    NSColor.black.setFill()
    rr(NSRect(x: centerX - iw / 2, y: scr.minY + sw * 0.034, width: iw, height: ih), ih / 2).fill()
    return outer
}

func text(_ s: String, size: CGFloat, weight: NSFont.Weight, color c: NSColor, kern: CGFloat = 0, line: CGFloat = 1.0) -> NSAttributedString {
    let p = NSMutableParagraphStyle()
    p.alignment = .center
    p.lineHeightMultiple = line
    return NSAttributedString(string: s, attributes: [.font: NSFont.systemFont(ofSize: size, weight: weight), .foregroundColor: c,
                                                      .paragraphStyle: p, .kern: kern])
}

/// Text filled with OpenTV's pink-to-violet gradient (Apple's product pages tint their eyebrows this way).
func gradientText(_ s: String, size: CGFloat, center: CGPoint) {
    let str = text(s, size: size, weight: .semibold, color: .white)
    let bounds = str.boundingRect(with: NSSize(width: 4000, height: 400), options: [.usesLineFragmentOrigin])
    let r = NSRect(x: center.x - bounds.width / 2 - 4, y: center.y, width: bounds.width + 8, height: bounds.height)
    // Draw the text as a mask, then the gradient through it.
    let mask = NSImage(size: r.size, flipped: true) { _ in
        str.draw(with: NSRect(origin: .zero, size: r.size), options: [.usesLineFragmentOrigin])
        return true
    }
    let tinted = NSImage(size: r.size, flipped: true) { rect in
        NSGradient(colors: [color(0xFF6A88), color(0xFF8A5C), color(0xB06BFF)])!.draw(in: rect, angle: 0)
        mask.draw(in: rect, from: .zero, operation: .destinationIn, fraction: 1, respectFlipped: true, hints: nil)
        return true
    }
    tinted.draw(in: r, from: .zero, operation: .sourceOver, fraction: 1, respectFlipped: true, hints: nil)
}

func canvas(_ w: Int, _ h: Int, background: NSColor, _ draw: (CGFloat, CGFloat) -> Void) -> NSBitmapImageRep {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: w, pixelsHigh: h, bitsPerSample: 8, samplesPerPixel: 4,
                               hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    rep.size = NSSize(width: w, height: h)
    NSGraphicsContext.saveGraphicsState()
    let ctx = NSGraphicsContext(bitmapImageRep: rep)!
    // Flip so layout reads top-down, like a page.
    ctx.cgContext.translateBy(x: 0, y: CGFloat(h))
    ctx.cgContext.scaleBy(x: 1, y: -1)
    NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx.cgContext, flipped: true)
    background.setFill()
    NSRect(x: 0, y: 0, width: w, height: h).fill()
    draw(CGFloat(w), CGFloat(h))
    NSGraphicsContext.restoreGraphicsState()
    return rep
}

func save(_ rep: NSBitmapImageRep, _ name: String, jpeg: Bool = true) {
    let data = jpeg ? rep.representation(using: .jpeg, properties: [.compressionFactor: 0.9])!
                    : rep.representation(using: .png, properties: [:])!
    try! data.write(to: URL(fileURLWithPath: "\(outDir)/\(name)"))
}

/// The room: black, lit by the TV's own colours, the light fading softly into the dark around it.
func drawRoom(_ screen: NSImage, tvFrame: NSRect, W: CGFloat, H: CGFloat, strength: CGFloat = 0.75, spread: CGFloat = 1.0) {
    var rect = CGRect(origin: .zero, size: screen.size)
    let cg = screen.cgImage(forProposedRect: &rect, context: nil, hints: nil)!
    let canvasRect = CGRect(x: 0, y: 0, width: W, height: H)
    // The picture where the TV is (Core Image counts from the bottom), a little larger, then blurred far out.
    let grow: CGFloat = 1.18
    let target = CGRect(x: tvFrame.midX - tvFrame.width * grow / 2, y: H - tvFrame.midY - tvFrame.height * grow / 2,
                        width: tvFrame.width * grow, height: tvFrame.height * grow)
    let placed = CIImage(cgImage: cg)
        .transformed(by: CGAffineTransform(scaleX: target.width / CGFloat(cg.width), y: target.height / CGFloat(cg.height)))
        .transformed(by: CGAffineTransform(translationX: target.minX, y: target.minY))
    let light = placed.composited(over: CIImage(color: .clear).cropped(to: canvasRect))
        .applyingGaussianBlur(sigma: Double(W * 0.07 * spread))
        .applyingFilter("CIColorControls", parameters: [kCIInputSaturationKey: 1.4])
        .cropped(to: canvasRect)
    let out = ciContext.createCGImage(light, from: canvasRect)!
    NSImage(cgImage: out, size: NSSize(width: W, height: H))
        .draw(in: canvasRect, from: .zero, operation: .sourceOver, fraction: strength, respectFlipped: true, hints: nil)
    // Keep the headline on true black.
    NSGradient(colorsAndLocations: (NSColor.black, 0.0), (NSColor.black, 0.14), (NSColor.black.withAlphaComponent(0.0), 0.24))!
        .draw(in: canvasRect, angle: 90)
}

// ---- App Store ---------------------------------------------------------------------------------------------

struct Slide { let file, tv, phone, eyebrow, headline, sub: String }

let slides: [Slide] = [
    Slide(file: "1-remote", tv: "home", phone: "remote", eyebrow: "Otv Remote for OpenTV",
          headline: "Your iPhone.\nYour TV remote.", sub: "Swipe, tap and press: every button\nyou need, right in your hand."),
    Slide(file: "2-control-center", tv: "control-center", phone: "remote", eyebrow: "Control Center",
          headline: "Everything,\none press away.", sub: "Power, Wi-Fi, volume, AirPlay and more.\nAlso on your Lock Screen and with Siri."),
    Slide(file: "3-trackpad", tv: "browser", phone: "browser", eyebrow: "Trackpad",
          headline: "Point and click\non the big screen.", sub: "Open a web page on the TV and the remote\nturns into a trackpad by itself."),
    Slide(file: "4-keyboard", tv: "typing", phone: "typing", eyebrow: "Keyboard",
          headline: "Type with the\nkeyboard you know.", sub: "Search, sign in and fill in boxes on the TV\nfrom your iPhone."),
    Slide(file: "5-send-to-tv", tv: "send", phone: "send", eyebrow: "Send to TV",
          headline: "From your phone\nto the big screen.", sub: "Share a web page, a video or a song\nand it opens on the TV."),
    Slide(file: "6-settings", tv: "screensaver", phone: "screensaver", eyebrow: "TV Settings",
          headline: "Set up the TV\nfrom your phone.", sub: "Live wallpapers, screen savers, inputs,\nBluetooth and software updates."),
    Slide(file: "7-cloud-gaming", tv: "xbox", phone: "settings", eyebrow: "Xbox Cloud Gaming",
          headline: "Sign in on your phone.\nPlay on your TV.", sub: "Use your saved passwords. The TV signs\nitself in, encrypted end to end."),
    Slide(file: "8-get-opentv", tv: "pair", phone: "pair", eyebrow: "OpenTV · Free and open source",
          headline: "Pair once.\nYou're all set.", sub: "Get OpenTV for your TV, free, at\nharkhowalia-1.github.io/OpenTV"),
]

func appStore(width: Int, height: Int) {
    for slide in slides {
        let screen = tv(slide.tv)
        let rep = canvas(width, height, background: .black) { W, H in
            let s = W / 1320
            let tvW = W * 0.90
            let tvFrame = NSRect(x: (W - tvW) / 2, y: 640 * s, width: tvW, height: tvW * 9 / 16 + tvW * 0.02)
            drawRoom(screen, tvFrame: tvFrame, W: W, H: H)

            gradientText(slide.eyebrow, size: 40 * s, center: CGPoint(x: W / 2, y: 150 * s))
            let head = text(slide.headline, size: 92 * s, weight: .bold, color: .white, kern: -1.8 * s, line: 0.98)
            head.draw(with: NSRect(x: 40 * s, y: 215 * s, width: W - 80 * s, height: 240 * s), options: [.usesLineFragmentOrigin])
            let sub = text(slide.sub, size: 38 * s, weight: .regular, color: color(0xA1A1A6), line: 1.08)
            sub.draw(with: NSRect(x: 60 * s, y: 448 * s, width: W - 120 * s, height: 120 * s), options: [.usesLineFragmentOrigin])

            drawTV(screen, in: tvFrame, scale: s)
            // The iPhone in front, overlapping the TV's lower right: the remote and what it controls, together.
            _ = drawPhone(phone(slide.phone), screenWidth: 640 * s, centerX: W * 0.64, top: tvFrame.maxY - 230 * s, scale: s)
        }
        save(rep, "\(slide.file).jpg")
    }
}

// ---- website -----------------------------------------------------------------------------------------------

/// Device pictures for the site: TV screens in the TV, and the remote in the iPhone, on black or white.
func web() {
    for name in ["home", "control-center", "cloud", "iptv", "wallsettings", "browser", "screensaver", "wallpaper"] {
        let screen = tv(name)
        let rep = canvas(2000, 1240, background: .black) { W, H in
            let tvW = W * 0.86
            let frame = NSRect(x: (W - tvW) / 2, y: 70, width: tvW, height: tvW * 9 / 16 + tvW * 0.02)
            drawRoom(screen, tvFrame: frame, W: W, H: H, strength: 0.6, spread: 0.8)
            drawTV(screen, in: frame, scale: 1.4)
        }
        save(rep, "tv-\(name).jpg")
    }
    // The hero: the TV with the remote in front, on the page's light grey (#f5f5f7) and, for dark mode, its dark grey.
    for (file, bg, shade) in [("hero", 0xF5F5F7, 0.35), ("hero-dark", 0x161617, 0.8)] as [(String, UInt32, CGFloat)] {
        let hero = canvas(2400, 1560, background: color(bg)) { W, H in
            let tvW = W * 0.78
            let frame = NSRect(x: W * 0.06, y: 60, width: tvW, height: tvW * 9 / 16 + tvW * 0.02)
            drawTV(tv("home"), in: frame, scale: 1.6)
            _ = drawPhone(phone("remote"), screenWidth: 560, centerX: W * 0.80, top: 300, scale: 1.4, shadowAlpha: shade)
        }
        save(hero, "\(file).jpg")
    }
    // Plain screens (no TV around them) for the website's live TV, when you open an app on it.
    for name in ["iptv", "browser", "cloud", "wallsettings", "screensaver"] {
        let screen = tv(name)
        let rep = canvas(1600, 900, background: .black) { W, H in screen.draw(in: NSRect(x: 0, y: 0, width: W, height: H)) }
        save(rep, "app-\(name).jpg")
    }
    for name in ["remote", "browser", "typing", "wallpaper", "screensaver"] {
        let rep = canvas(900, 1800, background: .clear) { W, _ in
            _ = drawPhone(phone(name), screenWidth: 700, centerX: W / 2, top: 60, scale: 1, shadowAlpha: 0.0)
        }
        save(rep, "phone-\(name).png", jpeg: false)
    }
}

if mode == "web" { web() } else {
    appStore(width: Int(args.count > 3 ? args[3] : "1320")!, height: Int(args.count > 4 ? args[4] : "2868")!)
}
print("done: \(outDir)")
