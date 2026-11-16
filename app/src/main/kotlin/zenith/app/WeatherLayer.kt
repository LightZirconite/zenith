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

private val RainTint = Color(0xFFBFD8FF)

private fun DrawScope.rain(t: Float, strength: Float, speedKmh: Float, heavy: Boolean) {
    // A light rain (0.35) must still be seen at a glance: density and light follow the square root.
    val k = sqrt(strength)
    val w = size.width
    val h = size.height
    val unit = minOf(w, h) / 400f
    val count = ((if (heavy) 170 else 130) * k).toInt().coerceAtLeast(20)
    // Faster car: the streaks lean more and fall faster, as on a windscreen.
    val lean = 0.12f + 0.38f * (speedKmh / 130f).coerceIn(0f, 1f)
    val fall = h * (1.1f + 0.9f * (speedKmh / 130f).coerceIn(0f, 1f)) * (if (heavy) 1.25f else 1f)
    for (i in 0 until count) {
        val depth = 0.35f + 0.65f * hash(i, 1)
        val len = (14f + 26f * depth) * unit * (if (heavy) 1.2f else 1f)
        val y = ((hash(i, 2) + t * fall * depth / h) % 1f) * (h + len) - len
        val x0 = hash(i, 3) * (w * (1f + lean)) - w * lean * 0.5f
        val x = x0 - lean * (y + len)
        val a = (0.22f + 0.38f * depth) * k
        drawLine(
            RainTint.copy(alpha = a),
            Offset(x, y),
            Offset(x - lean * len, y + len),
            strokeWidth = (1.0f + 1.1f * depth) * unit,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Drops landing on the glass, as on a windscreen: each lands with a little splash, catches the
 * light (a bright crescent on top, a darker rim below), stays a few seconds, sometimes slides down
 * leaving a faint trail, then dries. At speed they slide more and live shorter.
 */
private fun DrawScope.drops(t: Float, strength: Float, speedKmh: Float) {
    if (size.minDimension < 1f) return
    val k = sqrt(strength)
    val w = size.width
    val h = size.height
    val unit = minOf(w, h) / 400f
    val fast = (speedKmh / 130f).coerceIn(0f, 1f)
    val count = (14 + 46 * k).toInt()
    for (i in 0 until count) {
        val life = 2.2f + 3.8f * hash(i, 11) - 1.2f * fast
        val shifted = t + hash(i, 12) * life
        val cycle = (shifted / life).toInt()
        val age = (shifted - cycle * life) / life // 0 → 1 over the drop's life
        val seed = i * 7919 + cycle
        val r = (3.2f + 7.5f * hash(seed, 13) * hash(seed, 14)) * unit
        val x0 = hash(seed, 15) * w
        val y0 = hash(seed, 16) * h
        // One drop in three slides down (more at speed), slowly then faster.
        val slides = hash(seed, 17) < 0.33f + 0.4f * fast
        val slide = if (slides) age * age * (50f + 110f * fast) * unit else 0f
        val y = y0 + slide
        // Lands (0.12 of its life), stays, dries away (last 0.3).
        val alpha = when {
            age < 0.12f -> age / 0.12f
            age > 0.7f -> (1f - age) / 0.3f
            else -> 1f
        } * k
        val pop = if (age < 0.12f) 0.6f + 0.4f * (age / 0.12f) else 1f
        val rr = r * pop
        val c = Offset(x0, y)
        if (slides && slide > rr) {
            drawLine(Color.White.copy(alpha = 0.10f * alpha), Offset(x0, y0), Offset(x0, y - rr), strokeWidth = rr * 0.45f, cap = StrokeCap.Round)
        }
        // Body: a little water lens, lighter at the bottom where it gathers the light.
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFFDDEBFF).copy(alpha = 0.05f * alpha), Color(0xFFDDEBFF).copy(alpha = 0.22f * alpha)),
                center = Offset(c.x, c.y - rr * 0.3f),
                radius = rr * 1.2f,
            ),
            rr,
            c,
        )
        // Dark rim below, bright crescent on top: the drop reads as water, not as a dot.
        drawArc(Color.Black.copy(alpha = 0.35f * alpha), 20f, 140f, false, Offset(c.x - rr, c.y - rr), Size(rr * 2, rr * 2), style = Stroke(rr * 0.22f))
        drawArc(Color.White.copy(alpha = 0.75f * alpha), 200f, 90f, false, Offset(c.x - rr * 0.7f, c.y - rr * 0.7f), Size(rr * 1.4f, rr * 1.4f), style = Stroke(rr * 0.2f, cap = StrokeCap.Round))
        drawCircle(Color.White.copy(alpha = 0.85f * alpha), rr * 0.16f, Offset(c.x - rr * 0.35f, c.y - rr * 0.4f))
        // The splash as it lands: a thin ring opening out.
        if (age < 0.12f) {
            val q = age / 0.12f
            drawCircle(Color.White.copy(alpha = 0.4f * (1f - q) * k), r * (1f + 1.2f * q), c, style = Stroke(unit * 0.8f))
        }
    }
}

private fun DrawScope.snow(t: Float, strength: Float, speedKmh: Float) {
    if (size.minDimension < 1f) return
    val k = sqrt(strength)
    val w = size.width
    val h = size.height
    val unit = minOf(w, h) / 400f
    val count = (110 * k).toInt().coerceAtLeast(24)
    val fall = h * (0.08f + 0.35f * (speedKmh / 130f).coerceIn(0f, 1f))
    for (i in 0 until count) {
        val depth = 0.3f + 0.7f * hash(i, 1)
        val y = ((hash(i, 2) + t * fall * (0.5f + depth) / h) % 1f) * (h + 20f) - 10f
        val sway = sin(t * (0.5f + hash(i, 4)) + hash(i, 5) * 2 * PI.toFloat()) * 14f * unit * depth
        val x = hash(i, 3) * w + sway
        val r = (1.6f + 3.4f * depth * depth) * unit
        val a = (0.45f + 0.5f * depth) * k
        val c = Offset(x, y)
        // Soft glow, then the flake.
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f * a), Color.Transparent), c, r * 2.6f), r * 2.6f, c)
        if (depth > 0.85f) {
            // The nearest ones show their six arms, turning slowly.
            val spin = t * (0.3f + hash(i, 6)) + hash(i, 7) * 6f
            for (arm in 0 until 3) {
                val ang = spin + arm * (PI.toFloat() / 3f)
                val dx = kotlin.math.cos(ang) * r * 1.6f
                val dy = sin(ang) * r * 1.6f
                drawLine(Color.White.copy(alpha = a), Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), strokeWidth = r * 0.35f, cap = StrokeCap.Round)
            }
        } else {
            drawCircle(Color.White.copy(alpha = a), r, c)
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
