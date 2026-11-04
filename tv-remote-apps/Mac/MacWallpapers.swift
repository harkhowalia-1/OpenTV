import AppKit
import ImageIO
import SwiftUI
import UniformTypeIdentifiers

/**
 * macOS's own wallpapers for the TV. The list comes from this Mac (Apple's desktop picture catalog); each one is
 * downloaded from Apple's update servers, the same place System Settings gets it, when you send it. Dynamic
 * wallpapers become a set of pictures with the time of day each belongs to, so the TV changes with the day.
 * Pictures are made at the TV's own resolution (4K on a 4K TV).
 */
@MainActor
final class MacWallpapers: ObservableObject {

    static let shared = MacWallpapers()

    struct Item: Identifiable, Hashable {
        let id: String           // the wallpaper's name
        var url: URL?            // Apple's download (a zip with the HEIC inside)
        var local: URL?          // already on this Mac
        var dynamic = false
        var thumbnail: URL?
        var size = 0
    }

    @Published var items: [Item] = []
    @Published var sending: String?
    @Published var message: String?

    private static let pictures = URL(fileURLWithPath: "/System/Library/Desktop Pictures")
    private static let catalog = URL(fileURLWithPath:
        "/System/Library/AssetsV2/com_apple_MobileAsset_DesktopPicture/com_apple_MobileAsset_DesktopPicture.xml")

    func load() {
        guard items.isEmpty else { return }
        var byName: [String: Item] = [:]
        // Apple's catalog: where each wallpaper downloads from.
        if let plist = NSDictionary(contentsOf: Self.catalog), let assets = plist["Assets"] as? [[String: Any]] {
            for a in assets {
                guard let name = a["DesktopPictureID"] as? String, let base = a["__BaseURL"] as? String,
                      let rel = a["__RelativePath"] as? String else { continue }
                var it = Item(id: name, url: URL(string: base + rel))
                it.size = a["_DownloadSize"] as? Int ?? 0
                byName[name] = it
            }
        }
        let fm = FileManager.default
        for f in (try? fm.contentsOfDirectory(at: Self.pictures, includingPropertiesForKeys: nil)) ?? [] {
            let name = f.deletingPathExtension().lastPathComponent
            if f.pathExtension == "madesktop", let d = NSDictionary(contentsOf: f) {
                var it = byName[name] ?? Item(id: name)
                it.dynamic = d["isDynamic"] as? Bool ?? false
                if let t = d["thumbnailPath"] as? String { it.thumbnail = URL(fileURLWithPath: t) }
                byName[name] = it
            } else if f.pathExtension == "heic" {
                var it = byName[name] ?? Item(id: name)
                it.local = f
                it.thumbnail = it.thumbnail ?? f
                byName[name] = it
            }
        }
        items = byName.values.filter { $0.url != nil || $0.local != nil }
            .sorted { ($0.dynamic ? 0 : 1, $0.id) < ($1.dynamic ? 0 : 1, $1.id) }
    }

    // MARK: Sending

    func send(_ item: Item, to client: RemoteClient) async {
        sending = item.id
        message = nil
        defer { sending = nil }
        do {
            let heic = try await file(for: item)
            let width = await client.screenWidth()
            guard let src = CGImageSourceCreateWithURL(heic as CFURL, nil) else { throw StreamError("Couldn't open \(item.id).") }
            let count = CGImageSourceGetCount(src)
            let times = count > 1 ? Self.times(src, count: count) : []
            if times.count > 1 {
                var packed = Data([0x4F, 0x54, 0x56, 0x44])   // "OTVD"
                packed.be(UInt16(times.count))
                for (index, minute) in times {
                    guard let jpeg = Self.picture(src, index: index, width: width) else { continue }
                    packed.be(UInt16(minute))
                    packed.be(UInt32(jpeg.count))
                    packed.append(jpeg)
                }
                try await client.uploadDynamicWallpaper(packed, name: item.id)
                message = "\(item.id) is on the TV. It changes with the time of day."
            } else {
                guard let jpeg = Self.picture(src, index: 0, width: width) else { throw StreamError("Couldn't read \(item.id).") }
                try await client.uploadWallpaper(jpeg, name: item.id)
                message = "\(item.id) is on the TV."
            }
        } catch {
            message = "\(item.id) couldn't be sent: \(error.localizedDescription)"
        }
    }

    /// A picture from this Mac (any image file) as the TV's wallpaper.
    func sendFile(_ url: URL, to client: RemoteClient) async {
        sending = url.lastPathComponent
        defer { sending = nil }
        guard let data = try? Data(contentsOf: url) else { message = "Couldn't read that file."; return }
        let width = await client.screenWidth()
        do {
            try await client.uploadWallpaper(WallpaperImage.fit(data, width: width), name: url.deletingPathExtension().lastPathComponent)
            message = "\(url.deletingPathExtension().lastPathComponent) is on the TV."
        } catch {
            message = "That picture couldn't be sent."
        }
    }

    /// The HEIC on this Mac, or downloaded from Apple (kept in Caches, so a second send is instant).
    private func file(for item: Item) async throws -> URL {
        if let local = item.local { return local }
        guard let url = item.url else { throw StreamError("No download for \(item.id).") }
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("OpenTV Wallpapers/\(item.id)")
        if let found = Self.heic(in: dir) { return found }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let (zip, _) = try await URLSession.shared.download(from: url)
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
        p.arguments = ["-x", "-k", zip.path, dir.path]
        try p.run()
        p.waitUntilExit()
        try? FileManager.default.removeItem(at: zip)
        guard let found = Self.heic(in: dir) else { throw StreamError("Apple's download had no picture.") }
        return found
    }

    private static func heic(in dir: URL) -> URL? {
        let e = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: nil)
        while let f = e?.nextObject() as? URL {
            if f.pathExtension.lowercased() == "heic" { return f }
        }
        return nil
    }

    /// One picture scaled to the TV's width, as JPEG.
    private static func picture(_ src: CGImageSource, index: Int, width: Int) -> Data? {
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1280, width),
        ]
        guard let img = CGImageSourceCreateThumbnailAtIndex(src, index, opts as CFDictionary) else { return nil }
        return WallpaperImage.jpeg(img)
    }

    /**
     * Which picture belongs to which time of day, from the wallpaper's own metadata: "h24" lists a time for each
     * picture; "solar" lists the sun's position (its compass direction gives the hour: east is morning, south
     * noon, west evening); "apr" is light and dark pictures, shown by day and by night.
     */
    private static func times(_ src: CGImageSource, count: Int) -> [(Int, Int)] {
        guard let meta = CGImageSourceCopyMetadataAtIndex(src, 0, nil) else { return [] }
        func plist(_ key: String) -> [String: Any]? {
            guard let tag = CGImageMetadataCopyTagWithPath(meta, nil, "apple_desktop:\(key)" as CFString),
                  let b64 = CGImageMetadataTagCopyValue(tag) as? String, let data = Data(base64Encoded: b64) else { return nil }
            return try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any]
        }
        var out: [Int: Int] = [:]   // picture -> minute of day
        if let h24 = plist("h24"), let ti = h24["ti"] as? [[String: Any]] {
            for t in ti {
                if let i = t["i"] as? Int, let f = t["t"] as? Double, out[i] == nil { out[i] = Int((f * 1440).rounded()) % 1440 }
            }
        } else if let solar = plist("solar"), let si = solar["si"] as? [[String: Any]] {
            for s in si {
                if let i = s["i"] as? Int, let z = s["z"] as? Double, out[i] == nil {
                    out[i] = Int((z.truncatingRemainder(dividingBy: 360) / 360 * 1440).rounded()) % 1440
                }
            }
        } else if let apr = plist("apr"), let l = apr["l"] as? Int, let d = apr["d"] as? Int {
            out[l] = 7 * 60
            out[d] = 19 * 60
        }
        return out.filter { $0.key < count }.map { ($0.key, $0.value) }.sorted { $0.1 < $1.1 }
    }
}

private extension Data {
    mutating func be<T: FixedWidthInteger>(_ v: T) { Swift.withUnsafeBytes(of: v.bigEndian) { append(contentsOf: $0) } }
}

/// The TV Manager's Wallpapers pane.
struct WallpapersPane: View {
    @ObservedObject var w = MacWallpapers.shared
    @ObservedObject var m = TVManager.shared
    private let columns = [GridItem(.adaptive(minimum: 170), spacing: 16)]

    private var client: RemoteClient? { m.model.route.map { RemoteClient(tv: $0) } }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Wallpapers for the TV").font(.headline)
                Spacer()
                Button {
                    let p = NSOpenPanel()
                    p.allowedContentTypes = [.image]
                    p.message = "Choose a picture for the TV's wallpaper"
                    if p.runModal() == .OK, let url = p.url, let client {
                        Task { await w.sendFile(url, to: client) }
                    }
                } label: { Label("Picture from Mac…", systemImage: "photo") }
            }
            .padding(12)
            ScrollView {
                LazyVGrid(columns: columns, spacing: 18) {
                    ForEach(w.items) { item in
                        Button {
                            if let client { Task { await w.send(item, to: client) } }
                        } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                ZStack(alignment: .topTrailing) {
                                    Thumb(url: item.thumbnail)
                                        .frame(height: 96)
                                        .clipShape(RoundedRectangle(cornerRadius: 10))
                                    if item.dynamic {
                                        Text("Dynamic").font(.caption2.bold())
                                            .padding(.horizontal, 6).padding(.vertical, 2)
                                            .background(.ultraThinMaterial, in: Capsule()).padding(6)
                                    }
                                    if w.sending == item.id { ProgressView().controlSize(.small).padding(8) }
                                }
                                Text(item.id).font(.caption).lineLimit(1)
                            }
                        }
                        .buttonStyle(.plain)
                        .disabled(w.sending != nil || client == nil)
                    }
                }
                .padding(16)
            }
            if let msg = w.message {
                Text(msg).font(.callout).padding(8).frame(maxWidth: .infinity).background(.bar)
            }
        }
        .onAppear { w.load() }
    }
}

private struct Thumb: View {
    let url: URL?
    @State private var image: NSImage?
    var body: some View {
        ZStack {
            Rectangle().fill(.quaternary)
            if let image { Image(nsImage: image).resizable().aspectRatio(contentMode: .fill) }
        }
        .task {
            guard let url else { return }
            image = await Task.detached { () -> NSImage? in
                guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
                      let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                                                         kCGImageSourceThumbnailMaxPixelSize: 360] as CFDictionary)
                else { return nil }
                return NSImage(cgImage: cg, size: .zero)
            }.value
        }
    }
}
