package zenith.core

import kotlin.math.roundToInt
import kotlin.math.sin

data class AlertSettings(
    /** Announce a radar this many seconds before reaching it, plus the comfortable braking distance. */
    val lookaheadS: Double = 20.0,
    val minAlertM: Double = 300.0,
    val maxAlertM: Double = 3_000.0,
    val comfortDecelMps2: Double = 1.5,
    /** Half-angle of the cone in front of the car where radars count as ahead. */
    val aheadConeDeg: Double = 35.0,
    /** Max difference between travel heading and the radar's measured direction. */
    val directionToleranceDeg: Double = 50.0,
    /** Over the limit by more than this many km/h triggers the audible overspeed warning. */
    val overspeedToleranceKmh: Int = 3,
    val mobileMaxAgeMs: Long = 3 * 3_600_000L,
    /** How far ahead the "next radar" is looked up along the road. */
    val upcomingRangeM: Double = 15_000.0,
    /**
     * A radar on a side branch (not on the most probable road) is announced only this close, so an
     * exit ramp radar does not alert the whole motorway, yet still alerts before the turn-off.
     */
    val branchAlertM: Double = 500.0,
    /** Without a matched road, the "next radar" is only guessed this close, in a straight line. */
    val fallbackUpcomingM: Double = 3_000.0,
    /** Next limit change shown this far ahead along the road. */
    val limitAheadM: Double = 2_000.0,
    /** A new spoken overspeed warning needs this long since the last one... */
    val overspeedRepeatMs: Long = 30_000,
    /** ...or this long back under the limit. */
    val overspeedResetMs: Long = 10_000,
)

enum class OverspeedLevel { NONE, OVER, WARN }

sealed interface AlertEvent {
    val timeMs: Long

    data class Approach(override val timeMs: Long, val radar: Radar, val distanceM: Double, val limitKmh: Int?) : AlertEvent
    data class Brake(override val timeMs: Long, val radar: Radar, val distanceM: Double, val limitKmh: Int) : AlertEvent
    data class Passed(override val timeMs: Long, val radar: Radar, val speedKmh: Double, val limitKmh: Int?, val wouldFlash: Boolean) : AlertEvent
    data class SectionStarted(override val timeMs: Long, val radar: Radar, val lengthM: Double, val limitKmh: Int) : AlertEvent
    data class SectionEnded(override val timeMs: Long, val averageKmh: Double, val limitKmh: Int, val wouldFlash: Boolean) : AlertEvent
    data class Overspeed(override val timeMs: Long, val level: OverspeedLevel, val speedKmh: Int, val limitKmh: Int) : AlertEvent
    data class LimitChanged(override val timeMs: Long, val fromKmh: Int?, val toKmh: Int?) : AlertEvent
}

data class ActiveAlert(val radar: Radar, val distanceM: Double, val limitKmh: Int?, val overLimit: Boolean, val ageMs: Long?)

data class SectionStatus(val limitKmh: Int, val averageKmh: Double, val remainingM: Double, val maxSpeedToFinishKmh: Double?)

/**
 * Closest radar on the road ahead, even before its alert distance. [distanceM] is along the road
 * when [roadName] comes from the horizon, else a straight line (no road matched).
 */
data class UpcomingRadar(val radar: Radar, val distanceM: Double, val limitKmh: Int?, val roadName: String? = null)

data class AlertOutput(
    val events: List<AlertEvent>,
    val active: List<ActiveAlert>,
    val section: SectionStatus?,
    val upcoming: UpcomingRadar? = null,
    val limitAhead: LimitAhead? = null,
)

/**
 * Turns the filtered vehicle state into radar, section and overspeed alerts.
 * Radars are only considered when they are ahead, measure the travel direction and are within
 * the speed-dependent alert distance; each one goes approach → (brake) → passed exactly once.
 * With a matched road, "ahead" means reachable by road ([RoadHorizon]); radars off every road, or
 * drives without road data, fall back to a cone in front of the car so no alert is lost.
 */
class AlertEngine(
    /** Replaced when a newer radar pack is loaded. */
    var radars: RadarIndex,
    private val settings: AlertSettings = AlertSettings(),
    /** Turns a nominal limit into the one that applies to the driver (rain, probationary licence). */
    private val effectiveLimit: (Int) -> Int = { it },
) {
    private class Track(val radar: Radar, var minDistanceM: Double, var speedAtMinMps: Double, var brakeAnnounced: Boolean = false)

    /** Where radars stand on the road ahead, for one step. Null fields: no matched road. */
    private class Horizon(
        val snapper: RadarSnapper,
        val path: List<HorizonStep>,
        val reachable: Map<Pair<Long, Boolean>, HorizonStep>,
    ) {
        /** Snaps of [r]; empty when it stands on no known road. */
        fun snaps(r: Radar) = snapper.snap(r)

        fun roadDistance(snaps: List<RadarOnRoad>): Double? =
            snaps.mapNotNull { s -> reachable[s.segment.id to s.forward]?.let { RoadHorizon.distanceTo(listOf(it), s) } }.minOrNull()

        fun pathDistance(snaps: List<RadarOnRoad>): Pair<Double, RadarOnRoad>? =
            snaps.mapNotNull { s -> RoadHorizon.distanceTo(path, s)?.let { it to s } }.minByOrNull { it.first }
    }

    private val tracked = LinkedHashMap<String, Track>()
    private val recentlyPassed = HashMap<String, Long>()
    private var section: SectionTracker? = null
    private var sectionLimit = 0
    private var lastPosition: LatLon? = null
    private var overspeed = OverspeedLevel.NONE
    private var lastWarnMs: Long? = null
    private var underLimitSinceMs: Long? = null
    private var lastLimit: Int? = null
    private var snapper: RadarSnapper? = null

    /**
     * One step of the drive. [road] is the matched road in [network]; both null without road data,
     * which keeps the straight-line behaviour.
     */
    fun step(state: KinematicState, road: RoadCandidate? = null, network: RoadNetwork? = null): AlertOutput {
        val events = mutableListOf<AlertEvent>()
        val now = state.timeMs
        val v = state.speedMps
        val speedKmh = v * 3.6
        val limit = road?.limitKmh?.let(effectiveLimit)

        if (limit != lastLimit) {
            if (lastPosition != null) events += AlertEvent.LimitChanged(now, lastLimit, limit)
            lastLimit = limit
        }

        advanceSection(state, events)
        recentlyPassed.entries.removeIf { now - it.value > 5 * 60_000 }

        val heading = state.headingDeg
        val triggerM = (v * settings.lookaheadS + v * v / (2 * settings.comfortDecelMps2))
            .coerceIn(settings.minAlertM, settings.maxAlertM)
        val horizon = horizon(road, network)

        var upcoming: UpcomingRadar? = null
        val searchM = if (horizon != null) settings.upcomingRangeM else maxOf(triggerM + 200, settings.fallbackUpcomingM)
        for (r in radars.near(state.position, searchM)) {
            if (r.type.mobile && r.reportedAtMs != null && now - r.reportedAtMs > settings.mobileMaxAgeMs) continue
            if (heading == null || r.id in recentlyPassed) continue
            val snaps = horizon?.snaps(r).orEmpty()
            val d: Double
            if (horizon != null && snaps.isNotEmpty()) {
                val onPath = horizon.pathDistance(snaps)
                if (onPath != null && (upcoming == null || onPath.first < upcoming.distanceM)) {
                    upcoming = UpcomingRadar(r, onPath.first, radarLimit(r, limit), onPath.second.segment.name)
                }
                d = horizon.roadDistance(snaps) ?: continue
                if (onPath == null && d > settings.branchAlertM) continue
            } else {
                d = Geo.distanceM(state.position, r.position)
                if (!isAhead(state.position, heading, r.position, d)) continue
                if (r.directionDeg != null && Geo.headingDiffDeg(heading, r.directionDeg) > settings.directionToleranceDeg) continue
                if (horizon == null && d <= settings.fallbackUpcomingM && (upcoming == null || d < upcoming.distanceM)) {
                    upcoming = UpcomingRadar(r, d, radarLimit(r, limit))
                }
            }
            if (r.id !in tracked && d <= triggerM) {
                tracked[r.id] = Track(r, Geo.distanceM(state.position, r.position), v)
                events += AlertEvent.Approach(now, r, d, radarLimit(r, limit))
            }
        }

        val distances = HashMap<String, Double>()
        val iterator = tracked.values.iterator()
        while (iterator.hasNext()) {
            val t = iterator.next()
            val r = t.radar
            val straight = Geo.distanceM(state.position, r.position)
            if (straight < t.minDistanceM) {
                t.minDistanceM = straight
                t.speedAtMinMps = v
            }
            val snaps = horizon?.snaps(r).orEmpty()
            val roadD = if (horizon != null && snaps.isNotEmpty()) horizon.roadDistance(snaps) else null
            val ahead = if (horizon != null && snaps.isNotEmpty()) {
                roadD != null
            } else {
                heading != null && isAhead(state.position, heading, r.position, straight)
            }
            val passed = (t.minDistanceM < 120 && straight > t.minDistanceM + 25) || (!ahead && straight < 60 && t.minDistanceM < 60)
            if (passed) {
                iterator.remove()
                recentlyPassed[r.id] = now
                onPassed(t, now, limit, events)
                continue
            }
            if (!ahead && straight > 150) {
                // Turned off before reaching it.
                iterator.remove()
                continue
            }
            val d = roadD ?: straight
            distances[r.id] = d
            val rl = radarLimit(r, limit)
            if (rl != null && !t.brakeAnnounced) {
                val vl = rl / 3.6
                val needed = (v * v - vl * vl) / (2 * maxOf(d - 20, 1.0))
                if (v > vl && needed > settings.comfortDecelMps2) {
                    t.brakeAnnounced = true
                    events += AlertEvent.Brake(now, r, d, rl)
                }
            }
        }

        overspeed(now, speedKmh, limit, events)

        lastPosition = state.position
        val active = tracked.values.map { t ->
            val rl = radarLimit(t.radar, limit)
            ActiveAlert(
                radar = t.radar,
                distanceM = distances[t.radar.id] ?: Geo.distanceM(state.position, t.radar.position),
                limitKmh = rl,
                overLimit = rl != null && speedKmh >= rl + 1,
                ageMs = t.radar.reportedAtMs?.let { now - it },
            )
        }.sortedBy { it.distanceM }
        val limitAhead = horizon?.let { RoadHorizon.nextLimit(it.path, settings.limitAheadM) }
            ?.let { it.copy(limitKmh = effectiveLimit(it.limitKmh)) }
            ?.takeIf { it.limitKmh != limit }
        return AlertOutput(events, active, sectionStatus(), upcoming, limitAhead)
    }

    private fun horizon(road: RoadCandidate?, network: RoadNetwork?): Horizon? {
        if (road == null || network == null) return null
        val s = snapper?.takeIf { it.network === network } ?: RadarSnapper(network).also { snapper = it }
        return Horizon(
            snapper = s,
            path = RoadHorizon.mostProbablePath(network, road, settings.upcomingRangeM),
            // Constant range: a tracked radar must stay reachable when the car slows down.
            reachable = RoadHorizon.reachable(network, road, settings.maxAlertM + 200),
        )
    }

    /** Silent "over" state, spoken warning past the tolerance, not repeated while hovering around it. */
    private fun overspeed(now: Long, speedKmh: Double, limit: Int?, events: MutableList<AlertEvent>) {
        val level = when {
            limit == null -> OverspeedLevel.NONE
            speedKmh > limit + settings.overspeedToleranceKmh -> OverspeedLevel.WARN
            speedKmh >= limit + 1 -> OverspeedLevel.OVER
            else -> OverspeedLevel.NONE
        }
        if (level == OverspeedLevel.NONE) {
            if (underLimitSinceMs == null) underLimitSinceMs = now
        } else {
            underLimitSinceMs = null
        }
        if (limit != null && level > overspeed) {
            val warn = level == OverspeedLevel.WARN
            val last = lastWarnMs
            val calmedDown = last == null || now - last >= settings.overspeedRepeatMs
            if (!warn || calmedDown) events += AlertEvent.Overspeed(now, level, speedKmh.roundToInt(), limit)
            if (warn && calmedDown) lastWarnMs = now
        }
        // Long enough back under the limit: the next excess is a new one.
        if (underLimitSinceMs?.let { now - it >= settings.overspeedResetMs } == true) lastWarnMs = null
        overspeed = level
    }

    private fun isAhead(p: LatLon, heading: Double, target: LatLon, d: Double): Boolean {
        if (d < 15) return true
        val angle = Geo.headingDiffDeg(heading, Geo.bearingDeg(p, target))
        // Near the radar, accept a wider angle as long as it stays close to the road axis.
        return angle <= settings.aheadConeDeg || (angle < 90 && d * sin(Math.toRadians(angle)) < 25)
    }

    private fun radarLimit(r: Radar, roadLimit: Int?): Int? = r.limitKmh?.let(effectiveLimit) ?: roadLimit

    private fun onPassed(t: Track, now: Long, roadLimit: Int?, events: MutableList<AlertEvent>) {
        val r = t.radar
        val rl = radarLimit(r, roadLimit)
        val speedKmh = t.speedAtMinMps * 3.6
        val retained = SpeedRules.retainedSpeed(speedKmh, movingRadar = r.type == RadarType.MOBILE_VEHICLE)
        events += AlertEvent.Passed(now, r, speedKmh, rl, wouldFlash = rl != null && retained > rl)
        when (r.type) {
            RadarType.SECTION_START -> if (r.sectionLengthM != null && rl != null) {
                section = SectionTracker(r.sectionLengthM, rl, now)
                sectionLimit = rl
                events += AlertEvent.SectionStarted(now, r, r.sectionLengthM, rl)
            }
            RadarType.SECTION_END -> endSection(now, events)
            else -> Unit
        }
    }

    private fun advanceSection(state: KinematicState, events: MutableList<AlertEvent>) {
        val s = section ?: return
        val last = lastPosition ?: return
        s.advance(Geo.distanceM(last, state.position), state.timeMs)
        if (s.remainingM <= 0) endSection(state.timeMs, events)
    }

    private fun endSection(now: Long, events: MutableList<AlertEvent>) {
        val s = section ?: return
        val avg = s.averageKmh()
        events += AlertEvent.SectionEnded(now, avg, sectionLimit, wouldFlash = SpeedRules.retainedSpeed(avg) > sectionLimit)
        section = null
    }

    private fun sectionStatus(): SectionStatus? = section?.let {
        SectionStatus(sectionLimit, it.averageKmh(), it.remainingM, it.maxSpeedToFinishKmh())
    }
}
