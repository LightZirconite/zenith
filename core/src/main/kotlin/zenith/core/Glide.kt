package zenith.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Where the car arrow is drawn at one instant. */
data class GlidePose(val position: LatLon, val headingDeg: Double?)

/**
 * Smooth car arrow between two GPS fixes (they come about once a second): from the last fix the
 * car moves on at its speed along the road it follows ([DriveSnapshot.ahead]), else straight
 * along its heading; at the next fix, the gap between what was drawn and the new prediction fades
 * out over [BLEND_MS] instead of jumping. Never predicts more than [MAX_AHEAD_MS] past a fix (fix
 * lost, tunnel: the session's own coasting takes over), nor while stopped.
 */
class Glide {
    private var base: GlidePose? = null
    private var baseAtMs = 0L
    private var speedMps = 0.0
    private var path: List<LatLon> = emptyList()
    /** Drawn minus predicted at the last fix, fading out. */
    private var offLat = 0.0
    private var offLon = 0.0
    private var offHeading = 0.0
    private var offAtMs = 0L

    /** A new fix (its snapshot), received at [nowMs] on the screen's clock. */
    fun fix(s: DriveSnapshot, nowMs: Long) = fix(s.position, s.speedKmh, s.headingDeg, s.ahead, nowMs)

    /** Same as the snapshot's: [ahead] the road followed from the car onwards. */
    fun fix(position: LatLon, speedKmh: Int?, headingDeg: Double?, ahead: List<LatLon>, nowMs: Long) {
        val drawn = base?.let { at(nowMs) }
        base = GlidePose(position, headingDeg)
        baseAtMs = nowMs
        speedMps = (speedKmh ?: 0) / 3.6
        path = ahead
        offLat = 0.0
        offLon = 0.0
        offHeading = 0.0
        if (drawn == null) return
        val gap = Geo.distanceM(drawn.position, position)
        // A real jump (new road, first fix after a tunnel): straight there.
        if (gap > SNAP_M) return
        offLat = drawn.position.lat - position.lat
        offLon = drawn.position.lon - position.lon
        val h0 = drawn.headingDeg
        val h1 = headingDeg
        if (h0 != null && h1 != null) offHeading = signedDiff(h0, h1)
        offAtMs = nowMs
    }

    /** The arrow at [nowMs]; null before the first fix. */
    fun at(nowMs: Long): GlidePose? {
        val b = base ?: return null
        val dt = (nowMs - baseAtMs).coerceIn(0L, MAX_AHEAD_MS) / 1000.0
        val moving = speedMps >= MIN_SPEED_MPS
        val ahead = if (moving) advance(b, speedMps * dt) else b
        val k = blend(nowMs)
        if (k <= 0.0) return ahead
        val heading = ahead.headingDeg?.let { Geo.normalizeDeg(it + offHeading * k) }
        return GlidePose(LatLon(ahead.position.lat + offLat * k, ahead.position.lon + offLon * k), heading)
    }

    /** Share of the last correction still shown: 1 at the fix, 0 after [BLEND_MS] (eased). */
    private fun blend(nowMs: Long): Double {
        if (offLat == 0.0 && offLon == 0.0 && offHeading == 0.0) return 0.0
        val t = ((nowMs - offAtMs).toDouble() / BLEND_MS).coerceIn(0.0, 1.0)
        return 1 - t * t * (3 - 2 * t)
    }

    /** [b] moved [d] meters along the followed road, or straight along its heading without one. */
    private fun advance(b: GlidePose, d: Double): GlidePose {
        if (d <= 0) return b
        if (path.size >= 2) {
            var left = d
            for (i in 0 until path.size - 1) {
                val a = path[i]
                val c = path[i + 1]
                val len = Geo.distanceM(a, c)
                if (len <= 0.01) continue
                if (left <= len) {
                    val f = left / len
                    return GlidePose(LatLon(a.lat + (c.lat - a.lat) * f, a.lon + (c.lon - a.lon) * f), Geo.bearingDeg(a, c))
                }
                left -= len
            }
            val a = path[path.size - 2]
            return GlidePose(path.last(), Geo.bearingDeg(a, path.last()))
        }
        val h = b.headingDeg ?: return b
        return GlidePose(offset(b.position, h, d), h)
    }

    private fun offset(p: LatLon, headingDeg: Double, d: Double): LatLon {
        val r = headingDeg * PI / 180
        val dLat = d * cos(r) / M_PER_DEG
        val dLon = d * sin(r) / (M_PER_DEG * cos(p.lat * PI / 180).coerceAtLeast(0.01))
        return LatLon(p.lat + dLat, p.lon + dLon)
    }

    /** a − b in (−180, 180]. */
    private fun signedDiff(a: Double, b: Double): Double {
        var d = Geo.normalizeDeg(a) - Geo.normalizeDeg(b)
        if (d > 180) d -= 360
        if (d <= -180) d += 360
        return if (abs(d) > 120) 0.0 else d
    }

    companion object {
        const val MAX_AHEAD_MS = 1_800L
        const val BLEND_MS = 600L
        const val SNAP_M = 60.0
        /** Below about 5 km/h the GPS speed is noise: the arrow stays. */
        const val MIN_SPEED_MPS = 1.4
        private const val M_PER_DEG = 111_320.0
    }
}
