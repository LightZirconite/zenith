import AVFoundation
import Foundation

/**
 * Makes room for Zenith's sounds, like a navigation app (Android's AudioDucker). The music ducks
 * under the beeps and the voice: iOS slides it down when Zenith's session activates with
 * duckOthers, and back up when it deactivates, a little after the last sound so close alerts do not
 * make it pump. A voice may pause it instead (setting): ducked first, then interrupted (Spotify,
 * Apple Music pause), and given back on deactivation (they resume). One owner for tones, voice and
 * take-off, so one never releases the other's. Main thread only.
 */
final class AudioFocus {
    static let shared = AudioFocus()

    private enum Held { case none, duck, pause }

    private let session = AVAudioSession.sharedInstance()
    private var holders = 0
    private var pausers = 0
    private var held = Held.none
    private var duckedAt = Date.distantPast
    private var abandon: DispatchWorkItem?
    private var toPause: DispatchWorkItem?
    private var toDuck: DispatchWorkItem?

    static let holdMs = 1_500
    /** iOS's own duck ramp: the pause waits for the bottom of it. */
    static let fadeMs = 650
    /** Silence played before a sound on Bluetooth or CarPlay, which swallow the first samples. */
    static let bluetoothLeadMs = 300

    /** [pause]: the music must stop rather than duck (spoken alerts, setting; the take-off). */
    func acquire(pause: Bool = false) {
        holders += 1
        cancel(&abandon)
        if held == .none { activate(.duck) }
        guard pause else { return }
        pausers += 1
        cancel(&toDuck)
        if held != .pause {
            // Ducked first: the music is already low when it stops, never a hard cut.
            cancel(&toPause)
            let wait = max(0, Double(Self.fadeMs) / 1000 - Date().timeIntervalSince(duckedAt))
            toPause = schedule(after: wait) { [weak self] in self?.pauseNow() }
        }
    }

    /**
     * The music comes back [holdMs] after the last holder is done, unless another sound starts. The
     * last pausing holder gone while others remain, it plays on ducked after the same wait.
     */
    func release(pause: Bool = false, holdMs: Int = AudioFocus.holdMs) {
        holders = max(0, holders - 1)
        if pause { pausers = max(0, pausers - 1) }
        if pausers == 0 { cancel(&toPause) }
        if holders == 0 {
            cancel(&toDuck)
            cancel(&abandon)
            abandon = schedule(after: Double(holdMs) / 1000) { [weak self] in self?.releaseNow(tries: 0) }
        } else if pause && pausers == 0 {
            toDuck = schedule(after: Double(holdMs) / 1000) { [weak self] in self?.duckNow() }
        }
    }

    /** Ducks for a sound of [durationMs] starting now, the music back [holdMs] after it. */
    func duckFor(durationMs: Int, holdMs: Int = AudioFocus.holdMs) {
        acquire()
        DispatchQueue.main.asyncAfter(deadline: .now() + Double(durationMs) / 1000) { [weak self] in
            self?.release(holdMs: holdMs)
        }
    }

    /** Bluetooth or CarPlay output: sounds start after a short silence. Checked at each sound. */
    var bluetooth: Bool {
        session.currentRoute.outputs.contains { [.bluetoothA2DP, .bluetoothLE, .bluetoothHFP, .carAudio].contains($0.portType) }
    }

    /**
     * A mixable session (never touches the music) for sounds that must just play: button sounds,
     * the small window, the Canvas video. Nothing changes while an alert holds the session.
     */
    func ensureActive() {
        guard held == .none else { return }
        do {
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)
        } catch {
            NSLog("Zenith audio: mixable session: \(error)")
        }
    }

    /** End of a drive: everything given back now. */
    func shutdown() {
        cancel(&abandon)
        cancel(&toPause)
        cancel(&toDuck)
        holders = 0
        pausers = 0
        releaseNow(tries: 0)
    }

    // MARK: -

    private func activate(_ wanted: Held) {
        do {
            switch wanted {
            case .duck:
                try session.setCategory(.playback, mode: .voicePrompt, options: [.duckOthers, .interruptSpokenAudioAndMixWithOthers])
                duckedAt = Date()
            case .pause:
                // Not mixable: the other app is interrupted (and resumes when this session ends).
                try session.setCategory(.playback, mode: .voicePrompt, options: [])
            case .none:
                return
            }
            try session.setActive(true)
            held = wanted
        } catch {
            // In the background iOS may refuse to interrupt another app: the music stays ducked.
            NSLog("Zenith audio: \(wanted): \(error)")
            if wanted == .pause, held == .none { activate(.duck) }
        }
    }

    private func pauseNow() {
        if pausers > 0 && held != .pause { activate(.pause) }
    }

    /** Back from pause to duck: the paused player resumes on the hand-over, then ducks at once. */
    private func duckNow() {
        guard pausers == 0, holders > 0, held == .pause else { return }
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
        held = .none
        activate(.duck)
    }

    private func releaseNow(tries: Int) {
        guard holders == 0, held != .none else { return }
        // Stop ducking first (works while other sounds still play), then hand the audio back.
        try? session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
        do {
            try session.setActive(false, options: .notifyOthersOnDeactivation)
            held = .none
        } catch {
            // A sound still playing (button, video): tried again in a moment.
            if tries < 8 {
                abandon = schedule(after: 0.4) { [weak self] in self?.releaseNow(tries: tries + 1) }
            } else {
                held = .none
            }
        }
    }

    private func schedule(after seconds: Double, _ block: @escaping () -> Void) -> DispatchWorkItem {
        let work = DispatchWorkItem(block: block)
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: work)
        return work
    }

    private func cancel(_ work: inout DispatchWorkItem?) {
        work?.cancel()
        work = nil
    }
}
