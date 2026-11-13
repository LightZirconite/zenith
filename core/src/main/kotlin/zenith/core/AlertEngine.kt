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
    /** Early warning ([AlertEngine.earlySpeeding]): over the limit by more than this many km/h. */
    val earlyToleranceKmh: Int = 3,
    /**
     * A limit is said only once it has held this long: right after a change the road match may
     * still lag behind the sign, and a driver needs a moment to slow down.
     */
    val overspeedGraceMs: Long = 6_000,
    /** Fixes in a row over the threshold before it is said (one fix of GPS noise is not speeding). */
    val overspeedFixes: Int = 2,
    /** A higher limit this close ahead (or within 2 s) is already the limit: the sign is being passed. */
    val limitAnticipateM: Double = 60.0,
    /** A road matched farther than this from the fix may not be the road driven: its limit is never said. */
    val spokenMatchM: Double = 25.0,
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
    /**
     * Too fast from 3 km/h over the limit, instead of from the speed a radar flashes at once its
     * margin is taken off (56 for 50, 96 for 90, 138 for 130). Can change during a drive.
     */
    @kotlin.concurrent.Volatile
    var earlySpeeding: Boolean = false

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

        /** Nearest it has been by road, and the distance driven then. */
        var minRoadM: Double? = null
        var odometerAtMinRoadM = 0.0
    }

    /**
     * A track given up: when, and where the radar was expected by road ([roadM], with the distance
     * driven then, [odometerM]); null [roadM] without a road.
     */
    private class Dropped(val timeMs: Long, val odometerM: Double, val roadM: Double?)

    /** Where radars stand on the road ahead, for one step. Null fields: no matched road. */
    private class Horizon(
        val snapper: RadarSnapper,
        val path: List<HorizonStep>,
        val reachable: Map<Pair<Long, Boolean>, HorizonStep>,
    ) {
        /** Snaps of [r]; empty when it stands on no known road. */
        fun snaps(r: Radar) = snapper.snap(r)

        /** Shortest distance by any road, with the snap it reaches. */
        fun roadDistance(snaps: List<RadarOnRoad>): Pair<Double, RadarOnRoad>? =
            snaps.mapNotNull { s -> reachable[s.segment.id to s.forward]?.let { RoadHorizon.distanceTo(listOf(it), s) }?.let { it to s } }
                .minByOrNull { it.first }

        fun pathDistance(snaps: List<RadarOnRoad>): Pair<Double, RadarOnRoad>? =
            snaps.mapNotNull { s -> RoadHorizon.distanceTo(path, s)?.let { it to s } }.minByOrNull { it.first }
    }

    private val tracked = LinkedHashMap<String, Track>()
    private val recentlyPassed = HashMap<String, Long>()
    /** Radars just passed, kept a little while: a copy of one met after it is the same camera. */
    private val justPassed = HashMap<String, Pair<Radar, Long>>()
    /**
     * Radars the car drove right by, whatever came of their track (other carriageway, other
     * direction, twin): once behind the car, they are behind it, even when the road ahead loops
     * back to them (U-turn at a roundabout onto the other carriageway of the same road).
     */
    private val drivenBy = HashMap<String, Long>()
    /**
     * Tracks given up (turned off before them, or the road to them grew longer). Picked up again
     * where the drive since then says it should be (the road match hesitated), they are shown, not
     * spoken twice; picked up nearer by far (a side road announced it, the car went the long way
     * and now really comes to it), they are announced again.
     */
    private val dropped = HashMap<String, Dropped>()
    /** Distance driven since the start, meters. */
    private var odometerM = 0.0
    private var section: SectionTracker? = null
    private var sectionLimit = 0
    private var lastPosition: LatLon? = null
    private var overspeed = OverspeedLevel.NONE
    private var lastWarnMs: Long? = null
    private var underLimitSinceMs: Long? = null
    private var lastLimit: Int? = null
    private var limitSinceMs = 0L
    private var overFixes = 0
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
        /** Speed shown to the driver (accurate Doppler); "too fast" is judged on it when known. */
        shownKmh: Int? = null,
    ): AlertOutput {
        val events = mutableListOf<AlertEvent>()
        val now = state.timeMs
        val v = state.speedMps
        val speedKmh = shownKmh?.toDouble() ?: (v * 3.6)
        val limit = road?.limitKmh?.let(effectiveLimit)
        // A limit guessed from the place (built-up or not) is shown, but never spoken nor lent to a radar.
        val sure = road?.segment?.limitSource != LimitSource.AREA
        val roadLimit = limit.takeIf { sure }

        if (limit != lastLimit) {
            if (lastPosition != null) events += AlertEvent.LimitChanged(now, lastLimit, limit)
            lastLimit = limit
            limitSinceMs = now
        }

        // Standing (red light, jam): heading and road match are noise, so nothing moves on until the car does.
        val moving = motion == Motion.MOVING
        if (moving) lastPosition?.let { odometerM += Geo.distanceM(it, state.position) }
        advanceSection(state, moving, events)
        recentlyPassed.entries.removeAll { now - it.value > 5 * 60_000 }
        justPassed.entries.removeAll { now - it.value.second > JUST_PASSED_MS }
        drivenBy.entries.removeAll { now - it.value > 5 * 60_000 }
        dropped.entries.removeAll { now - it.value.timeMs > DROPPED_MS }
        if (moving) for (r in radars.near(state.position, DRIVEN_BY_M)) drivenBy[r.id] = now
        sectionsHandled.entries.removeAll { now - it.value > 30 * 60_000 }

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
                val onPath = horizon.pathDistance(snaps)?.takeUnless { turnedRound(state.position, heading, r, it) }
                if (onPath != null && (upcoming == null || onPath.first < upcoming.distanceM)) {
                    upcoming = UpcomingRadar(r, onPath.first, radarLimit(r, roadLimit), onPath.second.segment.name)
                }
                // Only when the measured direction is known: a section the other way must not show.
                val oneWay = r.directionDeg != null || snaps.singleOrNull()?.segment?.oneway == true
                if (onPath != null && r.type == RadarType.SECTION_END && r.sectionLengthM != null && oneWay) {
                    sectionWithoutStart(r, onPath.first, horizon.path, now, roadLimit, events)?.let(queue::addLast)
                }
                val byRoad = horizon.roadDistance(snaps) ?: continue
                if (turnedRound(state.position, heading, r, byRoad)) continue
                d = byRoad.first
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
                // A copy of the camera just passed, a little further (sources disagree on its spot):
                // it is behind the car in truth, never "radar dans 150 mètres" right after it.
                if (justPassed.values.any { sameCamera(it.first, r) }) {
                    recentlyPassed[r.id] = now
                    continue
                }
                val twin = tracked.values.any { t -> !t.silent && sameCamera(t.radar, r) }
                tracked[r.id] = Track(r, Geo.distanceM(state.position, r.position), speedKmh / 3.6, silent = twin)
                val hesitated = dropped[r.id]?.let { x ->
                    val driven = odometerM - x.odometerM
                    if (x.roadM == null) driven < DROPPED_NEAR_M else d <= x.roadM - driven + DROPPED_NEAR_M
                } == true
                if (!twin && !hesitated) events += AlertEvent.Approach(now, r, d, radarLimit(r, roadLimit))
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
                t.speedAtMinMps = speedKmh / 3.6
            }
            val snaps = horizon?.snaps(r).orEmpty()
            // Reachable only by turning round (loop, roundabout behind it): it is behind, not ahead.
            val roadD = if (horizon != null && snaps.isNotEmpty()) {
                horizon.roadDistance(snaps)?.takeUnless { heading != null && turnedRound(state.position, heading, r, it) }?.first
            } else {
                null
            }
            val ahead = if (horizon != null && snaps.isNotEmpty()) {
                roadD != null
            } else {
                heading != null && isAhead(state.position, heading, r.position, straight)
            }
            if (roadD != null) {
                t.lastRoadM = roadD
                t.speedAtLastRoadMps = v
                if (t.minRoadM.let { it == null || roadD < it }) {
                    t.minRoadM = roadD
                    t.odometerAtMinRoadM = odometerM
                }
            }
            // Gone by: the road distance was nearly used up when it vanished (two fixes at most), or
            // without road, the radar is now behind. Otherwise the car turned off before it.
            val reached = t.lastRoadM?.let { it < maxOf(REACHED_M, 2.5 * t.speedAtLastRoadMps) }
                ?: (heading == null || behind(state.position, heading, r.position))
            val leaving = (t.minDistanceM < 120 && straight > t.minDistanceM + 25) || (!ahead && straight < 60 && t.minDistanceM < 60)
            if (leaving && reached && !ahead) {
                iterator.remove()
                recentlyPassed[r.id] = now
                justPassed[r.id] = r to now
                onPassed(t, now, roadLimit, events)
                continue
            }
            if (!ahead && (straight > 150 || leaving)) {
                // Turned off before reaching it, unless the car drove right by it (a red light at the
                // crossroads, whose road distance vanished a little early): then it is behind, done.
                iterator.remove()
                if (t.minDistanceM < PASSED_NEAR_M) recentlyPassed[r.id] = now else dropped[r.id] = drop(t, now)
                continue
            }
            // Further and further by road: the car goes another way (it came by a side road, or
            // turned off where the radar stays reachable). Let it go; met again, it is announced anew.
            val minRoad = t.minRoadM
            if (roadD != null && minRoad != null && roadD > minRoad + GROWING_M && roadD > triggerM) {
                iterator.remove()
                dropped[r.id] = drop(t, now)
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

        val nextLimit = horizon?.let { RoadHorizon.nextLimit(it.path, settings.limitAheadM) }
        // The sign of a higher limit is being passed (the road match lags a little behind the car).
        val rising = nextLimit != null && limit != null && effectiveLimit(nextLimit.limitKmh) > limit &&
            nextLimit.distanceM <= maxOf(settings.limitAnticipateM, 2 * v)
        val source = road?.segment?.limitSource
        val spoken = (source == LimitSource.EXPLICIT || source == LimitSource.IMPLICIT) && !rising &&
            (road?.distanceM ?: Double.MAX_VALUE) <= settings.spokenMatchM && now - limitSinceMs >= settings.overspeedGraceMs
        overspeed(now, speedKmh, limit, spoken, events)

        lastPosition = state.position
        val active = tracked.values.filter { motion != Motion.PARKED }.map { t ->
            val rl = radarLimit(t.radar, roadLimit)
            ActiveAlert(
                radar = t.radar,
                distanceM = distances[t.radar.id] ?: Geo.distanceM(state.position, t.radar.position),
                limitKmh = rl,
                overLimit = rl != null && speedKmh >= warnAt(rl),
                ageMs = t.radar.reportedAtMs?.let { now - it },
            )
        }.sortedBy { it.distanceM }
        val limitAhead = nextLimit?.let { it.copy(limitKmh = effectiveLimit(it.limitKmh)) }
            ?.takeIf { it.limitKmh != limit }
        val ahead = horizon?.let { RoadHorizon.polyline(it.path, settings.aheadDrawnM) }.orEmpty()
        return AlertOutput(events, active, sectionStatus(), upcoming, limitAhead, ahead)
    }

    private fun drop(t: Track, now: Long) =
        t.minRoadM?.let { Dropped(now, t.odometerAtMinRoadM, it) } ?: Dropped(now, odometerM, null)

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

    /** Speed from which the driver is too fast for [limit]: the radar's flash speed, or 3 km/h over when early. */
    private fun warnAt(limit: Int): Int =
        if (earlySpeeding) limit + settings.earlyToleranceKmh + 1 else SpeedRules.flashThresholdKmh(limit)

    /**
     * Silent "over" state, spoken warning from [warnAt] on, not repeated while hovering around it.
     * Not [spoken] (limit guessed, unsure road, sign just passed): never past the silent state, so
     * it is said once the limit is sure, if the car is still too fast then.
     */
    private fun overspeed(now: Long, speedKmh: Double, limit: Int?, spoken: Boolean, events: MutableList<AlertEvent>) {
        val tooFast = limit != null && speedKmh >= warnAt(limit)
        overFixes = if (tooFast) overFixes + 1 else 0
        val level = when {
            limit == null -> OverspeedLevel.NONE
            tooFast -> if (spoken && overFixes >= settings.overspeedFixes) OverspeedLevel.WARN else OverspeedLevel.OVER
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

    /**
     * [r] is reached at [byRoad] only by turning round: its road there runs against the car's
     * heading and the road to it is a long detour compared with the straight line (a level
     * crossing's camera for the other direction, joined through the roundabout just after), or it
     * is behind the car after the car drove right by it. A radar merely behind in a straight line
     * stays: a winding road puts radars ahead by road behind the car's heading.
     */
    private fun turnedRound(p: LatLon, heading: Double, r: Radar, byRoad: Pair<Double, RadarOnRoad>): Boolean {
        val straight = Geo.distanceM(p, r.position)
        val against = Geo.headingDiffDeg(heading, byRoad.second.bearingDeg) > AGAINST_DEG
        val detour = byRoad.first > maxOf(DETOUR_FACTOR * straight, straight + DETOUR_SLACK_M)
        return (against && detour) || (r.id in drivenBy && behind(p, heading, r.position))
    }

    /**
     * [b] is [a] seen by another source: same kind of camera (fixed and discriminant alike, as the
     * data merge counts them), close, limits and measured directions that do not disagree. Two
     * measured directions that differ: two cameras (level crossing, crossroads), not one.
     */
    private fun sameCamera(a: Radar, b: Radar): Boolean =
        family(a.type) == family(b.type) && Geo.distanceM(a.position, b.position) <= settings.twinM &&
            (a.limitKmh == null || b.limitKmh == null || a.limitKmh == b.limitKmh) &&
            (a.directionDeg == null || b.directionDeg == null || Geo.headingDiffDeg(a.directionDeg, b.directionDeg) <= TWIN_DIRECTION_DEG)

    private fun family(t: RadarType) = if (t == RadarType.DISCRIMINANT) RadarType.FIXED else t

    private fun behind(p: LatLon, heading: Double, target: LatLon): Boolean =
        Geo.distanceM(p, target) > 15 && Geo.headingDiffDeg(heading, Geo.bearingDeg(p, target)) > 90

    private fun isAhead(p: LatLon, heading: Double, target: LatLon, d: Double): Boolean {
        if (d < 15) return true
        val angle = Geo.headingDiffDeg(heading, Geo.bearingDeg(p, target))
        // Near the radar, accept a wider angle as long as it stays close to the road axis.
        return angle <= settings.aheadConeDeg || (angle < 90 && d * sin(rad(angle)) < 25)
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

        /** A road running more than this against the car's heading is the way back. */
        const val AGAINST_DEG = 120.0

        /** Reached by road beyond this many times the straight line (and this many meters more): a detour. */
        const val DETOUR_FACTOR = 2.5
        const val DETOUR_SLACK_M = 150.0

        /** A track given up and found again within this time and distance is not announced again. */
        const val DROPPED_MS = 120_000L
        const val DROPPED_NEAR_M = 150.0

        /** A track left after the car came this close to it, in a straight line, was driven by. */
        const val PASSED_NEAR_M = 40.0

        /** A tracked radar this much further by road than it has been is not on the way any more. */
        const val GROWING_M = 200.0

        /** A radar this close to the car was driven by. */
        const val DRIVEN_BY_M = 40.0

        /** A radar met this soon after passing a copy of it is that same camera. */
        const val JUST_PASSED_MS = 90_000L

        /** Same camera seen by two sources: their measured directions agree within this. */
        const val TWIN_DIRECTION_DEG = 25.0
    }
}
