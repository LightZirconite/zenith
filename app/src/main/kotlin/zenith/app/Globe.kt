package zenith.app

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

private val OceanLit = Color(0xFF1C5DA8)
private val OceanMid = Color(0xFF0B2B55)
private val OceanDark = Color(0xFF020814)
private val Atmosphere = Color(0xFF4DA8FF)
private val Land = Color(0xFFD2EAFF)
private val CityLight = Color(0xFFFFB35C)
private val Locked = Color(0xFF30D158)
private val Seeking = Color(0xFFE6F0FF)
private val LinkBlue = Color(0xFF3D8BFF)
private val LinkPale = Color(0xFFCFE3FF)
private val Panel = Color(0xFF1B4E8F)
private val PanelEdge = Color(0xFF9CC2F0)

/** A link draws itself from its satellite down to the position in this long (seconds). */
private const val LINK_DRAW_S = 0.45f

/** France until the first position: the globe turns home to the viewer as the satellites lock, and the beacon lights up there. */
private const val HOME_LAT = 46.5
private const val HOME_LON = 2.5
private const val TAU = 6.2831855f

/** Turn of the globe while the GPS searches, in radians per second. */
private const val SPIN = 0.32f

/** Sun from the upper left and a little ahead: the day side faces the viewer, the night side lies low right. */
private const val LX = -0.55f
private const val LY = 0.55f
private const val LZ = 0.63f

/**
 * The Earth at the heart of the tunnel, drawn frame by frame: land as a field of dots on a dark
 * ocean lit from the upper left, a halo of atmosphere, city lights on the night side, and the
 * satellites in view travelling three tilted orbits (no lines drawn), each a small satellite. As they
 * lock, the globe slows and turns home (the driver's position once known, France before) to the
 * viewer; a beacon lights up there and every locked satellite glows blue, its link curving down to it.
 * Aimed, it settles on home like a camera, by the shortest way: the dive lands on the driver.
 */
internal class Globe {
    private val px: FloatArray
    private val py: FloatArray
    private val pz: FloatArray

    /** Projected dots by shade (bright day, dim day, edge or night, city light), two floats per dot. */
    private val shades: Array<FloatArray>
    private val shadeCount = IntArray(4)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private var home = unit(HOME_LAT, HOME_LON)
    private var homeSpin = (-Math.toRadians(HOME_LON)).toFloat()
    private var homeTilt = Math.toRadians(HOME_LAT).toFloat()

    /** France starts a quarter turn away, to the left: it comes into view as the search goes on. */
    private var spin = homeSpin - 1.7f
    private var tilt = 0.35f
    private var spinSpeed = SPIN
    private var tiltSpeed = 0f

    /** When each satellite locked (the time [draw] got), -1 while it has not: its link draws from then. */
    private val lockedAt = FloatArray(12) { -1f }

    init {
        val x = ArrayList<Float>()
        val y = ArrayList<Float>()
        val z = ArrayList<Float>()
        var bit = 0
        for (row in 0 until ROWS) {
            val lat = LAT0 + STEP * row
            val n = floor(360.0 * cos(Math.toRadians(lat)) / STEP).toInt()
            for (c in 0 until n) {
                if (land(bit++)) {
                    val p = unit(lat, -180.0 + (c + 0.5) * 360.0 / n)
                    x += p[0]
                    y += p[1]
                    z += p[2]
                }
            }
        }
        px = x.toFloatArray()
        py = y.toFloatArray()
        pz = z.toFloatArray()
        shades = Array(4) { FloatArray(px.size * 2) }
    }

    /** Home is where the driver is: the globe turns there from now on. */
    fun aimAt(latDeg: Double, lonDeg: Double) {
        home = unit(latDeg, lonDeg)
        homeSpin = (-Math.toRadians(lonDeg)).toFloat()
        homeTilt = Math.toRadians(latDeg).toFloat()
    }

    /** Home straight in front at once (the way back out of it, at the end of a drive). */
    fun face() {
        spin = homeSpin
        tilt = homeTilt
        spinSpeed = 0f
        tiltSpeed = 0f
    }

    /**
     * Spins while searching, slower with each satellite ([lock], 0 to 1). [aim]: home comes to the
     * center like a camera settling, from the speed the globe already has and always by the shortest
     * way (never a whole turn when home moves a little behind); [firm] through the jump, where it must
     * be dead center before the Earth grows large.
     */
    fun step(dt: Float, lock: Float, aim: Boolean, firm: Boolean = false) {
        if (aim) {
            val w = if (firm) 10f else 3f
            spinSpeed += (w * w * wrap(homeSpin - spin) - 2 * w * spinSpeed) * dt
            tiltSpeed += (w * w * (homeTilt - tilt) - 2 * w * tiltSpeed) * dt
        } else {
            val k = lock.coerceIn(0f, 1f)
            spinSpeed += (SPIN * (1f - 0.7f * k) - spinSpeed) * (2f * dt).coerceAtMost(1f)
            val target = 0.35f + (homeTilt - 0.35f) * k * k * (3 - 2 * k)
            tiltSpeed += (9f * (target - tilt) - 6f * tiltSpeed) * dt
        }
        spin = wrap(spin + spinSpeed * dt)
        tilt += tiltSpeed * dt
    }

    /**
     * Draws the globe of radius [r] at ([cx], [cy]). [orbits] fades the satellites alone (they leave
     * first at the jump); [used] of the [visible] satellites are locked, [lock] is 0 to 1. [flare]
     * (0 to 1): the lock-on, a ring of light running out of the beacon.
     */
    fun draw(
        scope: DrawScope,
        cx: Float,
        cy: Float,
        r: Float,
        alpha: Float,
        orbits: Float,
        t: Float,
        visible: Int,
        used: Int,
        lock: Float,
        flare: Float = 0f,
    ): Unit = with(scope) {
        if (alpha <= 0.01f || r < 1f) return@with
        val center = Offset(cx, cy)
        val beacon = rotated(home).takeIf { it[2] > 0.12f }?.let { Offset(cx + it[0] * r, cy - it[1] * r) }
        if (orbits > 0.01f) satellites(back = true, cx, cy, r, alpha * orbits, t, visible, used, null)
        // Atmosphere: a blue halo just outside the edge.
        drawCircle(
            Brush.radialGradient(
                0f to Atmosphere.copy(alpha = 0.35f * alpha),
                0.64f to Atmosphere.copy(alpha = 0.35f * alpha),
                0.69f to Atmosphere.copy(alpha = 0.22f * alpha),
                0.8f to Atmosphere.copy(alpha = 0.06f * alpha),
                1f to Color.Transparent,
                center = center,
                radius = r * 1.5f,
            ),
            radius = r * 1.5f,
            center = center,
        )
        drawCircle(
            Brush.radialGradient(0f to OceanLit, 0.5f to OceanMid, 1f to OceanDark, center = Offset(cx - 0.45f * r, cy - 0.5f * r), radius = 1.9f * r),
            radius = r,
            center = center,
            alpha = alpha,
        )
        project(cx, cy, r)
        drawIntoCanvas { canvas ->
            // Dots grow as the globe nears, up to a point: the dive reads as halftone rushing past.
            val dot = (r * 0.019f).coerceIn(1.2f, 7f * density)
            for (s in 0 until 4) {
                if (shadeCount[s] == 0) continue
                paint.strokeWidth = if (s == 3) dot * 0.8f else dot
                val color = when (s) {
                    0 -> Land.copy(alpha = 0.95f * alpha)
                    1 -> Land.copy(alpha = 0.6f * alpha)
                    2 -> Land.copy(alpha = 0.22f * alpha)
                    else -> CityLight.copy(alpha = 0.75f * alpha)
                }
                paint.color = color.toArgb()
                canvas.nativeCanvas.drawPoints(shades[s], 0, shadeCount[s] * 2, paint)
            }
        }
        beacon?.let { b ->
            val color = if (lock >= 0.999f) Locked else Seeking
            val strength = alpha * (0.3f + 0.7f * lock)
            for (w in 0 until 3) {
                val f = (t * 0.55f + w / 3f) % 1f
                drawCircle(color.copy(alpha = 0.55f * strength * (1f - f)), radius = r * (0.03f + 0.3f * f), center = b, style = Stroke(1.2f * density))
            }
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.7f * strength), Color.Transparent), center = b, radius = 12 * density), radius = 12 * density, center = b)
            drawCircle(Color.White.copy(alpha = strength), radius = 2.6f * density, center = b)
            if (flare > 0f && flare < 1f) {
                val k = 1f - (1f - flare) * (1f - flare)
                drawCircle(color.copy(alpha = alpha * (1f - flare)), radius = r * (0.04f + 0.55f * k), center = b, style = Stroke((3f * (1f - flare) + 0.6f) * density))
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.8f * alpha * (1f - flare)), Color.Transparent), center = b, radius = 34 * density),
                    radius = 34 * density,
                    center = b,
                )
            }
        }
        if (orbits > 0.01f) satellites(back = false, cx, cy, r, alpha * orbits, t, visible, used, beacon)
    }

    /** Projects the land dots facing the viewer into their shade buckets. */
    private fun project(cx: Float, cy: Float, r: Float) {
        shadeCount.fill(0)
        val cs = cos(spin)
        val sn = sin(spin)
        val ct = cos(tilt)
        val st = sin(tilt)
        for (i in px.indices) {
            val x1 = px[i] * cs + pz[i] * sn
            val z1 = -px[i] * sn + pz[i] * cs
            val y2 = py[i] * ct - z1 * st
            val z2 = py[i] * st + z1 * ct
            if (z2 <= 0f) continue
            val light = x1 * LX + y2 * LY + z2 * LZ
            val shade = when {
                // A few lights on the night side; the rest of it stays dark.
                light < -0.1f && i % 3 == 0 -> 3
                light < 0f -> 2
                else -> {
                    val b = (0.3f + 0.7f * light) * (0.4f + 0.6f * z2)
                    if (b > 0.5f) 0 else if (b > 0.28f) 1 else 2
                }
            }
            val k = shadeCount[shade]++ * 2
            shades[shade][k] = cx + x1 * r
            shades[shade][k + 1] = cy - y2 * r
        }
    }

    /**
     * The satellites behind the globe ([back]) or in front of it. No orbit lines: each one travels its
     * orbit as a small satellite; once locked it glows blue and a link curves down to the position,
     * drawing itself from the satellite in [LINK_DRAW_S].
     */
    private fun DrawScope.satellites(back: Boolean, cx: Float, cy: Float, r: Float, alpha: Float, t: Float, visible: Int, used: Int, beacon: Offset?) {
        for (i in 0 until 12) {
            if (i >= used) lockedAt[i] = -1f else if (lockedAt[i] < 0f) lockedAt[i] = t
        }
        for (i in 0 until visible.coerceAtMost(12)) {
            val k = i % 3
            val u = i * 2.3999632f + t * ORBIT_SPEED[k]
            val front = sin(u) > 0f
            if (front == back) continue
            val p = orbitPoint(k, u, cx, cy, r)
            if (!front && hypot(p.x - cx, p.y - cy) < r) continue
            val locked = i < used
            val a = alpha * (if (front) 1f else 0.6f) * (if (locked) 1f else 0.45f)
            if (locked && beacon != null) link(p, beacon, Offset(cx, cy), ((t - lockedAt[i]) / LINK_DRAW_S).coerceIn(0f, 1f), alpha)
            if (locked) {
                drawCircle(
                    Brush.radialGradient(listOf(LinkBlue.copy(alpha = 0.32f * a), Color.Transparent), center = p, radius = 17 * density),
                    radius = 17 * density,
                    center = p,
                )
            }
            // Along the orbit, the panels across it.
            val ahead = orbitPoint(k, u + 0.02f, cx, cy, r)
            val angle = Math.toDegrees(kotlin.math.atan2((ahead.y - p.y).toDouble(), (ahead.x - p.x).toDouble())).toFloat()
            satellite(p, angle, density * (if (front) 1f else 0.75f), a)
        }
    }

    /** A small satellite at [c]: white body, two blue solar panels on a boom, an antenna. */
    private fun DrawScope.satellite(c: Offset, angleDeg: Float, s: Float, a: Float) {
        rotate(angleDeg, c) {
            for (side in listOf(-1f, 1f)) {
                val left = if (side < 0) c.x - 15 * s else c.x + 5 * s
                drawRoundRect(Panel.copy(alpha = a), Offset(left, c.y - 4 * s), Size(10 * s, 8 * s), androidx.compose.ui.geometry.CornerRadius(s))
                drawRoundRect(PanelEdge.copy(alpha = a), Offset(left, c.y - 4 * s), Size(10 * s, 8 * s), androidx.compose.ui.geometry.CornerRadius(s), style = Stroke(s))
                drawLine(PanelEdge.copy(alpha = a), Offset(left + 5 * s, c.y - 4 * s), Offset(left + 5 * s, c.y + 4 * s), strokeWidth = 0.7f * s)
            }
            drawLine(Seeking.copy(alpha = a), Offset(c.x - 5 * s, c.y), Offset(c.x + 5 * s, c.y), strokeWidth = 1.4f * s)
            drawRoundRect(Seeking.copy(alpha = a), Offset(c.x - 3.5f * s, c.y - 5 * s), Size(7 * s, 10 * s), androidx.compose.ui.geometry.CornerRadius(1.6f * s))
            drawLine(Seeking.copy(alpha = a), Offset(c.x, c.y + 5 * s), Offset(c.x, c.y + 8.5f * s), strokeWidth = 1.2f * s, cap = StrokeCap.Round)
        }
    }

    /**
     * The link from a satellite [p] to the position [b]: a curve bowing away from the globe's
     * centre, pale at the position, with a soft blue glow under it; drawn up to [drawn] (0 to 1).
     */
    private fun DrawScope.link(p: Offset, b: Offset, center: Offset, drawn: Float, alpha: Float) {
        if (drawn <= 0f) return
        val mid = Offset((p.x + b.x) / 2, (p.y + b.y) / 2)
        val away = Offset(mid.x - center.x, mid.y - center.y)
        val len = hypot(away.x, away.y).coerceAtLeast(1f)
        val bow = 0.3f * hypot(b.x - p.x, b.y - p.y)
        val ctrl = Offset(mid.x + away.x / len * bow, mid.y + away.y / len * bow)
        val path = androidx.compose.ui.graphics.Path()
        path.moveTo(p.x, p.y)
        val steps = 24
        for (n in 1..(steps * drawn).toInt().coerceAtLeast(1)) {
            val f = n / steps.toFloat()
            val g = 1 - f
            path.lineTo(g * g * p.x + 2 * g * f * ctrl.x + f * f * b.x, g * g * p.y + 2 * g * f * ctrl.y + f * f * b.y)
        }
        drawPath(path, LinkBlue.copy(alpha = 0.2f * alpha), style = Stroke(6 * density, cap = StrokeCap.Round))
        drawPath(
            path,
            Brush.linearGradient(listOf(LinkBlue.copy(alpha = 0.15f * alpha), LinkPale.copy(alpha = alpha)), start = p, end = b),
            style = Stroke(1.8f * density, cap = StrokeCap.Round),
        )
    }

    private fun orbitPoint(k: Int, u: Float, cx: Float, cy: Float, r: Float): Offset {
        val a = ORBIT_RADIUS[k] * r
        val lx = a * cos(u)
        val ly = a * sin(ORBIT_INCLINATION[k]) * sin(u)
        val c = cos(ORBIT_NODE[k])
        val s = sin(ORBIT_NODE[k])
        return Offset(cx + lx * c - ly * s, cy + lx * s + ly * c)
    }

    /** [p] on the unit sphere turned and tilted as the globe is now. */
    private fun rotated(p: FloatArray): FloatArray {
        val x1 = p[0] * cos(spin) + p[2] * sin(spin)
        val z1 = -p[0] * sin(spin) + p[2] * cos(spin)
        return floatArrayOf(x1, p[1] * cos(tilt) - z1 * sin(tilt), p[1] * sin(tilt) + z1 * cos(tilt))
    }

    private companion object {
        /** Rows of dots every 2.5° from 56.25° S to 76.25° N, as many per row as fit evenly around. */
        const val LAT0 = -56.25
        const val STEP = 2.5
        const val ROWS = 54

        /** Three orbits: size (globe radii), inclination toward the viewer and tilt on screen (radians), speed. */
        val ORBIT_RADIUS = floatArrayOf(1.42f, 1.7f, 1.98f)
        val ORBIT_INCLINATION = floatArrayOf(0.3f, 0.5f, 0.2f)
        val ORBIT_NODE = floatArrayOf(-0.35f, 0.3f, 0.12f)
        val ORBIT_SPEED = floatArrayOf(0.55f, 0.4f, 0.3f)

        /** [a] brought between -π and π: the shortest way to turn. */
        fun wrap(a: Float): Float = ((a + PI_F) % TAU + TAU) % TAU - PI_F

        const val PI_F = TAU / 2

        fun unit(latDeg: Double, lonDeg: Double): FloatArray {
            val lat = Math.toRadians(latDeg)
            val lon = Math.toRadians(lonDeg)
            return floatArrayOf((cos(lat) * sin(lon)).toFloat(), sin(lat).toFloat(), (cos(lat) * cos(lon)).toFloat())
        }

        fun land(bit: Int): Boolean {
            val v = ALPHABET.indexOf(LAND[bit / 6])
            return ((v shr (5 - bit % 6)) and 1) == 1
        }

        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        /**
         * Land or sea at each dot, six dots per character (generated once from a global land mask:
         * a dot is land when most of the ground around it is).
         */
        const val LAND = "AAAAAAAAAAAAAAAAAGAAAAAAAAAAAAABgAAAAAAAAAAAAAA4AAAAAAAAAAAAAAAwAAAAAAAAAAgAAAABgAAAAAAAAAAEAAAAAOAAAAAAAAAAQGAAAAAHwAAAAAAAAAAAIAAAAAPwAAAAAAAAABwAAAAAAA/AAAB4AAAAOPwAAAAAAA/gAAA+AAAAD/+AAAAAAAD/AAAB8AAAAH/+AAAAAAAB/gAAA/AAAAB//gAAAAAAAH/AAAD+MAAAD//AAAAAAAAH/wAAD/GAAAA//gAAAAAAAB/+AAA/8YAAAA/8AAAAAAAAH/8AAA/+wAAAA/YAAAAAAAAP/+AAA/+IAAAAHIAAAAAAAAH//gAAH/gAAAAARAAAAAAAAA//+AAA/+AAAABoNAAAAAAAAH//8AAD/4AAABgA9QAAAAAAAP//wAAH/wAAAGKHwAAAAAAAAf/8AAAf/wAAAZ0YAAAAAAAAAf/gAAA//wAABz5AAAAAAAAAAf+AAAB//4AADjgAAAAAAAAAA/wAAPv//wAICBAAAAAAAAAAP+AAA////gBgKBgAAAAAAAABHIAAP///2AMBYcAAAAAAAAB4AAAD///+AHA+CAAAAAAAAHgBAAB///3gHB+EAAAAAAAAdgQAAD///fwfH0AAAAAAAADmIAAAf///+H9+AAAAAAAAHwAAAAP//3+P//0AAAAAAAfggAAA////P///gAAAAAAH8IAAAH///f///wAAAAAAH/wAAAP+7////+AAAAAAH/+AAAD8A////8cAAAAAf/8AAALwf////kgAAAAf/+AAAcC////+RAAAAB//wAAHi+////4AAAAD//wAAHfH////kAAAB//+AAH/v////AAAAf/+QAH/////+AAAD//8AB//////AAAH/3wAV/////iAIP/nAATf///4QDH/HAAO////CB//iEAd////8P/8mAP//////+bgP///+f9I4Hn//4BmeAB/4ATHgE8AC/wAYA"
    }
}
