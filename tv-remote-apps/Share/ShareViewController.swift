import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// "Send to TV" in the iPhone's Share sheet: a YouTube video, a Netflix or Spotify link, a video file or any
/// web page opens on the TV, and the iPhone is free again.
final class ShareViewController: UIViewController {

    private let state = SendState()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        let host = UIHostingController(rootView: SendCard(state: state))
        host.view.backgroundColor = .clear
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        Task { await send() }
    }

    private func send() async {
        guard let tv = TVStore.tv, TVStore.token(for: tv) != nil else {
            state.text = "Open Otv Remote and connect to your TV first."
            return finish(after: 2.5)
        }
        state.tvName = tv.name
        guard let url = await sharedURL() else {
            state.text = "There's no link to send."
            return finish(after: 2)
        }
        do {
            if let opened = try await RemoteClient(tv: tv).open(url) {
                state.text = opened == "Browser" ? "Opened on \(tv.name)" : "Playing in \(opened) on \(tv.name)"
                state.done = true
            } else {
                state.text = "The TV couldn't open this."
            }
        } catch {
            state.text = error.localizedDescription
        }
        finish(after: 1.4)
    }

    /// The shared web link, or the first link inside shared text (some apps share "Title https://…").
    private func sharedURL() async -> URL? {
        let items = (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
        for provider in items.flatMap({ $0.attachments ?? [] }) {
            if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier),
               let url = try? await provider.loadItem(forTypeIdentifier: UTType.url.identifier) as? URL,
               url.scheme?.hasPrefix("http") == true {
                return url
            }
            if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier),
               let text = try? await provider.loadItem(forTypeIdentifier: UTType.plainText.identifier) as? String,
               let match = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
                   .firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
               let url = match.url {
                return url
            }
        }
        return nil
    }

    private func finish(after seconds: Double) {
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
            self.extensionContext?.completeRequest(returningItems: nil)
        }
    }
}

@MainActor
final class SendState: ObservableObject {
    @Published var tvName = "TV"
    @Published var text = "Sending…"
    @Published var done = false
}

private struct SendCard: View {
    @ObservedObject var state: SendState

    var body: some View {
        VStack {
            Spacer()
            HStack(spacing: 14) {
                Image(systemName: state.done ? "checkmark.circle.fill" : "tv")
                    .font(.system(size: 28, weight: .medium))
                    .foregroundStyle(state.done ? .green : .white)
                    .contentTransition(.symbolEffect(.replace))
                VStack(alignment: .leading, spacing: 2) {
                    Text("Send to \(state.tvName)").font(.headline)
                    Text(state.text).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                }
                Spacer()
            }
            .padding(18)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
            .environment(\.colorScheme, .dark)
        }
    }
}
