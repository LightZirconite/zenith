package zenith.core

/**
 * Official "itinéraire de contrôle" (radars.securite-routiere.gouv.fr): a stretch of road where a
 * movable radar rotates between cabins, so a radar may be anywhere along it. Valid both ways.
 */
data class ControlZone(val id: String, val points: List<LatLon>) {
    init {
        require(points.size >= 2) { "zone $id needs at least 2 points" }
    }

    /** Distance along the zone at each point, meters. */
    internal val cumulative: DoubleArray =
        points.zipWithNext { a, b -> Geo.distanceM(a, b) }.runningFold(0.0) { acc, d -> acc + d }.toDoubleArray()

    val lengthM: Double get() = cumulative.last()
}

/** The control zone being driven, with what is left of it in the travel direction. */
data class ZoneStatus(val zone: ControlZone, val remainingM: Double)

/** Result of one [ZoneTracker] step. */
data class ZoneStep(val status: ZoneStatus?, val events: List<AlertEvent>)

/**
 * Tells when the car drives along a control zone. The car must run close to the zone line and
 * along it (a crossing road never counts) for [enterFixes] fixes before the zone starts, and be
 * off it for [leaveFixes] fixes before it ends, so GPS noise does not flicker it. A zone entered
 * again shortly after leaving it (roundabout off the line) is not announced twice.
 */
class ZoneTracker(
    zones: Collection<ControlZone>,
    /** Zone lines are simplified and cut corners: generous distance, the heading does the rest. */
    private val maxDistanceM: Double = 40.0,
    private val headingToleranceDeg: Double = 35.0,
    private val enterFixes: Int = 3,
    private val leaveFixes: Int = 5,
    /** Entering this close to the end is not worth an announcement. */
    private val minRemainingM: Double = 300.0,
    private val reannounceAfterMs: Long = 3 * 60_000L,
) {
    private class Match(val zone: ControlZone, val remainingM: Double)

    private val index = GridIndex<Pair<ControlZone, Int>>(cellDeg = 0.01)
    private var current: Match? = null
    private var pendingId: String? = null
    private var pendingCount = 0
    private var misses = 0
    private val leftAtMs = HashMap<String, Long>()

    init {
        for (z in zones) {
            for (k in 0 until z.points.size - 1) index.insert(BBox.of(listOf(z.points[k], z.points[k + 1])), z to k)
        }
    }

    fun step(state: KinematicState): ZoneStep {
        val now = state.timeMs
        val heading = state.headingDeg.takeIf { state.speedMps > MIN_SPEED_MPS }
        val match = heading?.let { match(state.position, it) }
        val events = mutableListOf<AlertEvent>()
        val cur = current
        if (cur != null) {
            if (match != null && match.zone.id == cur.zone.id) {
                misses = 0
                current = match
            } else if (heading != null && ++misses >= leaveFixes) { // stopped (red light): the zone goes on
                current = null
                leftAtMs[cur.zone.id] = now
                events += AlertEvent.ZoneLeft(now, cur.zone)
            }
        } else if (match != null && match.remainingM >= minRemainingM) {
            pendingCount = if (pendingId == match.zone.id) pendingCount + 1 else 1
            pendingId = match.zone.id
            if (pendingCount >= enterFixes) {
                current = match
                misses = 0
                pendingId = null
                val left = leftAtMs[match.zone.id]
                if (left == null || now - left > reannounceAfterMs) events += AlertEvent.ZoneEntered(now, match.zone, match.remainingM)
            }
        } else {
            pendingId = null
        }
        return ZoneStep(current?.let { ZoneStatus(it.zone, it.remainingM) }, events)
    }

    /** A fix while not driving (stopped, or a phone at home): nothing enters, a zone under way goes on. */
    fun idle(): ZoneStep {
        pendingId = null
        return ZoneStep(current?.let { ZoneStatus(it.zone, it.remainingM) }, emptyList())
    }

    /** Closest zone the car runs along, either way, with the distance left to its end. */
    private fun match(p: LatLon, heading: Double): Match? {
        val frame = LocalFrame(p)
        var best: Match? = null
        var bestD = maxDistanceM
        for ((zone, k) in index.query(BBox.around(p, maxDistanceM))) {
            val a = frame.toXY(zone.points[k])
            val ab = frame.toXY(zone.points[k + 1]) - a
            val len2 = ab.dot(ab)
            if (len2 == 0.0) continue
            val t = ((Vec2(0.0, 0.0) - a).dot(ab) / len2).coerceIn(0.0, 1.0)
            val d = (a + ab * t).length()
            if (d > bestD) continue
            val along = Geo.headingDiffDeg(heading, ab.headingDeg())
            val forward = when {
                along <= headingToleranceDeg -> true
                180 - along <= headingToleranceDeg -> false
                else -> continue
            }
            val offset = zone.cumulative[k] + t * (zone.cumulative[k + 1] - zone.cumulative[k])
            best = Match(zone, if (forward) zone.lengthM - offset else offset)
            bestD = d
        }
        return best
    }

    private companion object {
        const val MIN_SPEED_MPS = 3.0
    }
}
