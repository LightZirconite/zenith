import Foundation

// CONTRACT (agent "flight"): keep these names and signatures; fill the bodies. Port of WarpSound
// (Warp.kt), the same methods as on Android so the drive screen calls them at the same moments.

/** The take-off's synthesized sounds, and the music paused under the flight ([AudioFocus]). Main thread. */
final class WarpSound {
    static let shared = WarpSound()

    /** Synthesizes the sounds (off the main thread); safe to call again. */
    func prepare() {}
    /** "Démarrer": the engines. */
    func engage() {}
    /** Satellite [n] (1 to 4) locked: a soft round note, a step higher each time, the fourth a chord. */
    func lock(_ n: Int) {}
    /** The lock-on, the jump and the boom, in one sound: the pictures follow it ([heardAt]). */
    func arrive() {}
    /** The way back at the end of a drive; the music ducks a moment under it. */
    func homecoming() {}
    /** When the first sample of [kind] reaches the ear (output latency included), if known. */
    func heardAt(_ kind: FlightSound) -> Date? { nil }
    /** At Start: the music slides down, then pauses for the flight. */
    func musicAway() {}
    /** At the boom: the music plays again, low under the warm chord, then rises to where it was. */
    func musicBack() {}
    /** Flight cut short: the music comes back at once. */
    func musicNow() {}
    /** The hum while searching, looping. */
    func startHum() {}
    /** The hum dies away over [ms] instead of stopping dead. */
    func fadeHum(ms: Int = 700) {}
    func release() {}
}
