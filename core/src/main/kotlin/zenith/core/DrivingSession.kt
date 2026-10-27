package zenith.core

import kotlin.math.roundToInt

data class DriverProfile(val probationary: Boolean = false, val raining: Boolean = false)

/** Everything the UI needs after one GPS fix. */
data class DriveSnapshot(
    val timeMs: Long,
    val position: LatLon,
    val speedKmh: Int,
    val headingDeg: Double?,
    val road: RoadCandidate?,
    /** Limit after rain and licence rules; null when unknown. */
    val limitKmh: Int?,
    val limitSource: LimitSource?,
    val alerts: List<ActiveAlert>,
    val section: SectionStatus?,
    val events: List<AlertEvent>,
)

/**
 * Real-time pipeline: GPS fix → Kalman filter → map matching → alerts. Pure and deterministic,
 * so a recorded GPX trace replays exactly like a live drive.
 */
class DrivingSession(
    network: RoadNetwork?,
    radars: List<Radar>,
    profile: DriverProfile = DriverProfile(),
    settings: AlertSettings = AlertSettings(),
) {
    private val filter = KalmanFilter()
    private val matcher = network?.let { MapMatcher(it) }
    private val effective: (Int) -> Int = { SpeedRules.effectiveLimit(it, profile.raining, profile.probationary) }
    private val engine = AlertEngine(RadarIndex(radars), settings, effective)

    /** Processes one fix; null when the filter rejected it as an outlier. */
    fun process(fix: GpsFix): DriveSnapshot? {
        val state = filter.update(fix) ?: return null
        val road = matcher?.update(state)
        val roadLimit = road?.limitKmh
        val out = engine.step(state, roadLimit)
        return DriveSnapshot(
            timeMs = state.timeMs,
            position = state.position,
            speedKmh = (state.speedMps * 3.6).roundToInt(),
            headingDeg = state.headingDeg,
            road = road,
            limitKmh = roadLimit?.let(effective),
            limitSource = road?.segment?.limitSource,
            alerts = out.active,
            section = out.section,
            events = out.events,
        )
    }

    companion object {
        /** Replays a recorded trace and returns every snapshot. */
        fun replay(fixes: List<GpsFix>, session: DrivingSession): List<DriveSnapshot> = fixes.mapNotNull(session::process)
    }
}
