import AVFoundation
import Foundation

/** One note of a tone; 0 Hz is a silence. */
struct Note {
    let hz: Double
    let ms: Int
}

/** Alert sounds, synthesized once (no audio assets), the same as on Android. */
enum Tone: CaseIterable {
    /** Radar ahead: bright two-note rising chime. */
    case approach
    /** Repeated while over the limit near a radar, faster as it gets closer. */
    case danger
    /** Limit exceeded: two short beeps. */
    case overspeed
    /** Strong braking needed: three fast high beeps. */
    case brake
    /** Red light or level crossing camera ahead, rain, control itinerary: softer chime. */
    case chime
    /** Radar passed: soft falling pair. */
    case passed

    var notes: [Note] {
        switch self {
        case .approach: return [Note(hz: 988, ms: 130), Note(hz: 1319, ms: 220)]
        case .danger: return [Note(hz: 1568, ms: 90)]
        case .overspeed: return [Note(hz: 1175, ms: 70), Note(hz: 0, ms: 60), Note(hz: 1175, ms: 70)]
        case .brake: return [Note(hz: 1760, ms: 60), Note(hz: 0, ms: 40), Note(hz: 1760, ms: 60), Note(hz: 0, ms: 40), Note(hz: 1760, ms: 60)]
        case .chime: return [Note(hz: 784, ms: 140), Note(hz: 988, ms: 220)]
        case .passed: return [Note(hz: 1319, ms: 110), Note(hz: 988, ms: 170)]
        }
    }
}

/** Plays the tones; the music ducks under them ([AudioFocus]). Main thread. */
final class TonePlayer {
    private var players: [Tone: AVAudioPlayer] = [:]
    /** Same tones after a short silence, for a Bluetooth output that swallows its first samples. */
    private var bluetoothPlayers: [Tone: AVAudioPlayer] = [:]

    init() {
        for tone in Tone.allCases { players[tone] = Synth.player(Self.synthesize(tone.notes, leadMs: 0)) }
    }

    func play(_ tone: Tone) {
        let focus = AudioFocus.shared
        let bluetooth = focus.bluetooth
        let length = durationMs(tone, bluetooth: bluetooth)
        // Danger beeps come up to 2 s apart: the music stays down between them instead of pumping.
        focus.duckFor(durationMs: length, holdMs: tone == .danger ? 2_600 : AudioFocus.holdMs)
        let player: AVAudioPlayer?
        if bluetooth {
            if bluetoothPlayers[tone] == nil {
                bluetoothPlayers[tone] = Synth.player(Self.synthesize(tone.notes, leadMs: AudioFocus.bluetoothLeadMs))
            }
            player = bluetoothPlayers[tone]
        } else {
            player = players[tone]
        }
        player?.stop()
        player?.currentTime = 0
        player?.play()
    }

    /** Total length of [tone] as played (Bluetooth lead-in included), to start speech right after it. */
    func durationMs(_ tone: Tone, bluetooth: Bool = AudioFocus.shared.bluetooth) -> Int {
        tone.notes.reduce(0) { $0 + $1.ms } + (bluetooth ? AudioFocus.bluetoothLeadMs : 0)
    }

    func release() {
        players.values.forEach { $0.stop() }
        bluetoothPlayers.values.forEach { $0.stop() }
    }

    /** Sine with a touch of 2nd harmonic and a short attack/release so notes don't click. */
    static func synthesize(_ notes: [Note], leadMs: Int) -> [Float] {
        let rate = Synth.rate
        var out = [Float](repeating: 0, count: leadMs * rate / 1000)
        for note in notes {
            let n = note.ms * rate / 1000
            let fade = min(n / 4, rate / 200)
            var chunk = [Float](repeating: 0, count: n)
            if note.hz > 0 {
                for i in 0..<n {
                    let t = Double(i) / Double(rate)
                    let env = min(1.0, Double(min(i, n - 1 - i)) / Double(fade))
                    let v = sin(2 * .pi * note.hz * t) * 0.8 + sin(4 * .pi * note.hz * t) * 0.2
                    chunk[i] = Float(v * env * 0.85)
                }
            }
            out.append(contentsOf: chunk)
        }
        return out
    }
}
