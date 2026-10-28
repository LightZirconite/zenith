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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
private val PanelBlue = Color(0xFF2F6BD8)

/** Length of the jump, from the first good position to the map; the flash comes at [LANDING]. */
private const val JUMP_MS = 1_800
private const val LANDING = 0.68f

/**
 * Space behind the start of a drive, drawn frame by frame. At home, a calm sky drifts by. Once
 * started, a tube of stars forms around the viewer and rushes past, faster as the search goes on
 * and as satellites lock, rings of light marking its walls; real satellites, panels out, orbit
 * outside it, beaming green once used for the fix. At [FlightPhase.ARRIVE] everything stretches
 * into shooting-star streaks, a white flash fills the screen and the map opens beneath it
 * ([onLanded]), then the stars fade out ([onGone]).
 */
@Composable
fun Starfield(phase: FlightPhase, gps: GpsInfo, onLanded: () -> Unit, onGone: () -> Unit, modifier: Modifier = Modifier) {
    val sky = remember { Stars(380, tube = false) }
    val tube = remember { Stars(900, tube = true) }
    // The tube forms around the viewer at Start and speeds up the longer the search, the more satellites locked.
    val tunnel by animateFloatAsState(if (phase == FlightPhase.IDLE) 0f else 1f, tween(1_800, easing = FastOutSlowInEasing), label = "tunnel")
    var searchingSinceNs by remember { mutableLongStateOf(0L) }
    val orbit by animateFloatAsState(if (phase == FlightPhase.SEARCH) 1f else 0f, tween(900), label = "orbit")
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
    val locked by rememberUpdatedState(gps.used)
    val inPhase by rememberUpdatedState(phase)
    var frame by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(0.035f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (inPhase == FlightPhase.SEARCH && searchingSinceNs == 0L) searchingSinceNs = now
                if (inPhase == FlightPhase.IDLE) searchingSinceNs = 0L
                val searched = if (searchingSinceNs == 0L) 0f else (now - searchingSinceNs) / 1e9f
                // Progressive: from a cruise to a race over the first seconds, a little more per satellite.
                val cruise = 0.035f + tunnel * (0.45f + 0.9f * (searched / 8f).coerceAtMost(1f) + 0.06f * locked.coerceAtMost(8))
                speed = cruise + 16f * jump.value.pow(3)
                if (last != 0L) {
                    val dt = ((now - last) / 1e9f).coerceAtMost(0.05f)
                    sky.step(dt, speed * 0.35f + 0.02f)
                    tube.step(dt, speed)
                }
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
        sky.draw(this, cx, cy, scale, speed * 0.35f, t, fade * (1f - 0.5f * tunnel), twinkle = true)
        if (tunnel > 0.01f) {
            tubeRings(cx, cy, scale, t, speed, tunnel * fade)
            tube.draw(this, cx, cy, scale, speed, t, fade * tunnel, twinkle = false)
        }
        if (orbit > 0.01f) satellites(gps, cx, cy, scale, t, orbit * fade)

        // The jump's flash: white at the heart, blue at the edges, at its brightest as the map opens.
        val flash = if (j > 0f) exp(-((j - LANDING) / 0.09f).pow(2)) else 0f
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

/** Rings of light along the tube's wall, rushing toward the viewer: they make the tunnel read as one. */
private fun DrawScope.tubeRings(cx: Float, cy: Float, scale: Float, t: Float, speed: Float, alpha: Float) {
    val spacing = 0.25f
    // Depth of each ring moves with the distance travelled.
    val travelled = (t * speed * 0.6f) % spacing
    var z = spacing - travelled
    while (z < 1.2f) {
        if (z > 0.05f) {
            val near = (1f - z / 1.2f).coerceIn(0f, 1f)
            drawCircle(
                StarBlue.copy(alpha = alpha * near * near * 0.35f),
                radius = 1.15f / z * scale,
                center = Offset(cx, cy),
                style = Stroke((0.5f + near * 3f) * density),
            )
        }
        z += spacing
    }
}

/**
 * The satellites in view, outside the tube: a body and two blue panels turning slowly, a blinking
 * light; the ones used for the fix send a green beam to the center.
 */
private fun DrawScope.satellites(gps: GpsInfo, cx: Float, cy: Float, scale: Float, t: Float, alpha: Float) {
    val count = gps.visible.coerceAtMost(16)
    for (i in 0 until count) {
        val angle = i * 2.3999632f + t * 0.05f
        val radius = (1.25f + 0.55f * ((i * 0.618034f) % 1f)) * scale
        val p = Offset(cx + cos(angle) * radius, cy + sin(angle) * radius * 0.85f)
        val used = i < gps.used
        if (used) {
            val beat = 0.5f + 0.5f * sin(t * 5f + i)
            drawLine(Green.copy(alpha = alpha * (0.15f + 0.25f * beat)), Offset(cx, cy), p, strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        }
        rotate(degrees = (t * 20f + i * 40f) % 360f, pivot = p) {
            val u = 3.dp.toPx()
            drawRect(PanelBlue.copy(alpha = alpha), topLeft = Offset(p.x - 6.5f * u, p.y - 1.4f * u), size = Size(4.5f * u, 2.8f * u))
            drawRect(PanelBlue.copy(alpha = alpha), topLeft = Offset(p.x + 2f * u, p.y - 1.4f * u), size = Size(4.5f * u, 2.8f * u))
            drawLine(Color.White.copy(alpha = alpha * 0.6f), Offset(p.x - 2f * u, p.y), Offset(p.x + 2f * u, p.y), strokeWidth = 0.4f * u)
            drawRect(Color(0xFFD8D8DE).copy(alpha = alpha), topLeft = Offset(p.x - 1.4f * u, p.y - 1.4f * u), size = Size(2.8f * u, 2.8f * u))
        }
        val blink = if (sin(t * 3f + i * 1.7f) > 0.6f) 1f else 0.15f
        drawCircle((if (used) Green else StarRed).copy(alpha = alpha * blink), radius = 2.dp.toPx(), center = p)
        drawCircle((if (used) Green else Color.White).copy(alpha = alpha * 0.12f), radius = 16.dp.toPx(), center = p)
    }
}

/** The arrival's shock ring: a bright circle racing out from the tunnel's heart as the scene opens. */
@Composable
fun ShockRing(progress: Float, modifier: Modifier = Modifier) {
    if (progress <= 0f || progress >= 1f) return
    Canvas(modifier) {
        val reach = kotlin.math.hypot(size.width, size.height)
        val k = FastOutSlowInEasing.transform(progress)
        drawCircle(
            Color.White.copy(alpha = 0.8f * (1f - k)),
            radius = k * reach,
            center = Offset(size.width / 2, size.height * 0.42f),
            style = Stroke((14f * (1f - k) + 1f) * density),
        )
    }
}

/**
 * The middle of the screen while the GPS searches, a lock-on display: a ring of ticks turning, an
 * arc filling as satellites lock (four make a position), their number in the center, and what is
 * happening in words. In the demo there is no GPS: the arc simply fills.
 */
@Composable
fun FlightCaption(gps: GpsInfo, demo: Boolean) {
    val spin = rememberInfiniteTransition(label = "lockOn")
    val turn by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(12_000, easing = LinearEasing)), label = "ticks")
    val sweep by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1_800, easing = LinearEasing)), label = "sweep")
    val glow by spin.animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow")
    val target = if (demo) 1f else (gps.used / 4f).coerceAtMost(1f)
    val lock by animateFloatAsState(target, tween(900, easing = FastOutSlowInEasing), label = "lock")
    val ready = lock >= 0.999f
    val arcColor = if (ready) Green else Blue
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(230.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2 * 0.86f
                // Outer ring of ticks, slowly turning.
                rotate(turn) {
                    for (k in 0 until 72) {
                        val a = k * 5f * (PI.toFloat() / 180f)
                        val long = k % 6 == 0
                        val inner = r * if (long) 0.9f else 0.94f
                        drawLine(
                            Color.White.copy(alpha = if (long) 0.5f else 0.2f),
                            Offset(center.x + cos(a) * inner, center.y + sin(a) * inner),
                            Offset(center.x + cos(a) * r, center.y + sin(a) * r),
                            strokeWidth = (if (long) 2f else 1f) * density,
                        )
                    }
                }
                // The lock arc: how far the position is from being known.
                val ar = r * 0.8f
                drawCircle(Color.White.copy(alpha = 0.08f), radius = ar, style = Stroke(6.dp.toPx()))
                drawArc(
                    arcColor.copy(alpha = 0.35f * glow),
                    startAngle = -90f,
                    sweepAngle = 360f * lock,
                    useCenter = false,
                    topLeft = Offset(center.x - ar, center.y - ar),
                    size = Size(ar * 2, ar * 2),
                    style = Stroke(14.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    arcColor,
                    startAngle = -90f,
                    sweepAngle = 360f * lock,
                    useCenter = false,
                    topLeft = Offset(center.x - ar, center.y - ar),
                    size = Size(ar * 2, ar * 2),
                    style = Stroke(6.dp.toPx(), cap = StrokeCap.Round),
                )
                // The scanning beam inside, until the lock.
                if (!ready) {
                    val sr = r * 0.66f
                    rotate(sweep) {
                        drawArc(
                            Brush.sweepGradient(0f to Color.Transparent, 0.8f to Color.Transparent, 1f to Blue.copy(alpha = 0.6f), center = center),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = true,
                            topLeft = Offset(center.x - sr, center.y - sr),
                            size = Size(sr * 2, sr * 2),
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (demo) "∞" else "${gps.used}",
                    color = if (ready) Green else Color.White,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
                Text(
                    if (demo) "trajet simulé" else "satellites · ${gps.visible} en vue",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            when {
                demo -> "Décollage"
                ready -> "Position verrouillée"
                gps.used > 0 -> "Triangulation · ${gps.used} sur 4"
                gps.visible > 0 -> "Contact des satellites…"
                else -> "Recherche du ciel…"
            },
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Stars ahead of the camera. The sky fills the whole view; the tube keeps them on a wall around
 * the viewer, so at speed they read as a tunnel.
 */
private class Stars(private val count: Int, private val tube: Boolean) {
    private val random = Random(if (tube) 42 else 7)
    private val x = FloatArray(count)
    private val y = FloatArray(count)
    private val z = FloatArray(count)
    private val tint = IntArray(count)
    private val phase = FloatArray(count)

    init {
        for (i in 0 until count) spawn(i, 0.05f + random.nextFloat() * 1.15f)
    }

    private fun spawn(i: Int, depth: Float) {
        val angle = random.nextFloat() * 2 * PI.toFloat()
        val r = if (tube) 1f + random.nextFloat() * 0.35f else 0.05f + random.nextFloat().pow(0.5f) * 2.2f
        x[i] = cos(angle) * r
        y[i] = sin(angle) * r
        z[i] = depth
        tint[i] = random.nextInt(24)
        phase[i] = random.nextFloat() * 6.28f
    }

    fun step(dt: Float, speed: Float) {
        for (i in 0 until count) {
            z[i] -= speed * dt
            if (z[i] <= 0.03f) spawn(i, 1.2f)
        }
    }

    fun draw(scope: DrawScope, cx: Float, cy: Float, scale: Float, speed: Float, t: Float, fade: Float, twinkle: Boolean) = with(scope) {
        if (fade <= 0f) return@with
        val margin = 40f
        for (i in 0 until count) {
            val depth = z[i]
            val px = cx + x[i] / depth * scale
            val py = cy + y[i] / depth * scale
            if (px < -margin || px > size.width + margin || py < -margin || py > size.height + margin) continue
            val near = (1f - depth / 1.2f).coerceIn(0f, 1f)
            val shine = if (twinkle) 0.65f + 0.35f * sin(t * 2.2f + phase[i]) else 1f
            val alpha = ((0.15f + near * near * 1.1f) * shine * fade).coerceIn(0f, 1f)
            val color = when (tint[i]) {
                0 -> StarRed
                1, 2, 3, 4 -> StarBlue
                else -> Color.White
            }.copy(alpha = alpha)
            val width = (0.6f + near * 2.6f) * density
            // The trail: where the star was a moment ago; long at speed, nothing at rest.
            val tailDepth = (depth + speed * 0.05f).coerceAtMost(1.4f)
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
 * Start, a low hum looping while the GPS searches, then the jump, a rising rush ending in a round
 * deep boom and a warm chord as the map appears. Over the music, never pausing it.
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

    /** The hum dies away over [ms] instead of stopping dead. */
    suspend fun fadeHum(ms: Long = 700) {
        val h = hum?.takeIf { runCatching { it.playState == AudioTrack.PLAYSTATE_PLAYING }.getOrDefault(false) } ?: return
        val steps = 14
        for (k in steps downTo 0) {
            runCatching { h.setVolume(0.55f * k / steps) }
            delay(ms / steps)
        }
        runCatching { h.pause() }
    }

    fun arrive() = replay(arrive)

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
            val length = 2.2f
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

        /**
         * The jump: a rush rising fast, then at the flash a round deep boom, the rush's air settling
         * and a warm chord swelling under it, the whole dying away slowly: an arrival, not a cut.
         */
        fun arriveWave(): ShortArray {
            val impact = JUMP_MS * LANDING / 1_000f
            val length = impact + 3.6f
            var rush = 0f
            var boom = 0f
            return pcm(length) { t, noise ->
                var v = 0f
                if (t < impact) {
                    val k = t / impact
                    rush += 2 * PI.toFloat() * (120f + 900f * k * k * k) / RATE
                    v += k * k * (0.35f * sin(rush) + noise * 0.9f)
                } else {
                    val d = t - impact
                    // Pitch dropping from 70 to 35 Hz as it decays: the body of the boom.
                    boom += 2 * PI.toFloat() * (35f + 35f * exp(-d / 0.25f)) / RATE
                    // A round, deep landing (soft attack), no bell: the air settles, a warm chord carries on.
                    v += 1.0f * smooth(d / 0.03f) * exp(-d / 0.9f) * sin(boom)
                    v += noise * 0.9f * exp(-d / 0.35f)
                    val w = 2 * PI.toFloat() * d
                    val pad = 0.14f * smooth(d / 0.5f) * exp(-d / 1.8f)
                    v += pad * (sin(w * 110f) + sin(w * 138.6f) + sin(w * 164.8f) + 0.5f * sin(w * 220f))
                }
                // Last half second: down to silence, never a click.
                v * (1f - smooth((t - (length - 0.5f)) / 0.5f))
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
