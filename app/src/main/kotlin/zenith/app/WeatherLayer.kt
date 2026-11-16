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
            SkyKind.RAIN -> rain(t, shown, speed, heavy = false)
            SkyKind.STORM -> {
                rain(t, shown, speed, heavy = true)
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

private fun DrawScope.snow(t: Float, strength: Float, speedKmh: Float) {
    val k = sqrt(strength)
    val w = size.width
    val h = size.height
    val unit = minOf(w, h) / 400f
    val count = (90 * k).toInt().coerceAtLeast(14)
    val fall = h * (0.08f + 0.35f * (speedKmh / 130f).coerceIn(0f, 1f))
    for (i in 0 until count) {
        val depth = 0.3f + 0.7f * hash(i, 1)
        val y = ((hash(i, 2) + t * fall * (0.5f + depth) / h) % 1f) * (h + 20f) - 10f
        val sway = sin(t * (0.5f + hash(i, 4)) + hash(i, 5) * 2 * PI.toFloat()) * 14f * unit * depth
        val x = hash(i, 3) * w + sway
        val r = (1.2f + 2.6f * depth) * unit
        drawCircle(Color.White.copy(alpha = (0.35f + 0.5f * depth) * k), r, Offset(x, y))
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
