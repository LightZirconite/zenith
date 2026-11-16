package zenith.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import zenith.core.Sky
import zenith.core.SkyKind
import zenith.core.SkyLook
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * The weather over the screen: rain streaks that lean and quicken with the speed, snowflakes that
 * drift, a storm's distant lightning, fog drifting in soft banks. Every particle is a pure function
 * of its index and the time (nothing allocated per frame). [strength] from [Sky.strength]; at 0
 * nothing is drawn and no frame is asked for. The caller masks it like the map, so it never covers
 * the music's Canvas.
 */
@Composable
fun WeatherLayer(look: SkyLook, strength: Float, speedKmh: Int?, alerting: Boolean, modifier: Modifier = Modifier) {
    // A change of weather fades over 2 s; the kind drawn stays the last real one while it fades out.
    var kind by remember { mutableStateOf(look.kind) }
    LaunchedEffect(look.kind) { if (look.kind != SkyKind.CLEAR) kind = look.kind }
    val shown by animateFloatAsState(strength, tween(2_000), label = "sky")
    val speed by animateFloatAsState((speedKmh ?: 0).coerceIn(0, 150).toFloat(), tween(1_500), label = "skySpeed")
    if (shown < 0.01f) return
    var now by remember { mutableLongStateOf(0L) }
    val start = remember { withFrameStart() }
    LaunchedEffect(Unit) {
        start.value = withFrameMillis { it }
        while (true) withFrameMillis { now = it }
    }
    // Storm: lightning now and then, the first a few seconds in.
    var flashAt by remember { mutableLongStateOf(Long.MIN_VALUE / 2) }
    LaunchedEffect(kind) {
        if (kind != SkyKind.STORM) return@LaunchedEffect
        delay(Random.nextLong(2_000, Sky.FLASH_MIN_MS))
        while (true) {
            flashAt = withFrameMillis { it }
            delay(Random.nextLong(Sky.FLASH_MIN_MS, Sky.FLASH_MAX_MS))
        }
    }
    Canvas(modifier) {
        // Small times only (floats lose the frame's precision on a large clock); every particle loops well within 10 min.
        val t = ((now - start.value).coerceAtLeast(0L) % 600_000L) / 1000f
        when (kind) {
            SkyKind.RAIN -> {
                rain(t, shown, speed, heavy = false)
                drops(t, shown, speed)
            }
            SkyKind.STORM -> {
                rain(t, shown, speed, heavy = true)
                drops(t, shown, speed)
                if (!alerting) lightning(now - flashAt, shown)
            }
            SkyKind.SNOW -> snow(t, shown, speed)
            SkyKind.FOG -> fog(t, shown)
            SkyKind.CLEAR -> Unit
        }
    }
}

private const val FLASH_MS = 900L

/** The frame clock's first time, set at the first frame. */
private class FrameStart {
    var value = 0L
}

private fun withFrameStart() = FrameStart()

/** Stable pseudo-random 0..1 for particle [i], channel [c]. */
private fun hash(i: Int, c: Int): Float {
    var x = i * 374761393 + c * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0xFFFFFF) / 16777216f
}

private fun rgba(r: Int, g: Int, b: Int, a: Float) = Color(r, g, b, (a.coerceIn(0f, 1f) * 255).toInt())

/**
 * Rain falling: thin streaks in depth (far ones short and faint, near ones long and bright), each
 * fading toward its tail, leaning and quickening with the speed.
 */
private fun DrawScope.rain(t: Float, strength: Float, speedKmh: Float, heavy: Boolean) {
    // A light rain (0.35) must still be seen at a glance: density and light follow the square root.
    val k = sqrt(strength)
    val w = size.width
    val h = size.height
    val unit = minOf(w, h) / 400f
    val fast = (speedKmh / 130f).coerceIn(0f, 1f)
    val lean = 0.10f + 0.40f * fast
    val count = (((if (heavy) 220 else 170) * k).toInt()).coerceAtLeast(30).let { (it * areaShare()).toInt().coerceAtLeast(12) }
    for (i in 0 until count) {
        val d = hash(i, 1)
        val len = (10f + 44f * d * d) * unit * (if (heavy) 1.15f else 1f)
        val width = (0.5f + 1.5f * d) * unit
        val a = (0.10f + 0.42f * d) * k
        val ln = lean + (hash(i, 8) - 0.5f) * 0.06f
        val fall = h * (1.2f + 1.4f * d) * (1f + 0.8f * fast) * (if (heavy) 1.2f else 1f)
        val y = ((hash(i, 2) + t * fall / h) % 1f) * (h + len) - len
        val x = hash(i, 3) * (w * (1f + ln)) - w * ln * 0.5f - ln * (y + len)
        val mid = Offset(x - ln * len * 0.5f, y + len * 0.5f)
        drawLine(rgba(200, 220, 255, a * 0.35f), Offset(x, y), mid, strokeWidth = width, cap = StrokeCap.Round)
        drawLine(rgba(215, 230, 255, a), mid, Offset(x - ln * len, y + len), strokeWidth = width, cap = StrokeCap.Round)
    }
}

/** Fewer particles in a small window (the picture-in-picture) than on a whole phone screen. */
private fun DrawScope.areaShare(): Float = (size.width * size.height / (1080f * 1900f)).coerceIn(0.3f, 1f)

/**
 * One drop of water on the glass, read as a lens: lighter where it gathers the light below, a
 * dark rim, a bright crescent at the bottom, a sharp highlight at the top left.
 */
private fun DrawScope.waterDrop(c: Offset, r: Float, ry: Float, a: Float) {
    if (r < 0.3f) return
    drawOval(
        Brush.radialGradient(
            0f to rgba(235, 243, 255, 0.26f * a),
            0.7f to rgba(160, 180, 210, 0.08f * a),
            1f to rgba(0, 0, 0, 0.28f * a),
            center = Offset(c.x, c.y + ry * 0.35f),
            radius = maxOf(r, ry) * 1.05f,
        ),
        topLeft = Offset(c.x - r, c.y - ry),
        size = Size(r * 2, ry * 2),
    )
    drawOval(rgba(0, 0, 0, 0.30f * a), Offset(c.x - r, c.y - ry), Size(r * 2, ry * 2), style = Stroke(maxOf(r * 0.09f, 0.6f)))
    drawArc(rgba(255, 255, 255, 0.38f * a), 20f, 140f, false, Offset(c.x - r * 0.72f, c.y - ry * 0.72f), Size(r * 1.44f, ry * 1.44f), style = Stroke(r * 0.2f, cap = StrokeCap.Round))
    drawCircle(rgba(255, 255, 255, 0.92f * a), r * 0.17f, Offset(c.x - r * 0.38f, c.y - ry * 0.42f))
    drawCircle(rgba(255, 255, 255, 0.5f * a), r * 0.07f, Offset(c.x + r * 0.3f, c.y + ry * 0.38f))
}

/**
 * Water on the glass, as on a windscreen: a fine mist of tiny droplets, and drops that land with
 * a little splash, stay, sometimes run down leaving a trail of beads, then dry. At speed they run
 * more and live shorter. [slide] false (a window drawn a few times a second): they stay put.
 */
private fun DrawScope.drops(t: Float, strength: Float, speedKmh: Float, slide: Boolean = true) {
    if (size.minDimension < 1f) return
    val k = sqrt(strength)
    val w = size.width
    val h = size.height
    val unit = minOf(w, h) / 400f
    val fast = (speedKmh / 130f).coerceIn(0f, 1f)
    val share = areaShare()
    val mist = ((40 + 90 * k) * share).toInt()
    for (i in 0 until mist) {
        val life = 4f + 6f * hash(i, 21)
        val shifted = t + hash(i, 22) * life
        val cycle = (shifted / life).toInt()
        val age = (shifted - cycle * life) / life
        val seed = i * 104729 + cycle
        val a = (if (age < 0.1f) age / 0.1f else if (age > 0.75f) (1f - age) / 0.25f else 1f) * k
        val r = (0.8f + 1.6f * hash(seed, 23)) * unit
        val c = Offset(hash(seed, 24) * w, hash(seed, 25) * h)
        drawCircle(rgba(220, 232, 255, 0.18f * a), r, c)
        drawCircle(rgba(255, 255, 255, 0.7f * a), r * 0.35f, Offset(c.x - r * 0.35f, c.y - r * 0.35f))
    }
    val count = ((16 + 44 * k) * share).toInt().coerceAtLeast(6)
    for (i in 0 until count) {
        val life = 2.4f + 3.8f * hash(i, 11) - 1.2f * fast
        val shifted = t + hash(i, 12) * life
        val cycle = (shifted / life).toInt()
        val age = (shifted - cycle * life) / life
        val seed = i * 7919 + cycle
        val r0 = (3f + 8f * hash(seed, 13) * hash(seed, 14)) * unit
        val x0 = hash(seed, 15) * w
        val y0 = hash(seed, 16) * h
        val slides = slide && r0 > 5f * unit && hash(seed, 17) < 0.4f + 0.4f * fast
        val run = if (slides) maxOf(0f, age - 0.2f) / 0.8f else 0f
        val slid = run * run * (60f + 140f * fast) * unit
        val y = y0 + slid
        val a = (if (age < 0.1f) age / 0.1f else if (age > 0.72f) (1f - age) / 0.28f else 1f) * k
        val pop = if (age < 0.1f) 0.55f + 0.45f * age / 0.1f else 1f
        val r = r0 * pop * (if (slides) 0.92f else 1f)
        val ry = r * (0.88f + 0.1f * hash(seed, 18)) * (if (slides && slid > 0f) 1.25f else 1f)
        if (slides && slid > r) {
            // The trail: tiny beads left behind, fainter the older.
            val step = r * 1.6f
            var s = y0
            while (s < y - ry) {
                val f = (s - y0) / (y - y0)
                val bead = s.toInt()
                val br = r * (0.18f + 0.12f * hash(seed + bead, 19))
                val bx = x0 + (hash(seed + bead, 20) - 0.5f) * r * 0.4f
                drawCircle(rgba(220, 232, 255, 0.22f * a * (0.4f + 0.6f * f)), br, Offset(bx, s))
                drawCircle(rgba(255, 255, 255, 0.6f * a * (0.4f + 0.6f * f)), br * 0.35f, Offset(bx - br * 0.3f, s - br * 0.3f))
                s += step
            }
        }
        waterDrop(Offset(x0, y), r, ry, a)
        if (age < 0.1f) {
            val q = age / 0.1f
            drawCircle(rgba(255, 255, 255, 0.4f * (1f - q) * k), r0 * (1f + 1.3f * q), Offset(x0, y), style = Stroke(unit * 0.8f))
        }
    }
}

/**
 * Snow in depth: far flakes tiny and sharp, mid ones glowing, the nearest few large and out of
 * focus (bokeh), some with six arms turning; they drift with the wind and fall faster at speed.
 */
private fun DrawScope.snow(t: Float, strength: Float, speedKmh: Float) {
    if (size.minDimension < 1f) return
    val k = sqrt(strength)
    val w = size.width
    val h = size.height
    val unit = minOf(w, h) / 400f
    val fast = (speedKmh / 130f).coerceIn(0f, 1f)
    val count = (170 * k * areaShare()).toInt().coerceAtLeast(20)
    for (i in 0 until count) {
        val d = hash(i, 1)
        val fall = h * (0.06f + 0.10f * d + 0.45f * fast * d)
        val wind = (0.04f + 0.10f * fast) * w * d
        val y = ((hash(i, 2) + t * fall / h) % 1f) * (h + 40f) - 20f
        val sway = sin(t * (0.4f + 0.8f * hash(i, 4)) + hash(i, 5) * 2 * PI.toFloat()) * (10f + 18f * d) * unit
        var x = (hash(i, 3) * w + t * wind + sway) % (w + 40f)
        if (x < -20f) x += w + 40f
        x -= 20f
        val c = Offset(x, y)
        if (d > 0.9f) {
            val r = (9f + 10f * hash(i, 9)) * unit
            val a = 0.16f * k
            drawCircle(Brush.radialGradient(0f to rgba(255, 255, 255, a), 0.75f to rgba(255, 255, 255, a * 0.8f), 1f to Color.Transparent, center = c, radius = r), r, c)
            continue
        }
        val r = (1f + 3.2f * d * d) * unit
        val a = (0.35f + 0.55f * d) * k
        drawCircle(Brush.radialGradient(listOf(rgba(255, 255, 255, a * 0.45f), Color.Transparent), c, r * 2.4f), r * 2.4f, c)
        if (d > 0.7f) {
            val spin = t * (0.4f + hash(i, 6)) + hash(i, 7) * 6f
            for (arm in 0 until 3) {
                val ang = spin + arm * (PI.toFloat() / 3f)
                val dx = kotlin.math.cos(ang) * r * 1.5f
                val dy = sin(ang) * r * 1.5f
                drawLine(rgba(255, 255, 255, a), Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), strokeWidth = r * 0.28f, cap = StrokeCap.Round)
            }
            drawCircle(rgba(255, 255, 255, a), r * 0.35f, c)
        } else {
            drawCircle(rgba(255, 255, 255, a), r, c)
        }
    }
}

private fun DrawScope.fog(t: Float, k: Float) {
    val w = size.width
    val h = size.height
    if (w < 1f || h < 1f) return
    // Three soft banks drifting sideways, lower ones thicker, the road still seen through.
    for (b in 0 until 3) {
        val cy = h * (0.32f + 0.2f * b)
        val drift = sin(t * 0.05f * (1 + b) + b * 2.1f)
        val cx = w * (0.5f + 0.35f * drift)
        val rx = w * (0.9f + 0.2f * b)
        val ry = h * 0.16f
        val a = (0.12f + 0.08f * b) * k
        drawOval(
            Brush.radialGradient(listOf(Color.White.copy(alpha = a), Color.Transparent), Offset(cx, cy), rx),
            topLeft = Offset(cx - rx, cy - ry),
            size = Size(rx * 2, ry * 2),
        )
    }
}

/** A distant flash: two quick flickers of a soft light from the top, fading out. */
private fun DrawScope.lightning(sinceMs: Long, k: Float) {
    if (sinceMs !in 0..FLASH_MS) return
    val s = sinceMs / 1000f
    val flicker = exp(-s * 9f) + 0.6f * exp(-((s - 0.18f) * 22f).let { it * it })
    val a = (0.16f * flicker * k).coerceIn(0f, 0.2f)
    if (a < 0.005f || size.minDimension < 1f) return
    drawRect(
        Brush.radialGradient(
            listOf(Color(0xFFDDE6FF).copy(alpha = a), Color.Transparent),
            Offset(size.width * 0.5f, 0f),
            size.maxDimension * 0.9f,
        ),
    )
}
