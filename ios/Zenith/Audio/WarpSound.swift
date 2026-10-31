import Foundation

// CONTRACT (agent "flight"): keep these names and signatures; fill the bodies. Port of WarpSound (Warp.kt).

/** The take-off's synthesized sounds, and the music paused under the flight ([AudioFocus]). Main thread. */
final class WarpSound {
    static let shared = WarpSound()

    /** "Démarrer": engines, then the hum while searching; the music ducks then pauses. */
    func launch() {}
    /** Locked on: one sound for lock, jump and landing boom. */
    func arrive() {}
    /** The music back at the landing boom, still low under the chord, then up. */
    func landed() {}
    /** A flight cut short (Stop, app left): everything stops, the music comes back at once. */
    func abort() {}
    /** The way back to the stars at the end of a drive. */
    func homecoming() {}
    /** When the first sample of [sound] was really heard (output latency included), if known. */
    func heardAt(_ sound: FlightSound) -> Date? { nil }
}
