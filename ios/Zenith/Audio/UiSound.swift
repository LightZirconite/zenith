import AVFoundation
import Foundation
import UIKit

/**
 * The sounds under the finger, synthesized at launch (no audio files): round soft pops gliding in
 * pitch, airy swells, a warm little arpeggio, each with a soft attack and a fade, never a click or a
 * bell (port of UiSound.kt, same formulas). Played by a small pool of players per sound so quick
 * taps overlap, on Zenith's mixable session: the music is never touched. Turned down when the volume
 * is high (core Loudness), never louder than the alerts. Every press also gives a haptic, which
 * stays when the setting "Sons de l'interface" turns the sounds off. Main thread.
 */
enum UiSound {
    enum Kind: CaseIterable {
        /** Any button: a soft round pop. */
        case tap
        /** A switch going on (rising pair) or off (falling pair). */
        case on, off
        /** A page or another app opening (rising breath), a page closing (falling). */
        case open, close
        /** Something done: a permission granted, the demo. A warm rising arpeggio. */
        case success
        /** The heart: a bubble rising, or falling. */
        case like, unlike
        /** Play (a pop gliding up) and pause (gliding down). */
        case play, pause
        /** A swipe on the music card armed: a tiny tick. Let go: air moving past. */
        case tick, swipe
        /** The music takes the screen (a swell rising) or gives it back (falling). */
        case musicUp, musicDown
        /** The map comes back on the car. */
        case recenter
        /** Start and Stop: only the touch, the flight has its own sounds. */
        case silent
    }

    private static let rate = 48_000
    private static let twoPi = 2 * Float.pi
    /** Players for one sound at most: a fifth quick tap restarts the one furthest along. */
    private static let voices = 4

    /** Each sound as a WAV, once synthesized. */
    private static var wavs: [Kind: Data] = [:]
    private static var pools: [Kind: [AVAudioPlayer]] = [:]
    private static var preparing = false

    /** Synthesizes the sounds once (at launch), off the main thread; a press before they are ready is silent. */
    static func prepare() {
        guard !preparing else { return }
        preparing = true
        DispatchQueue.global(qos: .utility).async {
            var made: [Kind: Data] = [:]
            for kind in Kind.allCases {
                if let samples = UiSound.wave(kind) { made[kind] = Synth.wav(samples, rate: UiSound.rate) }
            }
            let ready = made
            DispatchQueue.main.async {
                UiSound.wavs = ready
                for (kind, data) in ready {
                    if let player = UiSound.makePlayer(data) { UiSound.pools[kind] = [player] }
                }
            }
        }
    }

    /** Plays [kind] (if the setting allows) and gives a haptic tick; [long]: a long press. */
    static func play(_ kind: Kind, long: Bool = false) {
        touch(kind, long: long)
        guard kind != .silent, SettingsStore.shared.uiSounds, let data = wavs[kind] else { return }
        var pool = pools[kind] ?? []
        let player: AVAudioPlayer
        if let free = pool.first(where: { !$0.isPlaying }) {
            player = free
        } else if pool.count < voices, let fresh = makePlayer(data) {
            pool.append(fresh)
            pools[kind] = pool
            player = fresh
        } else if let furthest = pool.max(by: { $0.currentTime < $1.currentTime }) {
            furthest.stop()
            player = furthest
        } else {
            return
        }
        AudioFocus.shared.ensureActive()
        // Turned down when the volume is high (car, loud phone): never louder than the alerts.
        player.volume = Cockpit.tapGain(volume: AVAudioSession.sharedInstance().outputVolume)
        player.currentTime = 0
        player.play()
    }

    private static func makePlayer(_ data: Data) -> AVAudioPlayer? {
        guard let player = try? AVAudioPlayer(data: data, fileTypeHint: AVFileType.wav.rawValue) else { return nil }
        player.prepareToPlay()
        AudioFocus.shared.track(player)
        return player
    }

    /** The haptic of each gesture, like the touch feedback of Android's buttons. */
    private static func touch(_ kind: Kind, long: Bool) {
        if long {
            UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
            return
        }
        switch kind {
        case .tick:
            UISelectionFeedbackGenerator().selectionChanged()
        case .on:
            UIImpactFeedbackGenerator(style: .rigid).impactOccurred(intensity: 0.75)
        case .off:
            UIImpactFeedbackGenerator(style: .soft).impactOccurred(intensity: 0.75)
        case .success, .silent:
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        case .swipe:
            UIImpactFeedbackGenerator(style: .soft).impactOccurred()
        default:
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }
    }

    private static func wave(_ kind: Kind) -> [Float]? {
        switch kind {
        case .tap:
            return pcm(0.12) { t in 0.5 * pop(t, 940, 600, 0.024) + 0.16 * pop(t, 240, 180, 0.018) }
        case .on:
            return pcm(0.24) { t in 0.36 * pop(t, 640, 520, 0.03) + 0.46 * pop(t - 0.075, 980, 800, 0.04) }
        case .off:
            return pcm(0.24) { t in 0.4 * pop(t, 900, 760, 0.03) + 0.4 * pop(t - 0.075, 600, 480, 0.04) }
        case .open:
            return breath(0.34, 380, 820, 0.42)
        case .close:
            return breath(0.3, 760, 360, 0.38)
        case .success:
            // E4, G#4, B4: the lock notes of the flight, quicker and softer.
            return pcm(0.75) { t in
                let e = note(t, 329.63, 0.22)
                let gSharp = note(t - 0.075, 415.3, 0.24)
                let b = note(t - 0.15, 493.88, 0.32)
                return 0.3 * (e + gSharp + 1.1 * b)
            }
        case .like:
            return pcm(0.3) { t in 0.5 * pop(t, 480, 1_100, 0.07) + 0.12 * pop(t - 0.05, 1_480, 1_650, 0.05) }
        case .unlike:
            return pcm(0.24) { t in 0.45 * pop(t, 900, 440, 0.06) }
        case .play:
            return pcm(0.2) { t in 0.48 * pop(t, 520, 860, 0.045) }
        case .pause:
            return pcm(0.2) { t in 0.48 * pop(t, 820, 500, 0.04) }
        case .tick:
            return pcm(0.05) { t in 0.3 * pop(t, 1_500, 1_250, 0.007) }
        case .swipe:
            return breath(0.26, 300, 700, 0.5, tone: 0.1)
        case .musicUp:
            return breath(0.42, 260, 620, 0.4)
        case .musicDown:
            return breath(0.42, 620, 260, 0.4)
        case .recenter:
            return pcm(0.28) { t in 0.3 * pop(t, 700, 560, 0.03) + 0.3 * pop(t - 0.06, 700, 840, 0.04) }
        case .silent:
            return nil
        }
    }

    /**
     * A soft round pop: a sine gliding quickly from [from] to [to] Hz with a quiet octave, a 3 ms
     * attack and an exponential fade of time constant [decay].
     */
    private static func pop(_ t: Float, _ from: Float, _ to: Float, _ decay: Float) -> Float {
        if t < 0 { return 0 }
        let glide: Float = 0.014
        let phase = twoPi * (to * t + (from - to) * glide * (1 - exp(-t / glide)))
        let attack = min(t / 0.003, 1)
        return attack * exp(-t / decay) * (sin(phase) + 0.22 * sin(2 * phase))
    }

    /** A soft sine note with a slow-ish attack and a fade, no bell. */
    private static func note(_ t: Float, _ hz: Float, _ decay: Float) -> Float {
        if t < 0 { return 0 }
        let w = twoPi * hz * t
        let attack = min(t / 0.008, 1)
        return attack * exp(-t / decay) * (sin(w) + 0.15 * sin(2 * w))
    }

    /**
     * Air moving: filtered noise swelling and settling over [seconds], its color and a faint tone
     * gliding from [from] to [to] Hz.
     */
    private static func breath(_ seconds: Float, _ from: Float, _ to: Float, _ level: Float, tone: Float = 0.18) -> [Float] {
        var noise = UiNoise(seed: 11)
        var low: Float = 0
        var band: Float = 0
        var phase: Float = 0
        let r = Float(rate)
        return pcm(seconds) { t in
            let k = t / seconds
            let hz = from + (to - from) * k * k * (3 - 2 * k)
            // Noise through a one-pole low-pass whose cutoff follows the glide, minus a slower one: a band of air.
            let white = noise.next() * 2 - 1
            low += (white - low) * min(twoPi * hz * 2.2 / r, 1)
            band += (low - band) * (twoPi * hz * 0.35 / r)
            phase += twoPi * hz / r
            let swell = sin(Float.pi * k)
            return level * swell * swell * (2.6 * (low - band) + tone * sin(phase))
        }
    }

    /** [seconds] of samples, in order; the last 15 ms fade to silence: never a hard end. */
    private static func pcm(_ seconds: Float, _ sample: (Float) -> Float) -> [Float] {
        let r = Float(rate)
        let n = Int(seconds * r)
        var out = [Float](repeating: 0, count: n)
        for i in 0..<n {
            let tail = min(Float(n - i) / (0.015 * r), 1)
            out[i] = max(-1, min(1, sample(Float(i) / r) * tail))
        }
        return out
    }
}

/** A seeded noise (SplitMix64): the same breath at every launch. */
private struct UiNoise {
    private var state: UInt64

    init(seed: UInt64) { state = seed }

    /** Uniform in 0..<1. */
    mutating func next() -> Float {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        z ^= z >> 31
        return Float(z >> 40) / 16_777_216
    }
}
