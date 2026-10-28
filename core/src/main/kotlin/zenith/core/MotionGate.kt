package zenith.core

/** [PARKED]: not driving at all (phone at home, car parked); [STANDING]: driving, stopped for now (light, jam). */
enum class Motion { PARKED, STANDING, MOVING }

/**
 * Tells whether the car is really driving, so a phone lying at home next to a road never announces
 * the radar down the street. Rolling starts once the filtered position has truly moved (net
 * displacement and average speed over a few seconds, accurate fix, Doppler agreeing): GPS noise
 * wanders around a point, it does not travel. Rolling ends after a long standstill, so a red light
 * or a jam keeps the alerts while a parked car drops them.
 */
class MotionGate(
    private val startMps: Double = 2.5,
    private val startWindowMs: Long = 8_000,
    private val minStartAgeMs: Long = 2_000,
    private val minStartM: Double = 20.0,
    /** Consecutive fixes that must look like driving: one noisy jump is not a start. */
    private val startFixes: Int = 3,
    private val maxAccuracyM: Double = 30.0,
    private val stopMs: Long = 60_000,
    private val stopRadiusM: Double = 20.0,
) {
    private val samples = ArrayDeque<Pair<Long, LatLon>>()
    private var startStreak = 0

    var rolling = false
        private set

    /** One filtered state with the raw fix it comes from. */
    fun update(state: KinematicState, fix: GpsFix): Motion {
        val now = state.timeMs
        if (samples.isNotEmpty() && now < samples.last().first) samples.clear()
        samples.addLast(now to state.position)
        while (now - samples.first().first > stopMs) samples.removeFirst()
        if (rolling) {
            rolling = !stopped(now, state.position)
        } else {
            startStreak = if (started(now, state, fix)) startStreak + 1 else 0
            rolling = startStreak >= startFixes
        }
        if (rolling) startStreak = 0
        // Under this speed the heading is not updated (see [KalmanFilter]) and the road match is noise.
        val moving = state.speedMps >= MOVING_MPS && (fix.speedMps == null || fix.speedMps >= MOVING_MPS)
        return when {
            !rolling -> Motion.PARKED
            moving -> Motion.MOVING
            else -> Motion.STANDING
        }
    }

    private fun started(now: Long, state: KinematicState, fix: GpsFix): Boolean {
        if (state.speedMps < startMps) return false
        if (fix.speedMps != null && fix.speedMps < startMps) return false
        if ((fix.accuracyM ?: 0.0) > maxAccuracyM) return false
        val (t, p) = samples.firstOrNull { now - it.first <= startWindowMs } ?: return false
        val age = now - t
        if (age < minStartAgeMs) return false
        val moved = Geo.distanceM(p, state.position)
        // Noise grows with the announced accuracy: so must the distance that proves the car moved.
        return moved >= maxOf(minStartM, 2 * (fix.accuracyM ?: 0.0)) && moved / (age / 1000.0) >= startMps
    }

    private fun stopped(now: Long, position: LatLon): Boolean =
        now - samples.first().first >= stopMs - 1_000 && samples.all { Geo.distanceM(it.second, position) < stopRadiusM }

    private companion object {
        const val MOVING_MPS = 1.5
    }
}
