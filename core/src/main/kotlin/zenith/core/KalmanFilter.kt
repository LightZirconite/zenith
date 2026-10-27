package zenith.core

import kotlin.math.max

/** One raw GPS fix. Speed in m/s, bearing in degrees, accuracy as 1-sigma meters. */
data class GpsFix(
    val timeMs: Long,
    val position: LatLon,
    val accuracyM: Double? = null,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
)

/** Filtered kinematic state. */
data class KinematicState(val timeMs: Long, val position: LatLon, val speedMps: Double, val headingDeg: Double?)

/**
 * Constant-velocity Kalman filter on a local plane: state (x, y, vx, vy).
 * Smooths GPS noise and rejects isolated jumps by gating the position innovation.
 */
class KalmanFilter(
    private val accelSigma: Double = 2.0,
    private val defaultAccuracyM: Double = 8.0,
    private val gate: Double = 16.0, // chi-square, 2 dof, ~99.97 %
    private val maxConsecutiveRejects: Int = 3,
) {
    private var frame: LocalFrame? = null
    private var x = DoubleArray(4)
    private var p = Array(4) { DoubleArray(4) }
    private var lastTimeMs = 0L
    private var rejects = 0
    private var lastHeading: Double? = null

    /** Feeds a fix; returns the filtered state, or null if the fix was rejected as an outlier. */
    fun update(fix: GpsFix): KinematicState? {
        val f = frame
        if (f == null || fix.timeMs < lastTimeMs) {
            reset(fix)
            return state()
        }
        if (Geo.distanceM(f.origin, fix.position) > 10_000) recenter(f)
        val dt = (fix.timeMs - lastTimeMs) / 1000.0
        if (dt > 30) {
            reset(fix)
            return state()
        }
        predict(dt)

        val z = frame!!.toXY(fix.position)
        val r = (fix.accuracyM ?: defaultAccuracyM).coerceAtLeast(2.0).let { it * it }
        // Position innovation and its covariance S = P_pos + R.
        val iy0 = z.x - x[0]
        val iy1 = z.y - x[1]
        val s00 = p[0][0] + r
        val s01 = p[0][1]
        val s11 = p[1][1] + r
        val det = s00 * s11 - s01 * s01
        val d2 = (iy0 * (s11 * iy0 - s01 * iy1) + iy1 * (s00 * iy1 - s01 * iy0)) / det
        if (d2 > gate && rejects < maxConsecutiveRejects) {
            rejects++
            lastTimeMs = fix.timeMs
            return null
        }
        rejects = 0

        val hasVelocity = fix.speedMps != null && fix.bearingDeg != null && fix.speedMps > 0.5
        if (hasVelocity) {
            val v = Vec2.fromHeading(fix.bearingDeg) * fix.speedMps
            val rv = 1.0 // (1 m/s)^2
            correct(doubleArrayOf(z.x, z.y, v.x, v.y), doubleArrayOf(r, r, rv, rv), intArrayOf(0, 1, 2, 3))
        } else {
            correct(doubleArrayOf(z.x, z.y), doubleArrayOf(r, r), intArrayOf(0, 1))
        }
        lastTimeMs = fix.timeMs
        return state()
    }

    private fun reset(fix: GpsFix) {
        frame = LocalFrame(fix.position)
        val v = if (fix.speedMps != null && fix.bearingDeg != null) Vec2.fromHeading(fix.bearingDeg) * fix.speedMps else Vec2(0.0, 0.0)
        x = doubleArrayOf(0.0, 0.0, v.x, v.y)
        val r = (fix.accuracyM ?: defaultAccuracyM).let { it * it }
        val vVar = if (fix.speedMps != null) 4.0 else 400.0
        p = Array(4) { i -> DoubleArray(4) { j -> if (i == j) (if (i < 2) r else vVar) else 0.0 } }
        lastTimeMs = fix.timeMs
        rejects = 0
        lastHeading = fix.bearingDeg?.takeIf { (fix.speedMps ?: 0.0) > 1.5 }
    }

    /** Moves the plane origin to the current estimate so the projection stays accurate on long drives. */
    private fun recenter(f: LocalFrame) {
        frame = LocalFrame(f.toLatLon(Vec2(x[0], x[1])))
        x[0] = 0.0
        x[1] = 0.0
    }

    private fun predict(dt: Double) {
        x[0] += x[2] * dt
        x[1] += x[3] * dt
        // P = F P F^T + Q, F = [[1,0,dt,0],[0,1,0,dt],[0,0,1,0],[0,0,0,1]].
        val fp = Array(4) { i -> DoubleArray(4) { j -> p[i][j] + if (i < 2) dt * p[i + 2][j] else 0.0 } }
        val fpf = Array(4) { i -> DoubleArray(4) { j -> fp[i][j] + if (j < 2) dt * fp[i][j + 2] else 0.0 } }
        val q = accelSigma * accelSigma
        val dt2 = dt * dt
        for (k in 0..1) {
            fpf[k][k] += q * dt2 * dt2 / 4
            fpf[k][k + 2] += q * dt2 * dt / 2
            fpf[k + 2][k] += q * dt2 * dt / 2
            fpf[k + 2][k + 2] += q * dt2
        }
        p = fpf
    }

    /** Sequential scalar updates: each measured component is independent with variance r[i]. */
    private fun correct(z: DoubleArray, r: DoubleArray, idx: IntArray) {
        for (m in z.indices) {
            val k = idx[m]
            val s = p[k][k] + r[m]
            val gain = DoubleArray(4) { p[it][k] / s }
            val innov = z[m] - x[k]
            for (i in 0..3) x[i] += gain[i] * innov
            val rowK = p[k].copyOf()
            for (i in 0..3) for (j in 0..3) p[i][j] -= gain[i] * rowK[j]
        }
    }

    private fun state(): KinematicState {
        val f = frame!!
        val v = Vec2(x[2], x[3])
        val speed = v.length()
        if (speed > 1.5) lastHeading = v.headingDeg()
        return KinematicState(lastTimeMs, f.toLatLon(Vec2(x[0], x[1])), max(0.0, speed), lastHeading)
    }
}
