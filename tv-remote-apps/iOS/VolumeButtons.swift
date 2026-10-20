import AVFoundation
import MediaPlayer
import SwiftUI

/// Turns the iPhone's volume buttons into TV volume buttons while the remote is open. The phone's own volume
/// is put back after each press, and restored when the remote closes.
@MainActor
final class VolumeButtons: NSObject, ObservableObject {
    var onPress: ((RemoteKey) -> Void)?

    private var observation: NSKeyValueObservation?
    private var original: Float?
    private var resetting = false
    private var baseline: Float = 0.5

    func start(enabled: Bool) {
        guard enabled else { stop(); return }
        guard observation == nil else { return }
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.ambient, options: [.mixWithOthers])
        try? session.setActive(true)
        original = session.outputVolume
        // At the very top or bottom a press wouldn't register, so start from the middle.
        baseline = session.outputVolume
        if baseline > 0.9 || baseline < 0.1 {
            baseline = 0.5
            set(baseline)
        }
        observation = session.observe(\.outputVolume, options: [.new]) { [weak self] _, change in
            guard let v = change.newValue else { return }
            Task { @MainActor in self?.changed(to: v) }
        }
    }

    func stop() {
        observation?.invalidate()
        observation = nil
        if let original { set(original) }
        original = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
    }

    private func changed(to v: Float) {
        if resetting { resetting = false; return }
        if abs(v - baseline) < 0.001 { return }
        onPress?(v > baseline ? .volup : .voldown)
        set(baseline)
    }

    private func set(_ v: Float) {
        resetting = true
        HiddenSlider.slider?.value = v
    }

    /// A hidden system volume view: it lets the app set the volume and keeps the volume HUD from showing.
    struct HiddenSlider: UIViewRepresentable {
        @MainActor static weak var slider: UISlider?

        func makeUIView(context: Context) -> MPVolumeView {
            let v = MPVolumeView(frame: CGRect(x: -100, y: -100, width: 10, height: 10))
            HiddenSlider.slider = v.subviews.compactMap { $0 as? UISlider }.first
            return v
        }

        func updateUIView(_ v: MPVolumeView, context: Context) {
            if HiddenSlider.slider == nil { HiddenSlider.slider = v.subviews.compactMap { $0 as? UISlider }.first }
        }
    }
}
