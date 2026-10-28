package zenith.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random
import kotlin.math.cos

/** Where the start of a drive stands: stars drifting at home, the tunnel while the GPS searches, the jump. */
enum class FlightPhase { IDLE, SEARCH, ARRIVE }

private val Blue = Color(0xFF0A84FF)
private val Green = Color(0xFF30D158)
private val StarBlue = Color(0xFF9CC8FF)
private val StarRed = Color(0xFFFF6B5E)

/** Height of the tunnel's heart, as a share of the screen: the globe, the flash and the portal sit there. */
internal const val HEART_Y = 0.42f

/** Radius of the globe at the heart while the GPS searches, as a share of the screen's smaller side. */
internal const val GLOBE_SHARE = 0.15f

/** Length of the jump, from the first good position to the map; the flash comes at [LANDING]. */
private const val JUMP_MS = 1_800
private const val LANDING = 0.68f

/** From the start of the jump to its boom, when the music comes back. */
internal val JUMP_IMPACT_MS: Long = (JUMP_MS * LANDING).toLong()

/**
 * Space behind the start of a drive, drawn frame by frame. At home, a calm sky drifts by. Once
 * started, a tube of stars forms around the viewer and rushes past, faster as the search goes on
 * and as satellites lock, rings of light marking its walls; at its end the Earth ([Globe]) turns,
 * its satellites locking one by one onto France. At [FlightPhase.ARRIVE] the stars stretch into
 * shooting-star streaks and the Earth rushes at the viewer until it fills the screen; a white flash
 * and the map opens beneath it ([onLanded]), then the stars fade out ([onGone]). [comingHome]: a
 * drive just ended, the stars come back rushing and slow down to the calm of home.
 */
@Composable
fun Starfield(
    phase: FlightPhase,
    gps: GpsInfo,
    onLanded: () -> Unit,
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
    comingHome: Boolean = false,
) {
    val sky = remember { Stars(380, tube = false) }
    val tube = remember { Stars(900, tube = true) }
    val globe = remember { Globe() }
    val lock by animateFloatAsState((gps.used / 4f).coerceAtMost(1f), tween(900, easing = FastOutSlowInEasing), label = "lock")
    // Out of hyperspace: from a rush down to the drift of home.
    val homecoming = remember { Animatable(if (comingHome) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (homecoming.value > 0f) homecoming.animateTo(0f, tween(2_600, easing = LinearOutSlowInEasing))
    }
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
                speed = cruise + 16f * jump.value.pow(3) + 9f * homecoming.value.pow(2)
                if (last != 0L) {
                    val dt = ((now - last) / 1e9f).coerceAtMost(0.05f)
                    sky.step(dt, speed * 0.35f + 0.02f)
                    tube.step(dt, speed)
                    globe.step(dt, lock)
                }
                last = now
                frame = now
            }
        }
    }

    Canvas(modifier) {
        val t = frame / 1e9f
        val cx = size.width / 2
        val cy = size.height * HEART_Y
        val scale = min(size.width, size.height) * 0.42f
        val j = jump.value
        // After the flash, the stars give way to the map.
        val fade = if (j <= LANDING) 1f else (1f - (j - LANDING) / (1f - LANDING)).coerceIn(0f, 1f)
        sky.draw(this, cx, cy, scale, speed * 0.35f, t, fade * (1f - 0.5f * tunnel), twinkle = true)
        if (tunnel > 0.01f) {
            tubeRings(cx, cy, scale, t, speed, tunnel * fade)
            tube.draw(this, cx, cy, scale, speed, t, fade * tunnel, twinkle = false)
            // The Earth ahead: out of the distance as the tube forms, a little nearer as satellites
            // lock, then rushing at the viewer through the jump until it fills the screen at the flash.
            val dive = (j / LANDING).coerceIn(0f, 1f)
            val r = min(size.width, size.height) * GLOBE_SHARE * (0.2f + 0.8f * tunnel) * (0.92f + 0.08f * lock) *
                (1f + 14f * dive.pow(2.4f))
            globe.draw(this, cx, cy, r, alpha = tunnel * fade, orbits = orbit * fade, t = t, visible = gps.visible, used = gps.used, lock = lock)
        }
        // Back home: a soft bloom at the heart while the stars slow down.
        val bloom = homecoming.value
        if (bloom > 0.01f) {
            drawRect(
                Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.45f * bloom.pow(3)),
                    0.5f to StarBlue.copy(alpha = 0.18f * bloom * bloom),
                    1f to Color.Transparent,
                    center = Offset(cx, cy),
                    radius = max(size.width, size.height) * 0.7f,
                ),
            )
        }

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
            center = Offset(size.width / 2, size.height * HEART_Y),
            style = Stroke((14f * (1f - k) + 1f) * density),
        )
    }
}

/**
 * The words under the globe while the GPS searches: what is happening, and four lights coming on
 * one by one as satellites lock (four make a position). In the demo, [gps] is simulated.
 */
@Composable
fun FlightCaption(gps: GpsInfo, demo: Boolean, modifier: Modifier = Modifier) {
    val ready = gps.used >= 4
    val halo = TextStyle(shadow = Shadow(Color.Black, blurRadius = 18f))
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val status = when {
            ready -> "Position verrouillée"
            gps.used > 0 -> "Triangulation · ${gps.used} sur 4"
            gps.visible > 0 -> "Contact des satellites…"
            else -> "Recherche du ciel…"
        }
        AnimatedContent(
            status,
            transitionSpec = {
                (slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(300)))
                    .togetherWith(slideOutVertically(tween(250)) { -it } + fadeOut(tween(200)))
            },
            label = "flightStatus",
        ) { text ->
            Text(text, color = if (ready) Green else Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, style = halo)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (i in 0 until 4) LockLight(on = i < gps.used, ready = ready)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (demo) "Trajet simulé · démo" else "${gps.visible} satellites en vue",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 14.sp,
            style = halo,
        )
    }
}

/** One of the four lock lights: it pops on with a glow, blue while searching, all green once locked. */
@Composable
private fun LockLight(on: Boolean, ready: Boolean) {
    val k by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow), label = "lockLight")
    val color = if (ready) Green else Blue
    Canvas(Modifier.size(14.dp)) {
        val r = size.minDimension / 2
        drawCircle(Color.White.copy(alpha = 0.22f), radius = r * 0.6f, style = Stroke(1.dp.toPx()))
        if (k > 0.01f) {
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.5f * k.coerceIn(0f, 1f)), Color.Transparent), center = center, radius = r * 2.2f), radius = r * 2.2f)
            drawCircle(color, radius = r * 0.6f * k)
        }
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
 * Start, a low hum looping while the GPS searches, a soft note at each satellite locked, then the
 * jump, a rising rush ending in a round deep boom and a warm chord as the map appears. The music
 * steps aside for the flight: it slides down and pauses at Start, plays again low under the boom's
 * chord and rises back to where it was.
 */
class WarpSound(context: Context) {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private var engage: AudioTrack? = null
    private var hum: AudioTrack? = null
    private var arrive: AudioTrack? = null
    private var locks: List<AudioTrack> = emptyList()

    /** Its own focus owner (the drive service has another): the music's way out and back. */
    private val music = AudioDucker(context)
    private var musicAway = false

    /** Builds the sounds off the main thread; anything asked before is silently skipped. */
    suspend fun prepare() = withContext(Dispatchers.Default) {
        runCatching {
            engage = track(engageWave())
            arrive = track(arriveWave())
            hum = track(humWave()).apply { setLoopPoints(0, HUM_SAMPLES, -1) }
            locks = LOCK_NOTES.mapIndexed { i, hz -> track(lockWave(hz, chord = i == LOCK_NOTES.lastIndex)) }
        }
    }

    fun engage() = replay(engage)

    /** Satellite [n] (1 to 4) locked: a soft round note, a step higher each time, the fourth a chord. */
    fun lock(n: Int) = replay(locks.getOrNull(n - 1))

    /** At Start: the music slides down, then pauses for the flight. */
    fun musicAway() {
        if (musicAway) return
        musicAway = true
        music.acquire(pause = true)
    }

    /** At the boom: the music plays again, low under the warm chord, then rises to where it was. */
    suspend fun musicBack() {
        if (!musicAway) return
        musicAway = false
        music.acquire()
        music.release(pause = true, holdMs = 0)
        delay(MUSIC_RISE_MS)
        music.release(holdMs = 0)
    }

    /** Flight cut short: the music comes back at once. */
    fun musicNow() {
        musicAway = false
        music.shutdown()
    }

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
        (listOfNotNull(engage, hum, arrive) + locks).forEach { runCatching { it.release() } }
        engage = null
        hum = null
        arrive = null
        locks = emptyList()
        musicNow()
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

        /** After the boom, the music stays low this long under the chord before rising. */
        const val MUSIC_RISE_MS = 1_600L

        /** E major, rising: E4, G#4, B4, then E5 with its chord. */
        val LOCK_NOTES = floatArrayOf(329.63f, 415.3f, 493.88f, 659.25f)

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

        /** A lock: a soft sine with a slow fade and a quiet octave for air; no bell, no click. */
        fun lockWave(hz: Float, chord: Boolean): ShortArray = pcm(1.1f) { t, _ ->
            val w = 2 * PI.toFloat() * t
            val env = smooth(t / 0.04f) * exp(-t / if (chord) 0.4f else 0.24f)
            var v = sin(w * hz) + 0.2f * sin(w * hz * 2f)
            if (chord) v += 0.7f * sin(w * hz * 1.2599f) + 0.55f * sin(w * hz * 1.4983f)
            0.3f * env * v * (1f - smooth((t - 0.9f) / 0.2f))
        }

        fun smooth(x: Float): Float {
            val k = x.coerceIn(0f, 1f)
            return k * k * (3 - 2 * k)
        }
    }
}

/** Sounds of the flight for this screen, built once, released with it (the music given back). */
@Composable
fun rememberWarpSound(): WarpSound {
    val context = LocalContext.current
    val sound = remember { WarpSound(context) }
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

/** A radar just passed: a band of light sweeps down the screen as under a gantry, green, or red if it would flash. */
@Composable
fun RadarPassWave(progress: Float, flash: Boolean, modifier: Modifier = Modifier) {
    if (progress <= 0f || progress >= 1f) return
    val color = if (flash) Color(0xFFFF3B30) else Green
    Canvas(modifier) {
        val y = size.height * (-0.2f + 1.4f * progress)
        val band = size.height * 0.22f
        val fade = 1f - progress * progress
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.5f to color.copy(alpha = 0.3f * fade),
                1f to Color.Transparent,
                startY = y - band,
                endY = y + band,
            ),
        )
        drawRect(
            Brush.radialGradient(
                0.6f to Color.Transparent,
                1f to color.copy(alpha = 0.3f * (1f - progress)),
                center = center,
                radius = max(size.width, size.height) * 0.75f,
            ),
        )
    }
}

/**
 * The calm sky of home behind the other screens (settings, permissions), with a faint nebula high
 * up: the whole app lives in the same space.
 */
@Composable
fun SpaceBackdrop(modifier: Modifier = Modifier) {
    val sky = remember { Stars(260, tube = false) }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) sky.step(((now - last) / 1e9f).coerceAtMost(0.05f), 0.025f)
                last = now
                frame = now
            }
        }
    }
    Canvas(modifier.background(Color.Black)) {
        val t = frame / 1e9f
        drawRect(
            Brush.radialGradient(
                0f to Blue.copy(alpha = 0.12f),
                1f to Color.Transparent,
                center = Offset(size.width * 0.15f, size.height * 0.08f),
                radius = size.maxDimension * 0.6f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                0f to Color(0xFF8E5CFF).copy(alpha = 0.08f),
                1f to Color.Transparent,
                center = Offset(size.width * 0.9f, size.height * 0.4f),
                radius = size.maxDimension * 0.5f,
            ),
        )
        sky.draw(this, size.width / 2, size.height * HEART_Y, min(size.width, size.height) * 0.42f, 0.025f, t, 0.7f, twinkle = true)
    }
}
