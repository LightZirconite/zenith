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
    /** Length of the road ahead drawn on the map. */
    val aheadDrawnM: Double = 1_500.0,
    /** Next limit change shown this far ahead along the road. */
    val limitAheadM: Double = 2_000.0,
    /** A new spoken overspeed warning needs this long since the last one... */
    val overspeedRepeatMs: Long = 30_000,
    /** ...or this long back under the limit. */
    val overspeedResetMs: Long = 10_000,
    /**
     * A radar of the same kind this close to one already announced is the same camera from another
     * source (positions differ a little): tracked, but silent.
     */
    val twinM: Double = 150.0,
    /** A section already under way is picked up only with at least this much of it left. */
    val minResumeM: Double = 300.0,
)

enum class OverspeedLevel { NONE, OVER, WARN }

sealed interface AlertEvent {
    val timeMs: Long

    data class Approach(override val timeMs: Long, val radar: Radar, val distanceM: Double, val limitKmh: Int?) : AlertEvent
    data class Brake(override val timeMs: Long, val radar: Radar, val distanceM: Double, val limitKmh: Int) : AlertEvent
    /** [points]: licence points at stake when [wouldFlash], from the retained speed. */
    data class Passed(
        override val timeMs: Long,
        val radar: Radar,
        val speedKmh: Double,
        val limitKmh: Int?,
        val wouldFlash: Boolean,
        val points: Int = 0,
    ) : AlertEvent
    /** [resumed]: the car was already inside (drive started there, road found late); [lengthM] is what is left. */
    data class SectionStarted(
        override val timeMs: Long,
        val radar: Radar,
        val lengthM: Double,
        val limitKmh: Int,
        val resumed: Boolean = false,
    ) : AlertEvent
    data class SectionEnded(override val timeMs: Long, val averageKmh: Double, val limitKmh: Int, val wouldFlash: Boolean) : AlertEvent
    data class Overspeed(override val timeMs: Long, val level: OverspeedLevel, val speedKmh: Int, val limitKmh: Int) : AlertEvent
    data class LimitChanged(override val timeMs: Long, val fromKmh: Int?, val toKmh: Int?) : AlertEvent
    data class ZoneEntered(override val timeMs: Long, val zone: ControlZone, val remainingM: Double) : AlertEvent
    data class ZoneLeft(override val timeMs: Long, val zone: ControlZone) : AlertEvent
}

data class ActiveAlert(val radar: Radar, val distanceM: Double, val limitKmh: Int?, val overLimit: Boolean, val ageMs: Long?)

/** Average-speed section under way. [lengthM]: what was tracked (the whole section, or what was left when picked up). */
data class SectionStatus(
    val limitKmh: Int,
    val averageKmh: Double,
    val remainingM: Double,
    val maxSpeedToFinishKmh: Double?,
    val lengthM: Double = remainingM,
) {
    /** Speed to hold until the end: the limit when the average allows it, never above it. */
    val targetKmh: Int get() = minOf(limitKmh, maxSpeedToFinishKmh?.toInt() ?: limitKmh)

    /** The average can no longer end under the limit at any reasonable speed. */
    val lost: Boolean get() = maxSpeedToFinishKmh != null && maxSpeedToFinishKmh < MIN_REASONABLE_KMH

    private companion object {
        const val MIN_REASONABLE_KMH = 30.0
    }
}

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
    /** Road the driver most likely follows, from the car onwards (for the map). */
    val ahead: List<LatLon> = emptyList(),
)

/**
 * Turns the filtered vehicle state into radar, section and overspeed alerts.
 * Radars are only considered when they are ahead, measure the travel direction and are within
 * the speed-dependent alert distance; each one goes approach → (brake) → passed exactly once.
 * With a matched road, "ahead" means reachable by road ([RoadHorizon]); radars off every road, or
 * drives without road data, fall back to a cone in front of the car so no alert is lost.
 * A section end that carries the section length but has no start camera gets a virtual start,
 * placed that far back on the road; a car already inside such a section picks it up.
 */
class AlertEngine(
    /** Replaced when a newer radar pack is loaded. */
    var radars: RadarIndex,
    private val settings: AlertSettings = AlertSettings(),
    /** Turns a nominal limit into the one that applies to the driver (rain, probationary licence). */
    private val effectiveLimit: (Int) -> Int = { it },
) {
    /** [silent]: same camera as one already announced, from another source. */
    private class Track(
        val radar: Radar,
        var minDistanceM: Double,
        var speedAtMinMps: Double,
        var brakeAnnounced: Boolean = false,
        val silent: Boolean = false,
    ) {
        /** Last distance by road while it was still ahead, and the speed then; null in straight-line mode. */
        var lastRoadM: Double? = null
        var speedAtLastRoadMps = 0.0
    }

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

    /** Section ends whose section was started without a start camera, with the time it was. */
    private val sectionsHandled = HashMap<String, Long>()

    /**
     * One step of the drive. [road] is the matched road in [network]; both null without road data,
     * which keeps the straight-line behaviour. [motion] comes from [MotionGate]: while not moving
     * (stopped at a light, or parked) nothing new is announced and radars already announced are
     * left as they were; parked (phone at home), nothing is shown either, until the car drives again.
     */
    fun step(
        state: KinematicState,
        road: RoadCandidate? = null,
        network: RoadNetwork? = null,
        motion: Motion = Motion.MOVING,
    ): AlertOutput {
        val events = mutableListOf<AlertEvent>()
        val now = state.timeMs
        val v = state.speedMps
        val speedKmh = v * 3.6
        val limit = road?.limitKmh?.let(effectiveLimit)
        // A limit guessed from the place (built-up or not) is shown, but never spoken nor lent to a radar.
        val sure = road?.segment?.limitSource != LimitSource.AREA
        val roadLimit = limit.takeIf { sure }

        if (limit != lastLimit) {
            if (lastPosition != null) events += AlertEvent.LimitChanged(now, lastLimit, limit)
            lastLimit = limit
        }

        // Standing (red light, jam): heading and road match are noise, so nothing moves on until the car does.
        val moving = motion == Motion.MOVING
        advanceSection(state, moving, events)
        recentlyPassed.entries.removeIf { now - it.value > 5 * 60_000 }
        sectionsHandled.entries.removeIf { now - it.value > 30 * 60_000 }

        val heading = state.headingDeg
        val triggerM = (v * settings.lookaheadS + v * v / (2 * settings.comfortDecelMps2))
            .coerceIn(settings.minAlertM, settings.maxAlertM)
        val horizon = horizon(road, network)

        var upcoming: UpcomingRadar? = null
        val searchM = if (horizon != null) settings.upcomingRangeM else maxOf(triggerM + 200, settings.fallbackUpcomingM)
        // Virtual section starts are queued behind the radars they come from.
        val queue = ArrayDeque(if (moving) radars.near(state.position, searchM) else emptyList())
        while (queue.isNotEmpty()) {
            val r = queue.removeFirst()
            if (r.type.mobile && r.reportedAtMs != null && now - r.reportedAtMs > settings.mobileMaxAgeMs) continue
            if (heading == null || r.id in recentlyPassed) continue
            val snaps = horizon?.snaps(r).orEmpty()
            val d: Double
            if (horizon != null && snaps.isNotEmpty()) {
                val onPath = horizon.pathDistance(snaps)
                if (onPath != null && (upcoming == null || onPath.first < upcoming.distanceM)) {
                    upcoming = UpcomingRadar(r, onPath.first, radarLimit(r, roadLimit), onPath.second.segment.name)
                }
                // Only when the measured direction is known: a section the other way must not show.
                val oneWay = r.directionDeg != null || snaps.singleOrNull()?.segment?.oneway == true
                if (onPath != null && r.type == RadarType.SECTION_END && r.sectionLengthM != null && oneWay) {
                    sectionWithoutStart(r, onPath.first, horizon.path, now, roadLimit, events)?.let(queue::addLast)
                }
                d = horizon.roadDistance(snaps) ?: continue
                // A side branch only counts ahead of the car: behind it, it could only be reached by
                // turning back (roundabout, loop), and its camera measures the other way.
                if (onPath == null && (d > settings.branchAlertM || behind(state.position, heading, r.position))) continue
            } else {
                d = Geo.distanceM(state.position, r.position)
                if (!isAhead(state.position, heading, r.position, d)) continue
                if (r.directionDeg != null && Geo.headingDiffDeg(heading, r.directionDeg) > settings.directionToleranceDeg) continue
                if (horizon == null && d <= settings.fallbackUpcomingM && (upcoming == null || d < upcoming.distanceM)) {
                    upcoming = UpcomingRadar(r, d, radarLimit(r, roadLimit))
                }
            }
            if (r.id !in tracked && d <= triggerM) {
                val twin = tracked.values.any { t ->
                    !t.silent && t.radar.type == r.type && Geo.distanceM(t.radar.position, r.position) <= settings.twinM &&
                        (t.radar.limitKmh == null || r.limitKmh == null || t.radar.limitKmh == r.limitKmh)
                }
                tracked[r.id] = Track(r, Geo.distanceM(state.position, r.position), v, silent = twin)
                if (!twin) events += AlertEvent.Approach(now, r, d, radarLimit(r, roadLimit))
            }
        }

        val distances = HashMap<String, Double>()
        val iterator = tracked.values.iterator()
        while (moving && iterator.hasNext()) {
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
            if (roadD != null) {
                t.lastRoadM = roadD
                t.speedAtLastRoadMps = v
            }
            // Gone by: the road distance was nearly used up when it vanished (two fixes at most), or
            // without road, the radar is now behind. Otherwise the car turned off before it.
            val reached = t.lastRoadM?.let { it < maxOf(REACHED_M, 2.5 * t.speedAtLastRoadMps) }
                ?: (heading == null || behind(state.position, heading, r.position))
            val leaving = (t.minDistanceM < 120 && straight > t.minDistanceM + 25) || (!ahead && straight < 60 && t.minDistanceM < 60)
            if (leaving && reached && !ahead) {
                iterator.remove()
                recentlyPassed[r.id] = now
                onPassed(t, now, roadLimit, events)
                continue
            }
            if (!ahead && (straight > 150 || leaving)) {
                // Turned off before reaching it.
                iterator.remove()
                continue
            }
            val d = roadD ?: straight
            distances[r.id] = d
            val rl = radarLimit(r, roadLimit)
            if (rl != null && !t.brakeAnnounced && !t.silent) {
                val vl = rl / 3.6
                val needed = (v * v - vl * vl) / (2 * maxOf(d - 20, 1.0))
                if (v > vl && needed > settings.comfortDecelMps2) {
                    t.brakeAnnounced = true
                    events += AlertEvent.Brake(now, r, d, rl)
                }
            }
        }

        overspeed(now, speedKmh, limit, spoken = sure, events)

        lastPosition = state.position
        val active = tracked.values.filter { motion != Motion.PARKED }.map { t ->
            val rl = radarLimit(t.radar, roadLimit)
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
        val ahead = horizon?.let { RoadHorizon.polyline(it.path, settings.aheadDrawnM) }.orEmpty()
        return AlertOutput(events, active, sectionStatus(), upcoming, limitAhead, ahead)
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

    /**
     * Silent "over" state, spoken warning past the tolerance, not repeated while hovering around it.
     * Not [spoken] (limit guessed from the place): never past the silent state.
     */
    private fun overspeed(now: Long, speedKmh: Double, limit: Int?, spoken: Boolean, events: MutableList<AlertEvent>) {
        val level = when {
            limit == null -> OverspeedLevel.NONE
            speedKmh > limit + settings.overspeedToleranceKmh -> if (spoken) OverspeedLevel.WARN else OverspeedLevel.OVER
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

    private fun behind(p: LatLon, heading: Double, target: LatLon): Boolean =
        Geo.distanceM(p, target) > 15 && Geo.headingDiffDeg(heading, Geo.bearingDeg(p, target)) > 90

    private fun isAhead(p: LatLon, heading: Double, target: LatLon, d: Double): Boolean {
        if (d < 15) return true
        val angle = Geo.headingDiffDeg(heading, Geo.bearingDeg(p, target))
        // Near the radar, accept a wider angle as long as it stays close to the road axis.
        return angle <= settings.aheadConeDeg || (angle < 90 && d * sin(Math.toRadians(angle)) < 25)
    }

    private fun radarLimit(r: Radar, roadLimit: Int?): Int? =
        if (!r.type.measuresSpeed) null else r.limitKmh?.let(effectiveLimit) ?: roadLimit

    /**
     * Section end [end] ahead on [path], [distanceM] away, with the section length but no start
     * camera. While the start is ahead, returns it as a virtual radar placed on the road; once the
     * car is inside without having passed it, picks the section up with what is left.
     */
    private fun sectionWithoutStart(
        end: Radar,
        distanceM: Double,
        path: List<HorizonStep>,
        now: Long,
        roadLimit: Int?,
        events: MutableList<AlertEvent>,
    ): Radar? {
        val length = end.sectionLengthM ?: return null
        if (end.id in sectionsHandled) return null
        val startId = end.id + VIRTUAL_START
        val entryM = distanceM - length
        if (entryM > 0) {
            val (position, bearing) = RoadHorizon.pointAt(path, entryM) ?: return null
            return Radar(startId, position, RadarType.SECTION_START, end.limitKmh, bearing, sectionLengthM = length)
        }
        // Its start just reached: passing it begins the section.
        if (startId in tracked) return null
        val limit = radarLimit(end, roadLimit) ?: return null
        if (section == null && distanceM >= settings.minResumeM) {
            section = SectionTracker(distanceM, limit, now)
            sectionLimit = limit
            sectionsHandled[end.id] = now
            events += AlertEvent.SectionStarted(now, end, distanceM, limit, resumed = true)
        }
        return null
    }

    private fun onPassed(t: Track, now: Long, roadLimit: Int?, events: MutableList<AlertEvent>) {
        val r = t.radar
        val rl = radarLimit(r, roadLimit)
        val speedKmh = t.speedAtMinMps * 3.6
        // Section cameras time the car: they never flash its speed on the spot.
        val spot = r.type != RadarType.SECTION_START && r.type != RadarType.SECTION_END
        val retained = SpeedRules.retainedSpeed(speedKmh, movingRadar = r.type == RadarType.MOBILE_VEHICLE)
        val flash = spot && rl != null && retained > rl
        val points = if (flash && rl != null) SpeedRules.pointsLost(retained - rl) else 0
        if (!t.silent) events += AlertEvent.Passed(now, r, speedKmh, rl, wouldFlash = flash, points = points)
        when (r.type) {
            RadarType.SECTION_START -> if (r.sectionLengthM != null && rl != null && (!t.silent || section == null)) {
                section = SectionTracker(r.sectionLengthM, rl, now)
                sectionLimit = rl
                if (r.id.endsWith(VIRTUAL_START)) sectionsHandled[r.id.removeSuffix(VIRTUAL_START)] = now
                events += AlertEvent.SectionStarted(now, r, r.sectionLengthM, rl)
            }
            RadarType.SECTION_END -> endSection(now, events)
            else -> Unit
        }
    }

    /** Standing, GPS noise is no progress (a jam would end the section early); moving, no more than the speed allows. */
    private fun advanceSection(state: KinematicState, moving: Boolean, events: MutableList<AlertEvent>) {
        val s = section ?: return
        val last = lastPosition ?: return
        val dtS = (state.timeMs - s.lastTimeMs) / 1000.0
        val step = if (moving) minOf(Geo.distanceM(last, state.position), state.speedMps * dtS * 1.5 + 5) else 0.0
        s.advance(step.coerceAtLeast(0.0), state.timeMs)
        if (s.remainingM <= 0) endSection(state.timeMs, events)
    }

    private fun endSection(now: Long, events: MutableList<AlertEvent>) {
        val s = section ?: return
        val avg = s.averageKmh()
        events += AlertEvent.SectionEnded(now, avg, sectionLimit, wouldFlash = SpeedRules.retainedSpeed(avg) > sectionLimit)
        section = null
    }

    private fun sectionStatus(): SectionStatus? = section?.let {
        SectionStatus(sectionLimit, it.averageKmh(), it.remainingM, it.maxSpeedToFinishKmh(), it.lengthM)
    }

    private companion object {
        /** Id suffix of the start placed on the road for a section end without a start camera. */
        const val VIRTUAL_START = ">start"

        /** A tracked radar whose road distance vanishes under this (or two fixes of travel) was reached. */
        const val REACHED_M = 30.0
    }
}
