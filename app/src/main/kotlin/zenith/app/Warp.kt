package zenith.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/** Where the start of a drive stands: stars drifting at home, the tunnel while the GPS searches, the jump. */
enum class FlightPhase { IDLE, SEARCH, ARRIVE }

private val Blue = Color(0xFF0A84FF)
private val Green = Color(0xFF30D158)
private val StarBlue = Color(0xFF9CC8FF)
private val StarRed = Color(0xFFFF6B5E)

/** Length of the jump, from the first good position to the map; the flash comes at [LANDING]. */
private const val JUMP_MS = 1_200
private const val LANDING = 0.72f

/**
 * Space behind the start of a drive, drawn frame by frame: at home, stars drift by slowly; once
 * started, they rush past in a tunnel while the GPS searches, the satellites in view hanging in
 * the sky (green once used for the fix); at [FlightPhase.ARRIVE] they stretch into shooting-star
 * streaks, a white flash fills the screen and the map takes over beneath it ([onLanded]), then
 * the stars fade out ([onGone]).
 */
@Composable
fun Starfield(phase: FlightPhase, gps: GpsInfo, onLanded: () -> Unit, onGone: () -> Unit, modifier: Modifier = Modifier) {
    val stars = remember { Stars(300) }
    val cruise by animateFloatAsState(
        if (phase == FlightPhase.IDLE) 0.035f else 0.6f,
        tween(1_600, easing = FastOutSlowInEasing),
        label = "cruise",
    )
    val sky by animateFloatAsState(if (phase == FlightPhase.SEARCH) 1f else 0f, tween(700), label = "sky")
    val jump = remember { Animatable(0f) }
    val landed by rememberUpdatedState(onLanded)
    val gone by rememberUpdatedState(onGone)
    LaunchedEffect(phase) {
        if (phase != FlightPhase.ARRIVE) {
            jump.snapTo(0f)
            return@LaunchedEffect
        }
        var sent = false
        jump.animateTo(1f, tween(JUMP_MS, easing = LinearEasing)) {
            if (!sent && value >= LANDING) {
                sent = true
                landed()
            }
        }
        gone()
    }
    val speed by rememberUpdatedState(cruise + 14f * jump.value.pow(3))
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) stars.step(((now - last) / 1e9f).coerceAtMost(0.05f), speed)
                last = now
                frame = now
            }
        }
    }

    Canvas(modifier) {
        val t = frame / 1e9f
        val cx = size.width / 2
        val cy = size.height * 0.42f
        val scale = min(size.width, size.height) * 0.42f
        val j = jump.value
        // After the flash, the stars give way to the map.
        val fade = if (j <= LANDING) 1f else (1f - (j - LANDING) / (1f - LANDING)).coerceIn(0f, 1f)
        stars.draw(this, cx, cy, scale, speed, t, fade, phase == FlightPhase.IDLE)

        // Satellites in view, high in the sky; those used for the fix glow green and link to the center.
        if (sky > 0.01f) {
            val count = gps.visible.coerceAtMost(28)
            for (i in 0 until count) {
                val angle = i * 2.3999632f + t * 0.06f
                val radius = (0.55f + 0.35f * ((i * 0.618034f) % 1f)) * scale * 1.6f
                val p = Offset(cx + cos(angle) * radius, cy + sin(angle) * radius * 0.75f)
                val used = i < gps.used
                val color = if (used) Green else Color.White
                if (used) drawLine(Green.copy(alpha = 0.12f * sky), Offset(cx, cy), p, strokeWidth = 1.dp.toPx())
                drawCircle(color.copy(alpha = 0.18f * sky), radius = 9.dp.toPx(), center = p)
                drawCircle(color.copy(alpha = 0.9f * sky), radius = 2.5.dp.toPx(), center = p)
            }
        }

        // The jump's flash: white at the heart, blue at the edges, at its brightest as the map comes in.
        val flash = exp(-((j - LANDING) / 0.1f).pow(2)) * if (j > 0f) 1f else 0f
        if (flash > 0.01f) {
            drawRect(
                Brush.radialGradient(
                    0f to Color.White.copy(alpha = flash),
                    0.6f to StarBlue.copy(alpha = flash * 0.7f),
                    1f to Blue.copy(alpha = flash * 0.35f),
                    center = Offset(cx, cy),
                    radius = max(size.width, size.height),
                ),
            )
        }
    }
}

/**
 * The middle of the screen while the GPS searches: a sonar ring sweeping around the number of
 * satellites captured, the ring pulsing outward. In the demo there is no GPS: the ring says so.
 */
@Composable
fun FlightCaption(gps: GpsInfo, demo: Boolean) {
    val spin = rememberInfiniteTransition(label = "sonar")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1_600, easing = LinearEasing)), label = "sweep")
    val pulse by spin.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2 * 0.8f
                drawCircle(Color.White.copy(alpha = 0.08f), radius = r, style = Stroke(1.dp.toPx()))
                drawCircle(Blue.copy(alpha = 0.5f * (1f - pulse)), radius = r * (0.55f + 0.6f * pulse), style = Stroke(1.5.dp.toPx()))
                rotate(angle) {
                    drawArc(
                        Brush.sweepGradient(0f to Color.Transparent, 0.75f to Color.Transparent, 1f to Blue.copy(alpha = 0.9f), center = center),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(center.x - r, center.y - r),
                        size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                        style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (demo) "∞" else "${gps.used}",
                    color = Color.White,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                )
                Text(
                    if (demo) "trajet simulé" else "satellites sur ${gps.visible}",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(if (demo) "Décollage" else "Recherche de ta position", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Stars in a cylinder ahead of the camera; the middle stays clear, so at speed it reads as a tunnel. */
private class Stars(private val count: Int) {
    private val random = Random(42)
    private val x = FloatArray(count)
    private val y = FloatArray(count)
    private val z = FloatArray(count)
    private val tint = IntArray(count)
    private val phase = FloatArray(count)

    init {
        for (i in 0 until count) spawn(i, 0.05f + random.nextFloat() * 0.95f)
    }

    private fun spawn(i: Int, depth: Float) {
        val angle = random.nextFloat() * 2 * PI.toFloat()
        val r = 0.12f + random.nextFloat().pow(0.6f) * 1.3f
        x[i] = cos(angle) * r
        y[i] = sin(angle) * r
        z[i] = depth
        tint[i] = random.nextInt(24)
        phase[i] = random.nextFloat() * 6.28f
    }

    fun step(dt: Float, speed: Float) {
        for (i in 0 until count) {
            z[i] -= speed * dt
            if (z[i] <= 0.03f) spawn(i, 1f)
        }
    }

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, cx: Float, cy: Float, scale: Float, speed: Float, t: Float, fade: Float, idle: Boolean) = with(scope) {
        if (fade <= 0f) return@with
        val margin = 40f
        for (i in 0 until count) {
            val depth = z[i]
            val px = cx + x[i] / depth * scale
            val py = cy + y[i] / depth * scale
            if (px < -margin || px > size.width + margin || py < -margin || py > size.height + margin) continue
            val near = (1f - depth).coerceIn(0f, 1f)
            val twinkle = if (idle) 0.65f + 0.35f * sin(t * 2.2f + phase[i]) else 1f
            val alpha = ((0.18f + near * near * 1.1f) * twinkle * fade).coerceIn(0f, 1f)
            val color = when (tint[i]) {
                0 -> StarRed
                1, 2, 3 -> StarBlue
                else -> Color.White
            }.copy(alpha = alpha)
            val width = (0.7f + near * 2.4f) * density
            // The trail: where the star was a moment ago; long at speed, nothing at rest.
            val tailDepth = (depth + speed * 0.05f).coerceAtMost(1.3f)
            val tx = cx + x[i] / tailDepth * scale
            val ty = cy + y[i] / tailDepth * scale
            if ((px - tx) * (px - tx) + (py - ty) * (py - ty) < width * width) {
                drawCircle(color, radius = width / 2 + 0.4f, center = Offset(px, py))
            } else {
                drawLine(color, Offset(tx, ty), Offset(px, py), strokeWidth = width, cap = StrokeCap.Round)
            }
        }
    }
}

/**
 * The flight's sounds, synthesized like the alert tones (no assets): the engines spooling up at
 * Start, a low hum looping while the GPS searches, then the jump, a rising rush ending in a deep
 * boom with a bright shimmer as the map appears. Over the music, never pausing it.
 */
class WarpSound {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private var engage: AudioTrack? = null
    private var hum: AudioTrack? = null
    private var arrive: AudioTrack? = null

    /** Builds the sounds off the main thread; anything asked before is silently skipped. */
    suspend fun prepare() = withContext(Dispatchers.Default) {
        runCatching {
            engage = track(engageWave())
            arrive = track(arriveWave())
            hum = track(humWave()).apply { setLoopPoints(0, HUM_SAMPLES, -1) }
        }
    }

    fun engage() = replay(engage)

    fun startHum() {
        val h = hum ?: return
        runCatching {
            h.setVolume(0.55f)
            if (h.playState != AudioTrack.PLAYSTATE_PLAYING) {
                h.reloadStaticData()
                h.setLoopPoints(0, HUM_SAMPLES, -1)
                h.play()
            }
        }
    }

    fun stopHum() {
        runCatching { hum?.takeIf { it.playState == AudioTrack.PLAYSTATE_PLAYING }?.pause() }
    }

    fun arrive() {
        stopHum()
        replay(arrive)
    }

    fun release() {
        listOfNotNull(engage, hum, arrive).forEach { runCatching { it.release() } }
        engage = null
        hum = null
        arrive = null
    }

    private fun replay(track: AudioTrack?) {
        val t = track ?: return
        runCatching {
            t.stop()
            t.reloadStaticData()
            t.play()
        }
    }

    private fun track(pcm: ShortArray): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(attributes)
        .setAudioFormat(
            AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
        )
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(pcm.size * 2)
        .build()
        .also { it.write(pcm, 0, pcm.size) }

    private companion object {
        const val RATE = 44_100

        /** Two seconds: every partial of the hum fits a whole number of cycles, so the loop is seamless. */
        const val HUM_SAMPLES = 2 * RATE

        fun pcm(seconds: Float, sample: (t: Float, noise: Float) -> Float): ShortArray {
            val random = Random(7)
            var low = 0f
            return ShortArray((seconds * RATE).toInt()) { i ->
                // Brown-ish noise: white noise through a one-pole low-pass, the "air" of a rush.
                low += (random.nextFloat() * 2 - 1 - low) * 0.08f
                val v = sample(i.toFloat() / RATE, low * 3f)
                (tanh(v) * 0.9f * Short.MAX_VALUE).toInt().toShort()
            }
        }

        /** Engines spooling up: a sweep from deep to mid, air rushing in, rising then letting go. */
        fun engageWave(): ShortArray {
            val length = 1.4f
            var phase = 0f
            return pcm(length) { t, noise ->
                val k = t / length
                phase += 2 * PI.toFloat() * (55f + 300f * k * k) / RATE
                val env = smooth(k / 0.75f) * (1f - smooth((k - 0.75f) / 0.25f))
                env * (0.55f * sin(phase) + 0.2f * sin(phase * 2.01f) + noise * (0.15f + 0.6f * k))
            }
        }

        /** The hum of the tunnel: deep partials breathing slowly, a faint shimmer on top. */
        fun humWave(): ShortArray = pcm(HUM_SAMPLES.toFloat() / RATE) { t, _ ->
            val breath = 1f + 0.3f * sin(2 * PI.toFloat() * 0.5f * t)
            val w = 2 * PI.toFloat() * t
            0.32f * breath * (sin(w * 55f) + 0.5f * sin(w * 110f) + 0.25f * sin(w * 165f)) + 0.03f * breath * sin(w * 880f)
        }

        /** The jump: a rush rising fast, then at the flash a deep boom and a bright chord ringing out. */
        fun arriveWave(): ShortArray {
            val impact = JUMP_MS * LANDING / 1_000f
            var rush = 0f
            var boom = 0f
            return pcm(impact + 1.6f) { t, noise ->
                var v = 0f
                if (t < impact) {
                    val k = t / impact
                    rush += 2 * PI.toFloat() * (120f + 900f * k * k * k) / RATE
                    v += k * k * (0.35f * sin(rush) + noise * 0.9f)
                } else {
                    val d = t - impact
                    // Pitch dropping from 70 to 35 Hz as it decays: the body of the boom.
                    boom += 2 * PI.toFloat() * (35f + 35f * exp(-d / 0.25f)) / RATE
                    v += 1.6f * exp(-d / 0.5f) * sin(boom)
                    v += noise * 2.2f * exp(-d / 0.07f)
                    val ring = 0.12f * exp(-d / 0.7f)
                    val w = 2 * PI.toFloat() * d
                    v += ring * (sin(w * 1318.5f) + sin(w * 1975.5f) + 0.6f * sin(w * 2637f))
                }
                v
            }
        }

        fun smooth(x: Float): Float {
            val k = x.coerceIn(0f, 1f)
            return k * k * (3 - 2 * k)
        }
    }
}

/** Sounds of the flight for this screen, built once, released with it. */
@Composable
fun rememberWarpSound(): WarpSound {
    val sound = remember { WarpSound() }
    LaunchedEffect(sound) { sound.prepare() }
    DisposableEffect(sound) { onDispose { sound.release() } }
    return sound
}

/** Over the limit near a radar: the edges of the whole screen pulse red, the middle stays clear. */
@Composable
fun DangerGlow(on: Boolean, modifier: Modifier = Modifier) {
    val level by animateFloatAsState(if (on) 1f else 0f, tween(400), label = "danger")
    if (level <= 0f) return
    val pulse = rememberInfiniteTransition(label = "dangerPulse")
    val beat by pulse.animateFloat(0.45f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "dangerBeat")
    Canvas(modifier) {
        drawRect(
            Brush.radialGradient(
                0.55f to Color.Transparent,
                1f to Color(0xFFFF3B30).copy(alpha = 0.55f * beat * level),
                center = center,
                radius = max(size.width, size.height) * 0.75f,
            ),
        )
    }
}
