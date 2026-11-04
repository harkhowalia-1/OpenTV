import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Pictures for the TV's wallpaper: scaled down to the TV's width (never up) and saved as JPEG.
enum WallpaperImage {
    static func fit(_ data: Data, width: Int) -> Data {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil) else { return data }
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1280, width),
        ]
        guard let img = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return data }
        return jpeg(img) ?? data
    }

    static func jpeg(_ img: CGImage, quality: Double = 0.9) -> Data? {
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, img, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        return CGImageDestinationFinalize(dest) ? out as Data : nil
    }
}
