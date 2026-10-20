import AppKit

/// The menu bar icon: the OpenTV glyph, a TV with a tinted screen and a base bar, as a template image so it
/// follows the menu bar's light or dark look. While the TV is showing this Mac, the screen fills in.
enum TrayIcon {
    static func image(showing: Bool) -> NSImage {
        let image = NSImage(size: CGSize(width: 22, height: 17), flipped: false) { _ in
            let bezel = NSBezierPath(roundedRect: CGRect(x: 1.5, y: 4.5, width: 19, height: 11.5), xRadius: 2.6, yRadius: 2.6)
            NSColor.black.set()
            bezel.lineWidth = 1.8
            bezel.stroke()
            // The screen: half-tone normally, solid while streaming.
            NSColor.black.withAlphaComponent(showing ? 1 : 0.45).setFill()
            NSBezierPath(roundedRect: CGRect(x: 3.6, y: 6.6, width: 14.8, height: 7.3), xRadius: 1.2, yRadius: 1.2).fill()
            NSColor.black.setFill()
            NSBezierPath(roundedRect: CGRect(x: 6.5, y: 0.8, width: 9, height: 1.9), xRadius: 0.95, yRadius: 0.95).fill()
            return true
        }
        image.isTemplate = true
        return image
    }
}
