import Foundation
import Observation

/**
 * User choices, kept in UserDefaults with the same keys as the Android app. Shared by the screens
 * and the drive engine; main thread.
 */
@Observable
final class SettingsStore {
    static let shared = SettingsStore()

    @ObservationIgnored private let defaults = UserDefaults.standard

    var probationary: Bool { didSet { defaults.set(probationary, forKey: "probationary") } }
    /** Set by the weather during a drive, never kept from one drive to the next. */
    var raining = false
    /** False: alert tones only, no voice. */
    var voice: Bool { didSet { defaults.set(voice, forKey: "voice") } }
    /** "Limite 80" when too fast for the road; off: the radars only. */
    var speeding: Bool { didSet { defaults.set(speeding, forKey: "say_speeding") } }
    /** Too fast from 3 km/h over the limit, instead of from the speed a radar flashes at. */
    var earlySpeeding: Bool { didSet { defaults.set(earlySpeeding, forKey: "early_speeding") } }
    /** After a radar or a section: "flash probable", the average. */
    var passResult: Bool { didSet { defaults.set(passResult, forKey: "say_pass") } }
    /** Control itineraries announced on entry. */
    var zones: Bool { didSet { defaults.set(zones, forKey: "say_zones") } }
    /** Beeps getting faster while too fast near a radar. */
    var dangerBeeps: Bool { didSet { defaults.set(dangerBeeps, forKey: "danger_beeps") } }
    /** The music pauses while the voice speaks (else it slides down and back up). */
    var pauseMusic: Bool { didSet { defaults.set(pauseMusic, forKey: "voice_pause") } }
    /** The media volume raised to 60 % for a drive when lower, put back after. */
    var raiseVolume: Bool { didSet { defaults.set(raiseVolume, forKey: "raise_volume") } }
    /** A drive started by hand ends by itself once the car is parked. */
    var autoStop: Bool { didSet { defaults.set(autoStop, forKey: "auto_stop") } }
    /** Master switch of every automatic start (riding, car shortcut): off, a drive only starts by hand. */
    var autoStart: Bool { didSet { defaults.set(autoStart, forKey: "auto_start") } }
    /** Drive started by itself when the phone starts riding in a vehicle. */
    var motionStart: Bool { didSet { defaults.set(motionStart, forKey: "motion_start") } }
    /** Rain mode from the weather (Open-Meteo) while driving. */
    var autoRain: Bool { didSet { defaults.set(autoRain, forKey: "auto_rain") } }
    /** Spotify Canvas behind the music card and in the small window. */
    var spotifyCanvas: Bool { didSet { defaults.set(spotifyCanvas, forKey: "spotify_canvas") } }
    /** Square small window, larger; else a 16:9 strip that leaves Waze visible. */
    var pipLarge: Bool { didSet { defaults.set(pipLarge, forKey: "pip_large") } }
    /** Soft sounds under the finger on every button; the alerts are not concerned. */
    var uiSounds: Bool { didSet { defaults.set(uiSounds, forKey: "ui_sounds") } }
    /** iOS: speed, limit and next radar on the lock screen and in the Dynamic Island. */
    var liveActivity: Bool { didSet { defaults.set(liveActivity, forKey: "live_activity") } }
    /** iOS: the small window opens by itself when Zenith goes to the background during a drive. */
    var autoPip: Bool { didSet { defaults.set(autoPip, forKey: "auto_pip") } }

    /**
     * When "Arrêter" was touched on a drive that started by itself (ms since 1970, 0: none): no
     * automatic start until the trip is over (core AutoStartPause).
     */
    var autoPauseSince: Int64 { didSet { defaults.set(autoPauseSince, forKey: "auto_pause_since") } }

    /** The start by riding is wanted: its own switch and the master one. */
    var motionWanted: Bool { autoStart && motionStart }

    private init() {
        let d = UserDefaults.standard
        func bool(_ key: String, _ fallback: Bool) -> Bool {
            d.object(forKey: key) == nil ? fallback : d.bool(forKey: key)
        }
        probationary = bool("probationary", false)
        voice = bool("voice", true)
        speeding = bool("say_speeding", true)
        earlySpeeding = bool("early_speeding", false)
        passResult = bool("say_pass", true)
        zones = bool("say_zones", true)
        dangerBeeps = bool("danger_beeps", true)
        pauseMusic = bool("voice_pause", false)
        raiseVolume = bool("raise_volume", true)
        autoStop = bool("auto_stop", true)
        autoStart = bool("auto_start", true)
        autoPauseSince = (d.object(forKey: "auto_pause_since") as? NSNumber)?.int64Value ?? 0
        motionStart = bool("motion_start", true)
        autoRain = bool("auto_rain", true)
        spotifyCanvas = bool("spotify_canvas", true)
        pipLarge = bool("pip_large", false)
        uiSounds = bool("ui_sounds", true)
        liveActivity = bool("live_activity", true)
        autoPip = bool("auto_pip", true)
    }
}
