import AVFoundation
import Foundation

/**
 * The flight's sounds, synthesized like the alert tones (no assets): the engines spooling up at
 * Start, a low hum looping while the GPS searches, a soft note at each satellite locked, then the
 * arrival: a deep tone swelling at the lock-on, the rush of the jump ending in a round deep boom
 * and a warm chord as the map appears. At the end of a drive, the way back: a soft breath falling
 * away and the chord of home.
 *
 * The music steps aside for the flight through [AudioFocus], the app's one audio session: it slides
 * down and pauses at Start, then comes back low and rises to where it was; it only ducks under the
 * way back. On iOS, handing the session back to the music stops every sound of the app, so the
 * music comes back once the flight's own sounds have rung out (the boom's chord included), and a
 * flight cut short fades them out first: nothing ever ends dead. The sounds follow the media volume
 * like the interface sounds ([Cockpit.uiGain]). Main thread.
 */
final class WarpSound {
    static let shared = WarpSound()

    /** The synthesized sounds, as WAV files in memory: built once, kept across [release]. */
    private struct Waves {
        let engage: Data
        let hum: Data
        let locks: [Data]
        let arrive: Data
        let homecoming: Data
    }

    private var waves: Waves?
    private var preparing = false
    private var engagePlayer: AVAudioPlayer?
    private var humPlayer: AVAudioPlayer?
    private var lockPlayers: [AVAudioPlayer?] = []

    /** The arrival or the way back now playing, a fresh player each time so its clock starts at zero. */
    private var moment: AVAudioPlayer?
    private var momentKind: FlightSound?
    private var momentHeard: Date?

    /** [musicAway] asked, [musicBack] not yet. */
    private var away = false
    /** The music paused for the flight: our pausing hold on [AudioFocus]. */
    private var pauseHeld = false
    /** The music back but still low: our ducking hold until it rises. */
    private var riseHeld = false
    /** The next step of the music's way back, waiting. */
    private var musicStep: DispatchWorkItem?

    private var humWanted = false
    private var humLevel = WarpSound.humBase
    private var humStop: DispatchWorkItem?

    /** Builds the sounds off the main thread; anything asked before is silently skipped. Safe to call again. */
    func prepare() {
        if let waves {
            if engagePlayer == nil { load(waves) }
            return
        }
        guard !preparing else { return }
        preparing = true
        DispatchQueue.global(qos: .userInitiated).async {
            let built = Waves(
                engage: Synth.wav(WarpSound.engageWave()),
                hum: Synth.wav(WarpSound.humWave()),
                locks: WarpSound.lockNotes.enumerated().map { i, hz in
                    Synth.wav(WarpSound.lockWave(hz, chord: i == WarpSound.lockNotes.count - 1))
                },
                arrive: Synth.wav(WarpSound.arriveWave()),
                homecoming: Synth.wav(WarpSound.homecomingWave())
            )
            DispatchQueue.main.async {
                self.preparing = false
                self.waves = built
                self.load(built)
            }
        }
    }

    /** "Démarrer": the engines. */
    func engage() { replay(engagePlayer) }

    /** Satellite [n] (1 to 4) locked: a soft round note, a step higher each time, the fourth a chord. */
    func lock(_ n: Int) {
        guard lockPlayers.indices.contains(n - 1) else { return }
        replay(lockPlayers[n - 1])
    }

    /** The lock-on, the jump and the boom, in one sound: the pictures follow it ([heardAt]). */
    func arrive() { play(.arrive, waves?.arrive) }

    /** The way back at the end of a drive; the music ducks a moment under it. */
    func homecoming() {
        // A flight cut short just before (the music still on its way back): given back now, so it
        // does not tear the way back's session down in the middle of it.
        if pauseHeld || riseHeld { giveBack() }
        // Until the sound has died away: ending the duck stops whatever still sounds.
        AudioFocus.shared.duckFor(
            durationMs: Int((WarpSound.homecomingSeconds * 1_000).rounded()) - WarpSound.homecomingHoldMs,
            holdMs: WarpSound.homecomingHoldMs
        )
        play(.homecoming, waves?.homecoming)
    }

    /**
     * When the first sample of [kind] reaches the ear: when it was started, plus the output's delay
     * (Bluetooth or CarPlay included, as iOS reports it), believed up to 350 ms.
     */
    func heardAt(_ kind: FlightSound) -> Date? {
        guard moment != nil, momentKind == kind else { return nil }
        return momentHeard
    }

    /** At Start: the music slides down, then pauses for the flight. */
    func musicAway() {
        guard !away else { return }
        away = true
        cancelStep()
        if !pauseHeld {
            pauseHeld = true
            AudioFocus.shared.acquire(pause: true)
        }
        if riseHeld {
            riseHeld = false
            AudioFocus.shared.release(holdMs: 0)
        }
    }

    /**
     * At the boom (or after a long search): the music plays again, low at first, then rises to where
     * it was. It waits for the flight's own sounds to ring out (the boom's chord, a lock note): giving
     * the session back stops them on iOS.
     */
    func musicBack() {
        guard away else { return }
        away = false
        handBack(until: Date().addingTimeInterval(WarpSound.ringMax))
    }

    /** Flight cut short: the music comes back at once, the flight's sounds fading out just before. */
    func musicNow() {
        away = false
        cancelStep()
        guard pauseHeld || riseHeld else { return }
        let sounding = playing
        if sounding.isEmpty {
            giveBack()
            return
        }
        for player in sounding { player.setVolume(0, fadeDuration: WarpSound.cutFade) }
        step(after: WarpSound.cutFade) { [weak self] in self?.giveBack() }
    }

    /** The hum while searching, looping. */
    func startHum() {
        humWanted = true
        humStop?.cancel()
        humStop = nil
        guard let hum = humPlayer else { return }
        AudioFocus.shared.ensureActive()
        humLevel = WarpSound.humBase * gain()
        if hum.isPlaying {
            hum.setVolume(humLevel, fadeDuration: 0.1)
        } else {
            hum.volume = humLevel
            hum.play()
        }
    }

    /** The hum dies away over [ms] instead of stopping dead. */
    func fadeHum(ms: Int = 700) {
        humWanted = false
        guard let hum = humPlayer, hum.isPlaying else { return }
        let seconds = Double(ms) / 1_000
        hum.setVolume(0, fadeDuration: seconds)
        humStop?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, !self.humWanted else { return }
            self.humPlayer?.pause()
        }
        humStop = work
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: work)
    }

    func release() {
        humWanted = false
        humStop?.cancel()
        humStop = nil
        for player in all { player.stop() }
        engagePlayer = nil
        humPlayer = nil
        lockPlayers = []
        moment = nil
        momentKind = nil
        momentHeard = nil
        musicNow()
    }

    // MARK: - Music

    /** Waits (until [deadline] at most) for the flight's sounds to ring out and the hum to fade, then hands the music back. */
    private func handBack(until deadline: Date) {
        cancelStep()
        let ringing = Date() < deadline ? ringingFor : 0
        if ringing > 0 {
            step(after: ringing + 0.05) { [weak self] in self?.handBack(until: deadline) }
            return
        }
        if let hum = humPlayer, hum.isPlaying {
            hum.setVolume(0, fadeDuration: WarpSound.cutFade)
            step(after: WarpSound.cutFade) { [weak self] in
                self?.humPlayer?.pause()
                self?.handBack(until: deadline)
            }
            return
        }
        guard pauseHeld else {
            resumeHum()
            return
        }
        // The pause becomes a duck: the music plays again, low; then the duck ends and it rises.
        pauseHeld = false
        riseHeld = true
        AudioFocus.shared.acquire()
        AudioFocus.shared.release(pause: true, holdMs: 0)
        step(after: Double(WarpSound.musicRiseMs) / 1_000) { [weak self] in
            guard let self else { return }
            self.riseHeld = false
            AudioFocus.shared.release(holdMs: 0)
            self.step(after: WarpSound.settleSeconds) { [weak self] in self?.resumeHum() }
        }
    }

    /** Our holds on the music given back now (whatever still sounds is silent by then). */
    private func giveBack() {
        cancelStep()
        for player in playing { player.stop() }
        if pauseHeld {
            pauseHeld = false
            AudioFocus.shared.release(pause: true, holdMs: 0)
        }
        if riseHeld {
            riseHeld = false
            AudioFocus.shared.release(holdMs: 0)
        }
        step(after: WarpSound.settleSeconds) { [weak self] in self?.resumeHum() }
    }

    /** Still searching once the music is back: the hum comes in again over it, softly. */
    private func resumeHum() {
        guard humWanted, let hum = humPlayer, !hum.isPlaying else { return }
        AudioFocus.shared.ensureActive()
        humLevel = WarpSound.humBase * gain()
        hum.volume = 0
        hum.play()
        hum.setVolume(humLevel, fadeDuration: 0.6)
    }

    private func step(after seconds: TimeInterval, _ block: @escaping () -> Void) {
        musicStep?.cancel()
        let work = DispatchWorkItem(block: block)
        musicStep = work
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: work)
    }

    private func cancelStep() {
        musicStep?.cancel()
        musicStep = nil
    }

    // MARK: - Players

    private var all: [AVAudioPlayer] {
        ([engagePlayer, humPlayer, moment] + lockPlayers).compactMap { $0 }
    }

    private var playing: [AVAudioPlayer] { all.filter { $0.isPlaying } }

    /** Seconds until the engines, a lock note and the arrival or the way back have all rung out. */
    private var ringingFor: TimeInterval {
        ([engagePlayer, moment] + lockPlayers)
            .compactMap { $0 }
            .filter { $0.isPlaying }
            .map { $0.duration - $0.currentTime }
            .max() ?? 0
    }

    private func load(_ waves: Waves) {
        engagePlayer = WarpSound.player(waves.engage)
        humPlayer = WarpSound.player(waves.hum)
        humPlayer?.numberOfLoops = -1
        lockPlayers = waves.locks.map { WarpSound.player($0) }
    }

    /** Turned down when the media volume is high (car head unit), like the interface sounds. */
    private func gain() -> Float {
        Cockpit.uiGain(volume: AVAudioSession.sharedInstance().outputVolume)
    }

    private func play(_ kind: FlightSound, _ wav: Data?) {
        guard let wav, let player = try? AVAudioPlayer(data: wav, fileTypeHint: AVFileType.wav.rawValue) else { return }
        moment?.stop()
        AudioFocus.shared.ensureActive()
        player.volume = gain()
        player.prepareToPlay()
        moment = player
        momentKind = kind
        let session = AVAudioSession.sharedInstance()
        let lag = min(max(session.outputLatency + session.ioBufferDuration, 0), WarpSound.maxLag)
        let started = Date()
        player.play()
        momentHeard = started.addingTimeInterval(lag)
    }

    private func replay(_ player: AVAudioPlayer?) {
        guard let player else { return }
        AudioFocus.shared.ensureActive()
        player.volume = gain()
        player.currentTime = 0
        player.play()
    }

    private static func player(_ wav: Data) -> AVAudioPlayer? {
        let player = try? AVAudioPlayer(data: wav, fileTypeHint: AVFileType.wav.rawValue)
        player?.prepareToPlay()
        return player
    }

    // MARK: - Waves

    private static let rate = Double(Synth.rate)
    private static let twoPi = 2 * Double.pi

    /** The hum's own level, under the other flight sounds. */
    private static let humBase: Float = 0.55

    /** After the music comes back, it stays low this long before rising. */
    private static let musicRiseMs = 800

    /** A sound heard later than this after it was started is not believed. */
    private static let maxLag: TimeInterval = 0.35

    /** The longest the music waits for the flight's sounds: the boom's chord rings 3.6 s. */
    private static let ringMax: TimeInterval = 4

    /** Fade of the flight's sounds before the music takes the session back. */
    private static let cutFade: TimeInterval = 0.25

    /** After a hand-back, the time for iOS to finish it before a sound starts again. */
    private static let settleSeconds: TimeInterval = 0.2

    /** The way back's sound, its tail included. */
    private static let homecomingSeconds = Double(HOMECOMING_MS) / 1_000 + 0.8
    private static let homecomingHoldMs = 500

    /** E major, rising: E4, G#4, B4, then E5 with its chord. */
    private static let lockNotes: [Double] = [329.63, 415.3, 493.88, 659.25]

    private static func smooth(_ x: Double) -> Double {
        let k = min(max(x, 0), 1)
        return k * k * (3 - 2 * k)
    }

    /** Samples of [seconds] of [sample] (time, noise), softly saturated. */
    private static func pcm(_ seconds: Double, _ sample: (Double, Double) -> Double) -> [Float] {
        var random = WarpNoise(seed: 7)
        var low = 0.0
        let count = Int(seconds * rate)
        var out = [Float](repeating: 0, count: count)
        for i in 0..<count {
            // Brown-ish noise: white noise through a one-pole low-pass, the "air" of a rush.
            low += (random.unit() * 2 - 1 - low) * 0.08
            out[i] = Float(tanh(sample(Double(i) / rate, low * 3)) * 0.9)
        }
        return out
    }

    /** Engines spooling up: a sweep from deep to mid, air rushing in, rising then letting go. */
    private static func engageWave() -> [Float] {
        let length = 2.2
        var phase = 0.0
        return pcm(length) { t, noise in
            let k = t / length
            phase += twoPi * (55 + 300 * k * k) / rate
            let env = smooth(k / 0.75) * (1 - smooth((k - 0.75) / 0.25))
            let tone = 0.55 * sin(phase) + 0.2 * sin(phase * 2.01)
            return env * (tone + noise * (0.15 + 0.6 * k))
        }
    }

    /** The hum of the tunnel, two seconds that loop seamlessly: deep partials breathing slowly, a faint shimmer on top. */
    private static func humWave() -> [Float] {
        pcm(2) { t, _ in
            let breath = 1 + 0.3 * sin(twoPi * 0.5 * t)
            let w = twoPi * t
            let partials = sin(w * 55) + 0.5 * sin(w * 110) + 0.25 * sin(w * 165)
            return 0.32 * breath * partials + 0.03 * breath * sin(w * 880)
        }
    }

    /**
     * The arrival, timed on the pictures: at the lock-on a deep tone swells, rising; the rush of
     * the jump rises fast out of it; at the flash a round deep boom, the air settling and a warm
     * chord swelling under it, the whole dying away slowly: an arrival, not a cut.
     */
    private static func arriveWave() -> [Float] {
        let hold = Double(HOLD_MS) / 1_000
        let impact = Double(JUMP_IMPACT_MS) / 1_000
        let length = impact + 3.6
        var charge = 0.0
        var rush = 0.0
        var boom = 0.0
        return pcm(length) { t, noise in
            var v = 0.0
            if t < impact + 0.05 {
                // The lock-on: from the hum's 55 Hz up an octave, swelling at once, then handing over to the rush.
                charge += twoPi * (55 + 55 * smooth(t / impact)) / rate
                let swell = smooth(t / 0.06) * (0.32 + 0.2 * smooth(t / hold)) * (1 - smooth((t - hold) / (impact - hold)))
                let tone = sin(charge) + 0.4 * sin(charge * 2)
                let air = noise * 0.2 * smooth(t / hold) * (1 - smooth((t - hold) / 0.4))
                v += swell * tone + air
                // The rush, starting under the lock-on, gone 50 ms after the flash.
                let k = min(max((t - hold + 0.15) / (impact - hold + 0.15), 0), 1)
                if k > 0 {
                    rush += twoPi * (120 + 900 * k * k * k) / rate
                    let out = t <= impact ? 1 : 1 - (t - impact) / 0.05
                    v += out * pow(k, 1.5) * (0.35 * sin(rush) + noise * 0.9)
                }
            }
            if t >= impact {
                let d = t - impact
                // Pitch dropping from 70 to 35 Hz as it decays: the body of the boom.
                boom += twoPi * (35 + 35 * exp(-d / 0.25)) / rate
                // A round, deep landing (soft attack), no bell: the air settles, a warm chord carries on.
                v += smooth(d / 0.03) * exp(-d / 0.9) * sin(boom)
                v += noise * 0.9 * smooth(d / 0.03) * exp(-d / 0.35)
                let w = twoPi * d
                let pad = 0.14 * smooth(d / 0.5) * exp(-d / 1.8)
                let chord = sin(w * 110) + sin(w * 138.6) + sin(w * 164.8) + 0.5 * sin(w * 220)
                v += pad * chord
            }
            // Last half second: down to silence, never a click.
            return v * (1 - smooth((t - (length - 0.5)) / 0.5))
        }
    }

    /** The way back: a soft breath of air drifting down with the Earth and the warm chord of home settling in; no impact. */
    private static func homecomingWave() -> [Float] {
        let recede = Double(RECEDE_MS) / 1_000
        let length = homecomingSeconds
        var sweep = 0.0
        return pcm(length) { t, noise in
            let u = min(max(t / recede, 0), 1)
            sweep += twoPi * (440 - 330 * smooth(u)) / rate
            let air = smooth(t / 0.12) * (1 - smooth(u))
            var v = air * (0.18 * sin(sweep) + 0.45 * noise)
            let c = t - 0.35
            if c > 0 {
                let w = twoPi * c
                let pad = 0.11 * smooth(c / 0.4) * exp(-c / 1.2)
                let chord = sin(w * 164.81) + sin(w * 207.65) + sin(w * 246.94) + 0.5 * sin(w * 329.63)
                v += pad * chord
            }
            return v * (1 - smooth((t - (length - 0.6)) / 0.6))
        }
    }

    /** A lock: a soft sine with a slow fade and a quiet octave for air; no bell, no click. */
    private static func lockWave(_ hz: Double, chord: Bool) -> [Float] {
        pcm(1.1) { t, _ in
            let w = twoPi * t
            let env = smooth(t / 0.04) * exp(-t / (chord ? 0.4 : 0.24))
            var v = sin(w * hz) + 0.2 * sin(w * hz * 2)
            if chord { v += 0.7 * sin(w * hz * 1.2599) + 0.55 * sin(w * hz * 1.4983) }
            return 0.3 * env * v * (1 - smooth((t - 0.9) / 0.2))
        }
    }
}

/** Seeded white noise for the synthesized air: the same sound every time. */
private struct WarpNoise {
    private var state: UInt64

    init(seed: UInt64) { state = seed }

    /** 0 (included) to 1 (excluded). */
    mutating func unit() -> Double {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        z ^= z >> 31
        return Double(z >> 11) / 9_007_199_254_740_992
    }
}
