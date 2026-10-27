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

data class AlertOutput(val events: List<AlertEvent>, val active: List<ActiveAlert>, val section: SectionStatus?)

/**
 * Turns the filtered vehicle state into radar, section and overspeed alerts.
 * Radars are only considered when they are ahead, measure the travel direction and are within
 * the speed-dependent alert distance; each one goes approach → (brake) → passed exactly once.
 */
class AlertEngine(
    private val radars: RadarIndex,
    private val settings: AlertSettings = AlertSettings(),
    /** Turns a nominal limit into the one that applies to the driver (rain, probationary licence). */
    private val effectiveLimit: (Int) -> Int = { it },
) {
    private class Track(val radar: Radar, var minDistanceM: Double, var speedAtMinMps: Double, var brakeAnnounced: Boolean = false)

    private val tracked = LinkedHashMap<String, Track>()
    private val recentlyPassed = HashMap<String, Long>()
    private var section: SectionTracker? = null
    private var sectionLimit = 0
    private var lastPosition: LatLon? = null
    private var overspeed = OverspeedLevel.NONE
    private var lastLimit: Int? = null

    fun step(state: KinematicState, roadLimitKmh: Int?): AlertOutput {
        val events = mutableListOf<AlertEvent>()
        val now = state.timeMs
        val v = state.speedMps
        val speedKmh = v * 3.6
        val limit = roadLimitKmh?.let(effectiveLimit)

        if (limit != lastLimit) {
            if (lastPosition != null) events += AlertEvent.LimitChanged(now, lastLimit, limit)
            lastLimit = limit
        }

        advanceSection(state, events)
        recentlyPassed.entries.removeIf { now - it.value > 5 * 60_000 }

        val heading = state.headingDeg
        val triggerM = (v * settings.lookaheadS + v * v / (2 * settings.comfortDecelMps2))
            .coerceIn(settings.minAlertM, settings.maxAlertM)

        for (r in radars.near(state.position, triggerM + 200)) {
            if (r.type.mobile && r.reportedAtMs != null && now - r.reportedAtMs > settings.mobileMaxAgeMs) continue
            val d = Geo.distanceM(state.position, r.position)
            val track = tracked[r.id]
            val ahead = heading != null && isAhead(state.position, heading, r.position, d)
            if (track == null) {
                if (heading == null || r.id in recentlyPassed || d > triggerM || !ahead) continue
                if (r.directionDeg != null && Geo.headingDiffDeg(heading, r.directionDeg) > settings.directionToleranceDeg) continue
                tracked[r.id] = Track(r, d, v)
                events += AlertEvent.Approach(now, r, d, radarLimit(r, limit))
            }
        }

        val iterator = tracked.values.iterator()
        while (iterator.hasNext()) {
            val t = iterator.next()
            val r = t.radar
            val d = Geo.distanceM(state.position, r.position)
            if (d < t.minDistanceM) {
                t.minDistanceM = d
                t.speedAtMinMps = v
            }
            val ahead = heading != null && isAhead(state.position, heading, r.position, d)
            val passed = (t.minDistanceM < 120 && d > t.minDistanceM + 25) || (!ahead && d < 60 && t.minDistanceM < 60)
            if (passed) {
                iterator.remove()
                recentlyPassed[r.id] = now
                onPassed(t, now, limit, events)
                continue
            }
            if (!ahead && d > 150) {
                // Turned off before reaching it.
                iterator.remove()
                continue
            }
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

        if (limit != null) {
            val level = when {
                speedKmh > limit + settings.overspeedToleranceKmh -> OverspeedLevel.WARN
                speedKmh >= limit + 1 -> OverspeedLevel.OVER
                else -> OverspeedLevel.NONE
            }
            if (level > overspeed) events += AlertEvent.Overspeed(now, level, speedKmh.roundToInt(), limit)
            overspeed = level
        } else {
            overspeed = OverspeedLevel.NONE
        }

        lastPosition = state.position
        val active = tracked.values.map { t ->
            val rl = radarLimit(t.radar, limit)
            ActiveAlert(
                radar = t.radar,
                distanceM = Geo.distanceM(state.position, t.radar.position),
                limitKmh = rl,
                overLimit = rl != null && speedKmh >= rl + 1,
                ageMs = t.radar.reportedAtMs?.let { now - it },
            )
        }.sortedBy { it.distanceM }
        return AlertOutput(events, active, sectionStatus())
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
