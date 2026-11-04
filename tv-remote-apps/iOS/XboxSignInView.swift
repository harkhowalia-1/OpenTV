import CryptoKit
import SwiftUI
import WebKit

/// Signing the TV in to Xbox Cloud Gaming from the iPhone: Microsoft's sign-in runs here, where iOS fills in
/// saved passwords (the key in the keyboard bar) and codes are easy to type, then the signed-in session goes to
/// the TV, encrypted end to end. The TV signs itself in; nothing is typed on the TV.
struct XboxSignInView: View {
    let client: RemoteClient
    @Environment(\.dismiss) private var dismiss
    @StateObject private var browser = XboxBrowser()

    var body: some View {
        NavigationStack {
            ZStack {
                XboxWebView(browser: browser).ignoresSafeArea(edges: .bottom)
                if case .done(let tag) = browser.state {
                    VStack(spacing: 14) {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.system(size: 56))
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, .green)
                        Text(tag.isEmpty ? "Your TV is signed in" : "Your TV is signed in as \(tag)")
                            .font(.title3.weight(.semibold))
                            .multilineTextAlignment(.center)
                        Text("Your games are ready on the TV.").foregroundStyle(.secondary)
                    }
                    .padding(32)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(.ultraThickMaterial)
                    .transition(.opacity)
                }
            }
            .safeAreaInset(edge: .bottom) {
                if let hint = browser.hint {
                    Text(hint)
                        .font(.footnote.weight(.medium))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 14).padding(.vertical, 9)
                        .background(.regularMaterial, in: Capsule())
                        .padding(.bottom, 8)
                }
            }
            .navigationTitle("Xbox Sign In")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(browser.isDone ? "Done" : "Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if !browser.isDone {
                        Button {
                            Task { await browser.sendToTV(client) }
                        } label: {
                            if case .sending = browser.state { ProgressView() } else { Text("Send to TV") }
                        }
                        .disabled(!browser.canSend)
                    }
                }
            }
            .animation(.easeInOut, value: browser.isDone)
        }
        .onAppear { browser.client = client }
        .onChange(of: browser.isDone) { _, done in
            if done {
                Haptics.tap()
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.2) { dismiss() }
            }
        }
    }
}

/// The browser behind the sign-in: a private one (nothing stays on the phone afterwards).
@MainActor
final class XboxBrowser: NSObject, ObservableObject, WKNavigationDelegate {
    enum State: Equatable { case signingIn, sending, done(String) }

    @Published var state: State = .signingIn
    @Published var hint: String? = "Sign in with your Microsoft account. Tap the key above the keyboard to use a saved password."
    @Published var canSend = false
    var client: RemoteClient?
    var isDone: Bool { if case .done = state { return true } else { return false } }

    let webView: WKWebView
    private var visitedLogin = false
    private var gamertag = ""
    private var gamerpic = ""

    override init() {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        webView = WKWebView(frame: .zero, configuration: config)
        super.init()
        webView.navigationDelegate = self
        let lang = (Locale.current.identifier.components(separatedBy: "@").first ?? "en_US").replacingOccurrences(of: "_", with: "-")
        let back = "https://www.xbox.com/\(lang)/play".addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""
        webView.load(URLRequest(url: URL(string: "https://www.xbox.com/\(lang)/auth/msa?action=logIn&returnUrl=\(back)")!))
    }

    nonisolated func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        Task { @MainActor in await self.pageLoaded() }
    }

    private func pageLoaded() async {
        guard let host = webView.url?.host else { return }
        if host.contains("login.") {
            visitedLogin = true
            return
        }
        guard host.hasSuffix("xbox.com"), visitedLogin else { return }
        // Back on Xbox after signing in: wait for the page to load the profile, then hand it over by itself.
        canSend = true
        hint = "Signed in. Sending to your TV…"
        for _ in 0..<8 {
            await probeProfile()
            if !gamertag.isEmpty { break }
            try? await Task.sleep(for: .seconds(1))
        }
        if let client, state == .signingIn { await sendToTV(client) }
    }

    /// The gamertag and gamerpic, from the signed-in Xbox page.
    private func probeProfile() async {
        let js = """
        (function(){var o={};function walk(x,d){if(!x||typeof x!=='object'||d>6)return;for(var k in x){var v=x[k];
        if(/^gamertag$|^modernGamertag$/i.test(k)&&typeof v==='string'&&!o.gamertag)o.gamertag=v;
        if(/gamerpic|displaypic|avatarurl/i.test(k)&&typeof v==='string'&&/^https?:/.test(v)&&!o.pic)o.pic=v;walk(v,d+1);}}
        for(var i=0;i<localStorage.length;i++){var s=localStorage.getItem(localStorage.key(i))||'';
        if(/gamertag/i.test(s)){try{walk(JSON.parse(s),0);}catch(e){}}}return JSON.stringify(o);})()
        """
        guard let s = try? await webView.evaluateJavaScript(js) as? String,
              let o = try? JSONSerialization.jsonObject(with: Data(s.utf8)) as? [String: String] else { return }
        gamertag = o["gamertag"] ?? gamertag
        gamerpic = o["pic"] ?? gamerpic
    }

    /// Microsoft's and Xbox's cookies and the Xbox site's saved session, sealed for the TV only.
    func sendToTV(_ client: RemoteClient) async {
        state = .sending
        hint = "Sending to your TV…"
        let wanted = ["live.com", "xbox.com", "xboxlive.com", "microsoft.com", "microsoftonline.com"]
        let cookies = await webView.configuration.websiteDataStore.httpCookieStore.allCookies()
            .filter { c in wanted.contains { c.domain.hasSuffix($0) } }
            .map { c -> [String: Any] in
                ["name": c.name, "value": c.value, "domain": c.domain, "path": c.path,
                 "expires": Int(c.expiresDate?.timeIntervalSince1970 ?? 0), "secure": c.isSecure, "httpOnly": c.isHTTPOnly]
            }
        var storage: [String: String] = [:]
        if webView.url?.host?.hasSuffix("xbox.com") == true,
           let s = try? await webView.evaluateJavaScript("JSON.stringify(Object.assign({}, localStorage))") as? String,
           let o = try? JSONSerialization.jsonObject(with: Data(s.utf8)) as? [String: String] {
            storage = o
        }
        let session: [String: Any] = ["cookies": cookies, "storage": storage, "gamertag": gamertag, "gamerpic": gamerpic]
        do {
            let tag = try await client.sendXboxSession(session)
            state = .done(tag.isEmpty ? gamertag : tag)
            hint = nil
        } catch {
            state = .signingIn
            hint = "Couldn't reach your TV. Make sure it's on, then tap Send to TV."
        }
    }
}

private struct XboxWebView: UIViewRepresentable {
    @ObservedObject var browser: XboxBrowser
    func makeUIView(context: Context) -> WKWebView { browser.webView }
    func updateUIView(_ view: WKWebView, context: Context) {}
}

extension RemoteClient {
    /// Seals the session for this TV (ECDH P-256 + HKDF-SHA256 + AES-GCM, a fresh key pair each time) and sends it.
    func sendXboxSession(_ session: [String: Any]) async throws -> String {
        let k = try await tv("xbox/key", [:])
        guard let id = k["id"] as? String, let b64 = k["key"] as? String, let der = Data(base64Encoded: b64) else {
            throw RemoteError.unreachable
        }
        let tvKey = try P256.KeyAgreement.PublicKey(derRepresentation: der)
        let mine = P256.KeyAgreement.PrivateKey()
        let secret = try mine.sharedSecretFromKeyAgreement(with: tvKey)
        let key = secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data(), sharedInfo: Data("OpenTV Xbox sign-in".utf8),
                                                 outputByteCount: 32)
        let plain = try JSONSerialization.data(withJSONObject: session)
        guard let sealed = try AES.GCM.seal(plain, using: key).combined else { throw RemoteError.unreachable }
        let r = try await tv("xbox/session", ["id": id, "key": mine.publicKey.derRepresentation.base64EncodedString(),
                                             "sealed": sealed.base64EncodedString()])
        guard r["ok"] as? Bool == true else { throw RemoteError.unreachable }
        return r["gamertag"] as? String ?? ""
    }
}
