package zenith.core

/**
 * Lowering Spotify when it plays on another device (Spotify Connect: the car's head unit, a
 * speaker), where the phone's own audio-focus ducking does nothing. With the driver's account
 * connected, the app reads the active device (Web API `GET /v1/me/player`), turns its volume down
 * when Zenith's sounds start (`PUT /v1/me/player/volume`) and puts it back once they are over.
 * Only the decisions live here; the app sends the requests, never waiting on them for a sound.
 */
object SpotifyDuck {
    /** Spotify's device type for a phone. */
    const val SMARTPHONE = "Smartphone"

    /** Ducked volume: about this share of the device's own. */
    const val SHARE = 0.25

    /** Never ducked below this (percent): the music stays faintly there, like Android's duck. */
    const val FLOOR = 10

    /**
     * Whether to lower the active device: not a phone ([deviceType] not "Smartphone": the car, a
     * speaker, a computer), it takes volume commands, music plays, and the account may have
     * Premium ([premium] null: unknown yet, Spotify's answer will tell). A phone is never lowered:
     * it is this one, which the system's own ducking already lowers. Telling this phone from
     * another one by its name failed (iOS 16+ only gives apps "iPhone", Spotify says
     * "iPhone de …"): the music went down twice and came back seconds late (13/11/2026).
     */
    fun shouldDuck(deviceType: String?, supportsVolume: Boolean, playing: Boolean, premium: Boolean?): Boolean =
        supportsVolume && playing && premium != false && !deviceType.equals(SMARTPHONE, ignoreCase = true)

    /** The volume (percent) while Zenith speaks: about a quarter of [current], at least [FLOOR], never louder. */
    fun duckedVolume(current: Int): Int {
        val now = current.coerceIn(0, 100)
        return maxOf(FLOOR, (now * SHARE + 0.5).toInt()).coerceAtMost(now)
    }

    /**
     * The volume to put back once Zenith is done: [before], if the device still plays at [duckedTo]
     * ([now] null: unknown, put back anyway); null when nothing was lowered or the driver set the
     * volume meanwhile (like [DriveVolume.restoreTo]).
     */
    fun restoreVolume(before: Int, duckedTo: Int, now: Int?): Int? {
        if (duckedTo >= before) return null
        return before.takeIf { now == null || now == duckedTo }
    }
}
