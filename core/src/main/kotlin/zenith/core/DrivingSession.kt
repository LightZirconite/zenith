package zenith.core

data class DriverProfile(val probationary: Boolean = false, val raining: Boolean = false)

/** Everything the UI needs after one GPS fix. */
data class DriveSnapshot(
    val timeMs: Long,
    val position: LatLon,
    /** Speed to display; null until the GPS is accurate enough. */
    val speedKmh: Int?,
    val gpsAccuracyM: Double?,
    val headingDeg: Double?,
    val road: RoadCandidate?,
    /** Limit after rain and licence rules; null when unknown. */
    val limitKmh: Int?,
    val limitSource: LimitSource?,
    val alerts: List<ActiveAlert>,
    val section: SectionStatus?,
    val upcoming: UpcomingRadar?,
    /** Next limit change on the road ahead, after rain and licence rules. */
    val limitAhead: LimitAhead?,
    val events: List<AlertEvent>,
)

/**
 * Real-time pipeline: GPS fix → Kalman filter → map matching → alerts. Pure and deterministic,
 * so a recorded GPX trace replays exactly like a live drive.
 */
class DrivingSession(
    /** Current road network; may change as road tiles load. */
    private val network: () -> RoadNetwork?,
    radars: List<Radar>,
    profile: DriverProfile = DriverProfile(),
    settings: AlertSettings = AlertSettings(),
) {
    constructor(
        network: RoadNetwork?,
        radars: List<Radar>,
        profile: DriverProfile = DriverProfile(),
        settings: AlertSettings = AlertSettings(),
    ) : this({ network }, radars, profile, settings)

    private val filter = KalmanFilter()
    private val stabilizer = SpeedStabilizer()
    private val matcher = MapMatcher(network)
    private val effective: (Int) -> Int = { SpeedRules.effectiveLimit(it, profile.raining, profile.probationary) }
    private val engine = AlertEngine(RadarIndex(radars), settings, effective)

    /** Swaps in a newer radar list (e.g. after a pack download) without resetting the drive. */
    fun setRadars(radars: List<Radar>) {
        engine.radars = RadarIndex(radars)
    }

    /** Processes one fix; null when the filter rejected it as an outlier. */
    fun process(fix: GpsFix): DriveSnapshot? {
        val state = filter.update(fix) ?: return null
        val net = network()
        val road = matcher.update(state)
        val roadLimit = road?.limitKmh
        val out = engine.step(state, road, net)
        return DriveSnapshot(
            timeMs = state.timeMs,
            position = state.position,
            speedKmh = stabilizer.update(fix, state.speedMps),
            gpsAccuracyM = fix.accuracyM,
            headingDeg = state.headingDeg,
            road = road,
            limitKmh = roadLimit?.let(effective),
            limitSource = road?.segment?.limitSource,
            alerts = out.active,
            section = out.section,
            upcoming = out.upcoming,
            limitAhead = out.limitAhead,
            events = out.events,
        )
    }

    companion object {
        /** Replays a recorded trace and returns every snapshot. */
        fun replay(fixes: List<GpsFix>, session: DrivingSession): List<DriveSnapshot> = fixes.mapNotNull(session::process)
    }
}
