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
    /** Road ahead the driver most likely follows, from the car onwards; empty without road data. */
    val ahead: List<LatLon>,
    val events: List<AlertEvent>,
    /** No GPS (tunnel): position estimated along the road at the last speed. */
    val estimated: Boolean = false,
    /** Control itinerary being driven, if any. */
    val zone: ZoneStatus? = null,
)

/**
 * Real-time pipeline: GPS fix → Kalman filter → map matching → alerts and control zones. Pure and
 * deterministic, so a recorded GPX trace replays exactly like a live drive.
 */
class DrivingSession(
    /** Current road network; may change as road tiles load. */
    private val network: () -> RoadNetwork?,
    radars: List<Radar>,
    profile: DriverProfile = DriverProfile(),
    settings: AlertSettings = AlertSettings(),
    zones: List<ControlZone> = emptyList(),
) {
    /** Licence and weather rules; can change during a drive (rain starting). */
    @Volatile
    var profile: DriverProfile = profile

    constructor(
        network: RoadNetwork?,
        radars: List<Radar>,
        profile: DriverProfile = DriverProfile(),
        settings: AlertSettings = AlertSettings(),
        zones: List<ControlZone> = emptyList(),
    ) : this({ network }, radars, profile, settings, zones)

    private val filter = KalmanFilter()
    private val stabilizer = SpeedStabilizer()
    private val matcher = MapMatcher(network)
    private val effective: (Int) -> Int = { SpeedRules.effectiveLimit(it, this.profile.raining, this.profile.probationary) }
    private val engine = AlertEngine(RadarIndex(radars), settings, effective)
    private var zoneTracker = ZoneTracker(zones)
    private val motion = MotionGate()

    /** Swaps in a newer radar list (e.g. after a pack download) without resetting the drive. */
    fun setRadars(radars: List<Radar>) {
        engine.radars = RadarIndex(radars)
    }

    /** Swaps in newer control zones; the zone being driven is picked up again after a few fixes. */
    fun setZones(zones: List<ControlZone>) {
        zoneTracker = ZoneTracker(zones)
    }

    private var last: DriveSnapshot? = null
    private var lastRealFixMs: Long? = null
    private var lastSpeedMps = 0.0

    /** Processes one fix; null when the filter rejected it as an outlier. */
    fun process(fix: GpsFix): DriveSnapshot? {
        val snapshot = step(fix, estimated = false) ?: return null
        lastRealFixMs = fix.timeMs
        lastSpeedMps = fix.speedMps ?: (snapshot.speedKmh ?: 0) / 3.6
        return snapshot
    }

    /**
     * GPS lost (tunnel, trench): keeps the car moving along the road ahead at its last speed, so
     * radars inside still alert. Null when there is nothing to follow, the car was slow, or the
     * signal has been lost for more than [MAX_COAST_MS].
     */
    fun coast(nowMs: Long): DriveSnapshot? {
        val prev = last ?: return null
        val lostSince = lastRealFixMs ?: return null
        if (nowMs - lostSince > MAX_COAST_MS || lastSpeedMps < MIN_COAST_MPS || prev.ahead.size < 2) return null
        val dt = (nowMs - prev.timeMs) / 1000.0
        if (dt <= 0) return null
        val road = Polyline(prev.ahead)
        val d = (lastSpeedMps * dt).coerceAtMost(road.lengthM)
        val fix = GpsFix(nowMs, road.pointAt(d), accuracyM = COAST_ACCURACY_M, speedMps = lastSpeedMps, bearingDeg = road.bearingAt(d))
        return step(fix, estimated = true)
    }

    private fun step(fix: GpsFix, estimated: Boolean): DriveSnapshot? {
        val state = filter.update(fix) ?: return null
        val net = network()
        val road = matcher.update(state)
        val roadLimit = road?.limitKmh
        val out = engine.step(state, road, net, motion = motion.update(state, fix))
        val zone = zoneTracker.step(state)
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
            ahead = out.ahead,
            events = out.events + zone.events,
            estimated = estimated,
            zone = zone.status,
        ).also { last = it }
    }

    companion object {
        const val MAX_COAST_MS = 60_000L
        private const val MIN_COAST_MPS = 3.0
        private const val COAST_ACCURACY_M = 10.0

        /** Replays a recorded trace and returns every snapshot. */
        fun replay(fixes: List<GpsFix>, session: DrivingSession): List<DriveSnapshot> = fixes.mapNotNull(session::process)
    }
}
