import SwiftUI

/// The remote, laid out like Apple's TV Remote: the TV at the top, a touch surface, then the buttons.
/// Shared by the iPhone app and the Mac menu bar app.
struct RemoteView: View {
    @ObservedObject var model: RemoteModel
    var compact = false            // the Mac menu bar popover

    var body: some View {
        VStack(spacing: compact ? 14 : 20) {
            header
            switch model.phase {
            case .searching:
                SearchingView(model: model)
            case .pairing:
                PairingView(model: model)
            case .connected, .offline:
                controls
            }
        }
        .padding(.horizontal, compact ? 16 : 24)
        .padding(.vertical, compact ? 16 : 12)
        .foregroundStyle(.white)
        .alert("OpenTV", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.error ?? "")
        }
    }

    // MARK: Header

    private var header: some View {
        HStack(spacing: 10) {
            Menu {
                ForEach(model.found) { tv in
                    Button {
                        model.choose(tv)
                    } label: {
                        if tv.host == model.tv?.host { Label(tv.name, systemImage: "checkmark") } else { Text(tv.name) }
                    }
                }
                if model.tv != nil {
                    Divider()
                    Button("Forget This TV", role: .destructive) { model.forget() }
                }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "tv")
                        .font(.system(size: compact ? 15 : 18, weight: .medium))
                    VStack(alignment: .leading, spacing: 1) {
                        Text(model.tv?.name ?? "Choose a TV")
                            .font(.system(size: compact ? 14 : 17, weight: .semibold))
                            .lineLimit(1)
                        if model.tv != nil {
                            HStack(spacing: 5) {
                                Circle().fill(statusColor).frame(width: 6, height: 6)
                                Text(statusText).font(.system(size: compact ? 11 : 12)).foregroundStyle(.secondary)
                            }
                        }
                    }
                    Image(systemName: "chevron.down")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
            }
            .menuStyle(.button)
            .buttonStyle(.plain)
            .menuIndicator(.hidden)
            .fixedSize()

            Spacer()

            if model.phase == .connected || model.phase == .offline {
                RoundButton(symbol: "power", size: compact ? 34 : 44, tint: Color(red: 1, green: 0.27, blue: 0.23)) {
                    model.press(.power)
                }
                .help("Sleep or wake the TV")
            }
        }
    }

    private var statusColor: Color {
        switch model.phase {
        case .connected: return Color(red: 0.19, green: 0.82, blue: 0.35)
        case .offline: return Color(red: 1, green: 0.27, blue: 0.23)
        default: return .orange
        }
    }

    private var statusText: String {
        switch model.phase {
        case .connected: return "Connected"
        case .offline: return "Not responding"
        case .pairing: return "Not connected"
        case .searching: return "Searching"
        }
    }

    // MARK: Remote

    private var controls: some View {
        VStack(spacing: compact ? 14 : 22) {
            if let np = model.nowPlaying { NowPlayingCard(np: np, compact: compact) { model.press($0) } }

            #if os(iOS)
            TouchSurface { model.press($0) }
                .aspectRatio(1, contentMode: .fit)
            #else
            ClickPad(size: 176) { model.press($0) }
                .padding(.vertical, 4)
            #endif

            let s: CGFloat = compact ? 52 : 66
            Grid(horizontalSpacing: compact ? 18 : 26, verticalSpacing: compact ? 14 : 18) {
                GridRow {
                    RoundButton(symbol: "chevron.backward", size: s) { model.press(.back) }.help("Back")
                    RoundButton(symbol: "tv", size: s) { model.press(.home) }.help("Home")
                    RoundButton(symbol: "switch.2", size: s) { model.press(.control) }.help("Control Center")
                }
                GridRow {
                    RoundButton(symbol: "playpause.fill", size: s) { model.press(.playpause) }.help("Play/Pause")
                    RoundButton(symbol: "speaker.slash.fill", size: s) { model.press(.mute) }.help("Mute")
                    KeyboardButton(model: model, size: s)
                }
            }

            VolumeBar(compact: compact) { model.press($0) }
        }
    }
}

// MARK: - Pieces

/// A round, dark button that brightens while pressed.
struct RoundButton: View {
    let symbol: String
    var size: CGFloat = 64
    var tint: Color = .white
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: size * 0.34, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: size, height: size)
                .contentShape(Circle())
        }
        .buttonStyle(PressStyle(shape: AnyShape(Circle())))
    }
}

struct PressStyle: ButtonStyle {
    var shape: AnyShape

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(shape.fill(Color.white.opacity(configuration.isPressed ? 0.24 : 0.11)))
            .scaleEffect(configuration.isPressed ? 0.93 : 1)
            .animation(.spring(response: 0.22, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

/// Volume down, a label, volume up, in one capsule (with previous/next track on either side).
struct VolumeBar: View {
    var compact: Bool
    let press: (RemoteKey) -> Void

    var body: some View {
        let h: CGFloat = compact ? 44 : 56
        HStack(spacing: compact ? 12 : 16) {
            RoundButton(symbol: "backward.fill", size: h) { press(.previous) }.help("Previous")
            HStack(spacing: 0) {
                Button { press(.voldown) } label: {
                    Image(systemName: "minus").frame(maxWidth: .infinity, maxHeight: .infinity).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help("Volume down")
                Image(systemName: "speaker.wave.2.fill").font(.system(size: 13)).foregroundStyle(.secondary)
                Button { press(.volup) } label: {
                    Image(systemName: "plus").frame(maxWidth: .infinity, maxHeight: .infinity).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help("Volume up")
            }
            .font(.system(size: compact ? 16 : 20, weight: .semibold))
            .frame(height: h)
            .background(Capsule().fill(Color.white.opacity(0.11)))
            RoundButton(symbol: "forward.fill", size: h) { press(.next) }.help("Next")
        }
    }
}

struct NowPlayingCard: View {
    let np: NowPlaying
    var compact: Bool
    let press: (RemoteKey) -> Void

    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 8)
                .fill(LinearGradient(colors: [.pink.opacity(0.9), .orange.opacity(0.8)], startPoint: .topLeading, endPoint: .bottomTrailing))
                .frame(width: compact ? 36 : 44, height: compact ? 36 : 44)
                .overlay(Image(systemName: "music.note").font(.system(size: compact ? 15 : 18, weight: .semibold)))
            VStack(alignment: .leading, spacing: 2) {
                Text(np.title).font(.system(size: compact ? 13 : 15, weight: .semibold)).lineLimit(1)
                if !np.artist.isEmpty {
                    Text(np.artist).font(.system(size: compact ? 12 : 13)).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            Button { press(.playpause) } label: {
                Image(systemName: np.playing ? "pause.fill" : "play.fill")
                    .font(.system(size: compact ? 16 : 20))
                    .frame(width: 36, height: 36)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(compact ? 10 : 12)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color.white.opacity(0.08)))
    }
}

/// Opens a text field that types into whatever search or sign-in box is open on the TV.
struct KeyboardButton: View {
    @ObservedObject var model: RemoteModel
    var size: CGFloat
    @State private var open = false
    @State private var text = ""

    var body: some View {
        RoundButton(symbol: "keyboard", size: size) { open = true }
            .help("Type on the TV")
            .popover(isPresented: $open) {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Type on TV").font(.headline)
                    TextField("Search or type", text: $text)
                        .textFieldStyle(.roundedBorder)
                        .onChange(of: text) { _, new in model.type(new) }
                        .onSubmit { model.press(.select); open = false }
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        #endif
                    Text("Text appears in the box that's selected on your TV.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                .padding(18)
                .frame(minWidth: 280)
                .presentationCompactAdaptation(.sheet)
                .presentationDetents([.height(170)])
                .onAppear { text = "" }
            }
    }
}

#if os(iOS)
/// The touch surface: swipe to move (a long swipe moves several steps), tap to select.
struct TouchSurface: View {
    let press: (RemoteKey) -> Void
    @State private var travelled: CGSize = .zero
    @State private var touching = false
    @State private var dot: CGPoint?

    private let step: CGFloat = 54

    var body: some View {
        GeometryReader { geo in
            ZStack {
                RoundedRectangle(cornerRadius: 30, style: .continuous)
                    .fill(Color.white.opacity(touching ? 0.13 : 0.09))
                if let dot {
                    Circle()
                        .fill(Color.white.opacity(0.18))
                        .frame(width: 64, height: 64)
                        .position(dot)
                        .blur(radius: 6)
                }
                if !touching {
                    VStack(spacing: 6) {
                        Image(systemName: "hand.draw").font(.system(size: 26, weight: .light))
                        Text("Swipe to navigate, tap to select").font(.footnote)
                    }
                    .foregroundStyle(.white.opacity(0.28))
                }
            }
            .contentShape(RoundedRectangle(cornerRadius: 30))
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { g in
                        touching = true
                        dot = g.location
                        let dx = g.translation.width - travelled.width
                        let dy = g.translation.height - travelled.height
                        if abs(dx) > step && abs(dx) > abs(dy) {
                            press(dx > 0 ? .right : .left)
                            travelled.width += dx > 0 ? step : -step
                            travelled.height = g.translation.height
                        } else if abs(dy) > step {
                            press(dy > 0 ? .down : .up)
                            travelled.height += dy > 0 ? step : -step
                            travelled.width = g.translation.width
                        }
                    }
                    .onEnded { g in
                        let moved = hypot(g.translation.width, g.translation.height)
                        if moved < 12 && travelled == .zero { press(.select) }
                        travelled = .zero
                        withAnimation(.easeOut(duration: 0.25)) { touching = false; dot = nil }
                    }
            )
            .frame(width: geo.size.width, height: geo.size.height)
        }
    }
}
#endif

/// Siri Remote style click pad for the Mac: click the ring's edges to move, the centre to select.
struct ClickPad: View {
    var size: CGFloat
    let press: (RemoteKey) -> Void

    var body: some View {
        ZStack {
            Circle().fill(Color.white.opacity(0.10))
            arrow(.up, "chevron.up", x: 0, y: -1)
            arrow(.down, "chevron.down", x: 0, y: 1)
            arrow(.left, "chevron.left", x: -1, y: 0)
            arrow(.right, "chevron.right", x: 1, y: 0)
            Button { press(.select) } label: {
                Circle().fill(Color.clear).frame(width: size * 0.44, height: size * 0.44).contentShape(Circle())
            }
            .buttonStyle(PressStyle(shape: AnyShape(Circle())))
            .help("Select")
        }
        .frame(width: size, height: size)
    }

    private func arrow(_ key: RemoteKey, _ symbol: String, x: CGFloat, y: CGFloat) -> some View {
        Button { press(key) } label: {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(.white.opacity(0.75))
                .frame(width: size * 0.3, height: size * 0.3)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .offset(x: x * size * 0.36, y: y * size * 0.36)
    }
}

// MARK: - Finding and pairing

struct SearchingView: View {
    @ObservedObject var model: RemoteModel
    @State private var address = ""

    var body: some View {
        VStack(spacing: 18) {
            Spacer(minLength: 0)
            Image(systemName: "appletvremote.gen4.fill")
                .font(.system(size: 54, weight: .light))
                .foregroundStyle(.white.opacity(0.85))
            Text(model.found.isEmpty ? "Looking for your TV…" : "Choose your TV")
                .font(.system(size: 20, weight: .semibold))
            if model.found.isEmpty {
                ProgressView().controlSize(.small)
                Text("Make sure the TV is on and connected to the same Wi-Fi.")
                    .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
            VStack(spacing: 8) {
                ForEach(model.found) { tv in
                    Button { model.choose(tv) } label: {
                        HStack {
                            Image(systemName: "tv")
                            Text(tv.name).fontWeight(.medium)
                            Spacer()
                            Image(systemName: "chevron.right").foregroundStyle(.secondary)
                        }
                        .padding(.horizontal, 16)
                        .frame(height: 50)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(PressStyle(shape: AnyShape(RoundedRectangle(cornerRadius: 14))))
                }
            }
            Spacer(minLength: 0)
            HStack {
                TextField("Or type the TV's address", text: $address)
                    .textFieldStyle(.plain)
                    #if os(iOS)
                    .keyboardType(.numbersAndPunctuation)
                    #endif
                    .onSubmit { if !address.isEmpty { model.add(host: address) } }
                Button("Connect") { model.add(host: address) }
                    .disabled(address.isEmpty)
            }
            .padding(.horizontal, 14)
            .frame(height: 44)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color.white.opacity(0.08)))
        }
    }
}

struct PairingView: View {
    @ObservedObject var model: RemoteModel
    @State private var code = ""
    @State private var codeShown = false
    @State private var busy = false
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 18) {
            Spacer(minLength: 0)
            Image(systemName: "appletvremote.gen4.fill")
                .font(.system(size: 50, weight: .light))
                .foregroundStyle(.white.opacity(0.85))
            Text("Connect to \(model.tv?.name ?? "TV")")
                .font(.system(size: 20, weight: .semibold))
                .multilineTextAlignment(.center)
            Text(codeShown ? "Enter the code shown on your TV." : "A code will appear on your TV. You only do this once.")
                .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)

            if codeShown {
                ZStack {
                    HStack(spacing: 12) {
                        ForEach(0..<4, id: \.self) { i in
                            let chars = Array(code)
                            RoundedRectangle(cornerRadius: 12)
                                .fill(Color.white.opacity(0.1))
                                .frame(width: 52, height: 62)
                                .overlay(Text(i < chars.count ? String(chars[i]) : "").font(.system(size: 30, weight: .medium)))
                                .overlay(RoundedRectangle(cornerRadius: 12)
                                    .stroke(i == chars.count && focused ? Color.white.opacity(0.6) : .clear, lineWidth: 1.5))
                        }
                    }
                    TextField("", text: $code)
                        #if os(iOS)
                        .keyboardType(.numberPad)
                        .textContentType(.oneTimeCode)
                        #endif
                        .focused($focused)
                        .foregroundStyle(.clear)
                        .tint(.clear)
                        .textFieldStyle(.plain)
                        .frame(width: 244, height: 62)
                        .opacity(0.02)
                        .onChange(of: code) { _, new in
                            let digits = String(new.filter(\.isNumber).prefix(4))
                            if digits != new { code = digits }
                            if digits.count == 4 { submit(digits) }
                        }
                }
                .onTapGesture { focused = true }
                if busy { ProgressView().controlSize(.small) }
            }

            Button {
                Task {
                    busy = true
                    await model.showCode()
                    busy = false
                    code = ""
                    codeShown = true
                    focused = true
                }
            } label: {
                Text(codeShown ? "Show a New Code" : "Show Code on TV")
                    .font(.system(size: 16, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressStyle(shape: AnyShape(Capsule())))
            .keyboardShortcut(.defaultAction)
            Spacer(minLength: 0)
        }
    }

    private func submit(_ digits: String) {
        busy = true
        Task {
            if !(await model.pair(code: digits)) { code = "" }
            busy = false
        }
    }
}
