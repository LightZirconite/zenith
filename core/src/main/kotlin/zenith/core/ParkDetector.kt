package zenith.core

/**
 * Tells when a drive is over, so Zenith can stop by itself and spare the battery:
 * - parked: the car stayed within [stillRadiusM] for [stillMs];
 * - walking away: never above [walkKmh] for [walkMs], phone not charging (a phone charging is in the car);
 * - indoors: no GPS fix for [silentMs] after a slow last fix ([silentFastMs] after a fast one: a tunnel);
 * - false start: a drive started automatically ([falseStartMs] set: activity recognition, which
 *   sometimes fires at home) that never went above [driveKmh] within that time.
 * Never on a motorway or a trunk road: a jam there, however long, is not the end of the drive.
 * Times are wall-clock milliseconds.
 */
class ParkDetector(
    private val startMs: Long,
    private val stillMs: Long = 10 * 60_000L,
    private val stillRadiusM: Double = 300.0,
    private val walkMs: Long = 15 * 60_000L,
    private val walkKmh: Int = 8,
    private val silentMs: Long = 10 * 60_000L,
    private val silentFastMs: Long = 30 * 60_000L,
    private val falseStartMs: Long? = null,
    private val driveKmh: Int = 20,
) {
    private var driven = false
    private var anchor: LatLon? = null
    private var anchorMs = startMs
    private var fastSinceMs = startMs
    private var lastFixMs = startMs
    private var lastKmh = 0

    /** One fix of the drive; true when the drive is over. */
    fun onFix(timeMs: Long, position: LatLon, speedKmh: Int?, roadClass: String?, charging: Boolean): Boolean {
        val kmh = speedKmh ?: 0
        lastFixMs = timeMs
        lastKmh = kmh
        val a = anchor
        if (a == null || Geo.distanceM(a, position) > stillRadiusM) {
            anchor = position
            anchorMs = timeMs
        }
        if (kmh > walkKmh || charging) fastSinceMs = timeMs
        if (kmh >= driveKmh) driven = true
        if (!driven && falseStartMs != null && timeMs - startMs >= falseStartMs) return true
        if (roadClass in FAST_ROADS) return false
        return timeMs - anchorMs >= stillMs || timeMs - fastSinceMs >= walkMs
    }

    /** No fix for a while (called every few seconds); true when the drive is over. */
    fun onSilence(nowMs: Long): Boolean = nowMs - lastFixMs >= if (lastKmh <= walkKmh) silentMs else silentFastMs

    private companion object {
        val FAST_ROADS = setOf("motorway", "motorway_link", "trunk", "trunk_link")
    }
}
