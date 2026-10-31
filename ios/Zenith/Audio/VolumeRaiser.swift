import AVFoundation
import MediaPlayer
import UIKit
import ZenithCore

/**
 * Alerts must be heard: a low volume goes up to 60 % for the drive and back where it was at the
 * end, unless the driver set it meanwhile (core DriveVolume, on iOS's 16 volume steps). iOS has no
 * API to set the volume: the system volume slider of an off-screen MPVolumeView does it (needs
 * Zenith in front, which a drive started by hand is). Main thread.
 */
final class VolumeRaiser {
    static let shared = VolumeRaiser()

    private static let steps: Int32 = 16
    private var raised: (before: Int32, to: Int32)?
    private lazy var volumeView: MPVolumeView = {
        let v = MPVolumeView(frame: CGRect(x: -2000, y: -2000, width: 10, height: 10))
        v.alpha = 0.01
        v.isUserInteractionEnabled = false
        return v
    }()

    func raise() {
        guard SettingsStore.shared.raiseVolume else { return }
        AudioFocus.shared.ensureActive()
        let now = currentStep()
        let target = Bridge.shared.raiseVolumeTo(current: now, max: Self.steps)
        guard target >= 0 else { return }
        set(step: target)
        raised = (now, target)
    }

    func restore() {
        guard let (before, to) = raised else { return }
        raised = nil
        let back = Bridge.shared.restoreVolumeTo(before: before, raisedTo: to, now: currentStep())
        if back >= 0 { set(step: back) }
    }

    private func currentStep() -> Int32 {
        Int32((AVAudioSession.sharedInstance().outputVolume * Float(Self.steps)).rounded())
    }

    private func set(step: Int32) {
        let window = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first
        if volumeView.superview == nil { window?.addSubview(volumeView) }
        let value = Float(step) / Float(Self.steps)
        // The slider exists once the view is in a window, a moment later.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
            guard let slider = self?.volumeView.subviews.compactMap({ $0 as? UISlider }).first else { return }
            slider.setValue(value, animated: false)
            slider.sendActions(for: .valueChanged)
            // Left in the window, it would hide iOS's volume display and seem to take the buttons.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { self?.volumeView.removeFromSuperview() }
        }
    }
}
