import AVFoundation
import Foundation
import Observation

/**
 * French text-to-speech. The music ducks (or pauses, setting) while it speaks ([AudioFocus]); an
 * urgent alert drops what is still queued, so the driver never hears a stale one. No French voice
 * on the phone: nothing is spoken (the beeps still sound) and the settings say so. Main thread.
 */
final class Speaker: NSObject, AVSpeechSynthesizerDelegate {
    /** No French voice on this phone (seen at the last drive): the settings say how to get one. */
    static var frenchMissing: Bool { VoiceStatus.shared.frenchMissing }

    private let synth = AVSpeechSynthesizer()
    private let voice: AVSpeechSynthesisVoice?
    /** Utterances queued or speaking → whether each one paused the music. */
    private var queued: [ObjectIdentifier: Bool] = [:]

    /** A spoken alert is queued or under way (danger beeps wait for it). */
    var speaking: Bool { !queued.isEmpty }

    /** Looked up once (listing the voices is slow): warmed up off the main thread at launch. */
    static let frenchVoice: AVSpeechSynthesisVoice? = bestFrenchVoice()

    /** Finds the voice in the background, so "Démarrer" never waits for it. */
    static func prewarm() {
        DispatchQueue.global(qos: .utility).async {
            let missing = frenchVoice == nil
            DispatchQueue.main.async { VoiceStatus.shared.frenchMissing = missing }
        }
    }

    override init() {
        voice = Self.frenchVoice
        super.init()
        VoiceStatus.shared.frenchMissing = voice == nil
        synth.usesApplicationAudioSession = true
        synth.delegate = self
    }

    /**
     * Speaks [text] after [delayMs] of silence (to let an alert tone finish first). [urgent] drops
     * the queue; [pauseMusic] pauses the music rather than lowering it.
     */
    func say(_ text: String, delayMs: Int = 0, urgent: Bool = false, pauseMusic: Bool = false) {
        guard let voice else { return }
        if urgent && synth.isSpeaking {
            // didCancel releases what was queued.
            synth.stopSpeaking(at: .immediate)
        }
        let u = AVSpeechUtterance(string: text)
        u.voice = voice
        u.rate = AVSpeechUtteranceDefaultSpeechRate * 1.05
        u.volume = 1
        // Without a tone before it, a Bluetooth output needs a moment to wake up.
        let wait = delayMs == 0 && AudioFocus.shared.bluetooth ? AudioFocus.bluetoothLeadMs : delayMs
        u.preUtteranceDelay = Double(wait) / 1000
        queued[ObjectIdentifier(u)] = pauseMusic
        AudioFocus.shared.acquire(pause: pauseMusic)
        synth.speak(u)
    }

    func shutdown() {
        synth.stopSpeaking(at: .immediate)
        for paused in queued.values { AudioFocus.shared.release(pause: paused) }
        queued.removeAll()
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        finished(utterance)
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        finished(utterance)
    }

    private func finished(_ u: AVSpeechUtterance) {
        let run = { [weak self] in
            guard let self, let paused = self.queued.removeValue(forKey: ObjectIdentifier(u)) else { return }
            AudioFocus.shared.release(pause: paused)
        }
        if Thread.isMainThread { run() } else { DispatchQueue.main.async(execute: run) }
    }

    /** French from France first, the best quality installed (premium, enhanced, then default). */
    static func bestFrenchVoice() -> AVSpeechSynthesisVoice? {
        let french = AVSpeechSynthesisVoice.speechVoices().filter { $0.language.hasPrefix("fr") }
        func rank(_ v: AVSpeechSynthesisVoice) -> Int {
            let quality: Int
            switch v.quality {
            case .premium: quality = 3
            case .enhanced: quality = 2
            default: quality = 1
            }
            return quality * 2 + (v.language == "fr-FR" ? 1 : 0)
        }
        return french.max { rank($0) < rank($1) } ?? AVSpeechSynthesisVoice(language: "fr-FR")
    }
}

/** Whether a French voice exists, for the settings. */
@Observable
final class VoiceStatus {
    static let shared = VoiceStatus()
    var frenchMissing = false
}
