package zenith.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random
import kotlin.math.cos
import zenith.core.LatLon
import zenith.core.Loudness

/** Where the start of a drive stands: stars drifting at home, the tunnel while the GPS searches, the jump. */
enum class FlightPhase { IDLE, SEARCH, ARRIVE }

/** The two moments whose pictures follow their sound: the arrival and the way back. */
enum class FlightSound { ARRIVE, HOMECOMING }

private val Blue = Color(0xFF0A84FF)
private val Green = Color(0xFF30D158)
private val StarBlue = Color(0xFF9CC8FF)
private val StarRed = Color(0xFFFF6B5E)

/** Height of the tunnel's heart, as a share of the screen: the globe, the flash and the portal sit there. */
internal const val HEART_Y = 0.42f

/** Radius of the globe at the heart while the GPS searches, as a share of the screen's smaller side. */
internal const val GLOBE_SHARE = 0.15f

/**
 * Connected: just a beat (the lock-on ring, "Position verrouillée") before the jump. The calm
 * belongs to the search; once the position is there, everything goes fast.
 */
internal const val HOLD_MS = 250

/** The dive, from the first frame of the rush to the last star; the flash comes at [LANDING] of it. */
private const val DIVE_MS = 1_300
private const val LANDING = 0.66f

/** From the start of the arrival (position locked) to the flash: the boom, the map, the music back. */
internal const val JUMP_IMPACT_MS = HOLD_MS + (DIVE_MS * LANDING).toInt()

/** The way back: the scene steps back and fades into the stars over [CLOSE_MS]... */
internal const val CLOSE_MS = 500

/** ...the Earth, behind it, drifts away to its size in the tunnel... */
private const val RECEDE_MS = 1_300

/** ...then fades into the calm sky of home. */
internal const val HOMECOMING_MS = 2_000

/** A sound heard later than this after it was started is not believed (a broken timestamp). */
private const val MAX_LAG_NS = 350_000_000L

/**
 * Space behind the start of a drive, drawn frame by frame. At home, a calm sky drifts by. Once
 * started, a tube of stars forms around the viewer and rushes past, faster as the search goes on
 * and as satellites lock, rings of light marking its walls; at its end the Earth ([Globe]) turns,
 * its satellites locking one by one onto [home] (the driver's position; France until known).
 *
 * [FlightPhase.ARRIVE]: a beat ([HOLD_MS]) as the position locks with a ring of light ([onDive] at
 * its end), then the jump, fast: the stars stretch into streaks and the Earth rushes at the viewer,
 * swinging home dead center on the way, until it fills the screen; a white flash and the map opens
 * beneath it ([onLanded]), then the stars fade out ([onGone]).
 *
 * [comingHome]: a drive just ended. Behind the scene fading away, the Earth, home in front, drifts
 * back from the viewer and fades into the stars, which slow down to the calm of home. Soft, short,
 * nothing crossing the screen.
 *
 * Both follow their sound ([heardAt]: when it is first heard, Bluetooth included when Android knows),
 * so the boom lands on the flash.
 */
@Composable
fun Starfield(
    phase: FlightPhase,
    gps: GpsInfo,
    onDive: () -> Unit,
    onLanded: () -> Unit,
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
    comingHome: Boolean = false,
    home: LatLon? = null,
    heardAt: (FlightSound) -> Long? = { null },
) {
    val sky = remember { Stars(380, tube = false) }
    val tube = remember { Stars(900, tube = true) }
    val globe = remember {
        Globe().apply {
            home?.let { aimAt(it.lat, it.lon) }
            if (comingHome) face()
        }
    }
    LaunchedEffect(home) { home?.let { globe.aimAt(it.lat, it.lon) } }
    val lock by animateFloatAsState((gps.used / 4f).coerceAtMost(1f), tween(900, easing = FastOutSlowInEasing), label = "lock")
    // The tube forms around the viewer at Start and speeds up the longer the search, the more satellites locked.
    val tunnel by animateFloatAsState(if (phase == FlightPhase.IDLE) 0f else 1f, tween(1_800, easing = FastOutSlowInEasing), label = "tunnel")
    val orbit by animateFloatAsState(if (phase == FlightPhase.SEARCH) 1f else 0f, tween(900), label = "orbit")
    // Time since the arrival and since the way back began, in ms; -1 while not under way.
    var arriveMs by remember { mutableFloatStateOf(-1f) }
    var backMs by remember { mutableFloatStateOf(if (comingHome) 0f else -1f) }
    // A drive ended in the last instants of the jump, this sky still up: the way back from here.
    LaunchedEffect(comingHome) {
        if (comingHome && backMs < 0f) {
            globe.face()
            backMs = 0f
        }
    }
    val dive by rememberUpdatedState(onDive)
    val landed by rememberUpdatedState(onLanded)
    val gone by rememberUpdatedState(onGone)
    val heard by rememberUpdatedState(heardAt)
    val locked by rememberUpdatedState(gps.used)
    val inPhase by rememberUpdatedState(phase)
    var frame by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(0.035f) }
    LaunchedEffect(Unit) {
        var last = 0L
        var searchingSince = 0L
        var arriveFrom = 0L
        var backFrom = 0L
        var sent = 0
        while (true) {
            withFrameNanos { now ->
                if (inPhase == FlightPhase.SEARCH && searchingSince == 0L) searchingSince = now
                if (inPhase == FlightPhase.IDLE) searchingSince = 0L
                // The arrival's clock: from its sound once heard, never going back.
                if (inPhase == FlightPhase.ARRIVE) {
                    if (arriveFrom == 0L) arriveFrom = now
                    val anchor = heard(FlightSound.ARRIVE)?.takeIf { abs(it - arriveFrom) < 700_000_000L } ?: arriveFrom
                    arriveMs = max(arriveMs, ((now - anchor) / 1e6f).coerceAtLeast(0f))
                    if (sent == 0 && arriveMs >= HOLD_MS) sent = 1.also { dive() }
                    if (sent == 1 && arriveMs >= JUMP_IMPACT_MS) sent = 2.also { landed() }
                    if (sent == 2 && arriveMs >= HOLD_MS + DIVE_MS) sent = 3.also { gone() }
                } else {
                    arriveFrom = 0L
                    arriveMs = -1f
                    sent = 0
                }
                if (backMs >= 0f) {
                    if (backFrom == 0L) backFrom = now
                    val anchor = heard(FlightSound.HOMECOMING)?.takeIf { abs(it - backFrom) < 700_000_000L } ?: backFrom
                    backMs = max(backMs, ((now - anchor) / 1e6f).coerceAtLeast(0f))
                    if (backMs >= HOMECOMING_MS) backMs = -1f
                } else {
                    backFrom = 0L
                }
                val searched = if (searchingSince == 0L) 0f else (now - searchingSince) / 1e9f
                // Progressive: from a cruise to a race over the first seconds, a little more per satellite.
                val cruise = 0.035f + tunnel * (0.45f + 0.9f * (searched / 8f).coerceAtMost(1f) + 0.06f * locked.coerceAtMost(8))
                val a = Jump(arriveMs)
                // The rush, from the very start of the dive.
                speed = cruise + 18f * a.dive.pow(2) + Back(backMs).speed
                if (last != 0L) {
                    val dt = ((now - last) / 1e9f).coerceAtMost(0.05f)
                    sky.step(dt, speed * 0.35f + 0.02f)
                    tube.step(dt, speed)
                    val arriving = inPhase == FlightPhase.ARRIVE
                    globe.step(dt, lock, aim = arriving || backMs >= 0f || lock >= 0.98f, firm = arriving)
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
        val base = min(size.width, size.height) * GLOBE_SHARE
        val a = Jump(arriveMs)
        val b = Back(backMs)
        // After the flash, the stars give way to the map.
        val fade = if (a.dive <= LANDING) 1f else (1f - (a.dive - LANDING) / (1f - LANDING)).coerceIn(0f, 1f)
        sky.draw(this, cx, cy, scale, speed * 0.35f, t, fade * (1f - 0.5f * max(tunnel, b.tube)), twinkle = true)
        if (tunnel > 0.01f) {
            tubeRings(cx, cy, scale, t, speed, tunnel * fade)
            tube.draw(this, cx, cy, scale, speed, t, fade * tunnel, twinkle = false)
            // The Earth ahead: out of the distance as the tube forms, a little nearer as satellites
            // lock, then rushing at the viewer until it fills the screen at the flash.
            val rush = (a.dive / LANDING).coerceAtMost(1f)
            val r = base * (0.2f + 0.8f * tunnel) * (0.92f + 0.08f * lock) * (1f + 14f * rush.pow(2f))
            val flare = if (arriveMs < 0f) 0f else (arriveMs / 600f).coerceIn(0f, 1f)
            if (backMs < 0f) {
                globe.draw(this, cx, cy, r, alpha = tunnel * fade, orbits = orbit * fade, t = t, visible = gps.visible, used = gps.used, lock = if (arriveMs >= 0f) 1f else lock, flare = flare)
            }
        }
        if (backMs >= 0f) {
            // Back from a drive: the tunnel's walls drift backwards, the Earth drifts away from the
            // viewer, home in front, and fades into the stars; a soft glow at the heart while it recedes.
            tube.draw(this, cx, cy, scale, speed, t, b.tube, twinkle = false)
            if (b.glow > 0.01f) {
                drawRect(
                    Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.35f * b.glow),
                        0.4f to StarBlue.copy(alpha = 0.16f * b.glow),
                        1f to Color.Transparent,
                        center = Offset(cx, cy),
                        radius = max(size.width, size.height) * 0.7f,
                    ),
                )
            }
            globe.draw(this, cx, cy, base * b.size, alpha = b.globe, orbits = 0f, t = t, visible = 0, used = 0, lock = 1f)
        }
        // The jump's flash: white at the heart, blue at the edges, at its brightest as the map opens.
        val flash = if (arriveMs >= 0f) exp(-((a.dive - LANDING) / 0.09f).pow(2)) else 0f
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

/** Where the arrival stands at [ms] since its start (-1: not arriving): the dive, 0 to 1, after the lock-on beat. */
private class Jump(ms: Float) {
    val dive = if (ms < 0f) 0f else ((ms - HOLD_MS) / DIVE_MS).coerceIn(0f, 1f)
}

/** Where the way back stands at [ms] since the stop (-1: not under way). */
private class Back(ms: Float) {
    private val on = ms >= 0f

    /** Drifting away (0 to 1), then fading into the stars (0 to 1). */
    private val recede = if (on) (ms / RECEDE_MS).coerceIn(0f, 1f) else 0f
    private val vanish = if (on) ((ms - RECEDE_MS) / (HOMECOMING_MS - RECEDE_MS)).coerceIn(0f, 1f) else 0f

    /** The stars drift gently back toward the heart, then rest. */
    val speed = if (on) -3f * (1f - recede).pow(2) else 0f
    val tube = if (on) 0.6f * (1f - smooth(vanish)) else 0f
    val glow = if (on) 0.5f * (1f - recede).pow(2) * smooth(ms / CLOSE_MS) else 0f

    /** The Earth's radius in globe radii: near behind the scene, its size in the tunnel, then a point. */
    val size = (1f + 2.5f * (1f - recede).pow(2)) * (1f - 0.8f * smooth(vanish))
    val globe = if (on) smooth((ms - 120f) / (CLOSE_MS - 120f)) * (1f - smooth(vanish)) else 0f
}

private fun smooth(x: Float): Float {
    val k = x.coerceIn(0f, 1f)
    return k * k * (3 - 2 * k)
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
 * one by one as satellites lock (four make a position). [locked]: the airlock, the position is
 * there. In the demo, [gps] is simulated.
 */
@Composable
fun FlightCaption(gps: GpsInfo, demo: Boolean, modifier: Modifier = Modifier, locked: Boolean = false) {
    val ready = locked || gps.used >= 4
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
            for (i in 0 until 4) LockLight(on = locked || i < gps.used, ready = ready)
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

    /** [speed] below zero: backwards, the stars fall away into the heart and new ones come from beside the viewer. */
    fun step(dt: Float, speed: Float) {
        for (i in 0 until count) {
            z[i] -= speed * dt
            if (z[i] <= 0.03f) spawn(i, 1.2f) else if (z[i] > 1.25f) spawn(i, 0.05f + random.nextFloat() * 0.15f)
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
            // The trail: where the star was a moment ago; long at speed, nothing at rest, outward backwards.
            val tailDepth = (depth + speed * 0.05f).coerceIn(depth * 0.3f, 1.4f)
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
 * arrival: a deep tone swelling at the lock-on, the rush of the jump ending in a round deep boom
 * and a warm chord as the map appears. At the end of a drive, the way back: a soft breath falling
 * away and the chord of home. The music
 * steps aside for the flight: it slides down and pauses at Start, plays again low under the boom's
 * chord and rises back to where it was; it only ducks under the way back.
 */
class WarpSound(context: Context) {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private var engage: AudioTrack? = null
    private var hum: AudioTrack? = null
    private var locks: List<AudioTrack> = emptyList()

    @Volatile
    private var arrivePcm: ShortArray? = null

    @Volatile
    private var homecomingPcm: ShortArray? = null

    /** The arrival or the way back now playing, a fresh track each time so its clock starts at zero. */
    private var moment: AudioTrack? = null
    private var momentKind: FlightSound? = null
    private var momentStartNs = 0L
    private var momentLagNs: Long? = null

    /** Its own focus owner (the drive service has another): the music's way out and back. */
    private val music = AudioDucker(context)
    private val audio = context.getSystemService(AudioManager::class.java)

    /** Turned down when the media volume is high (car head unit), like the interface sounds. */
    private fun gain(): Float = runCatching {
        Loudness.uiGain(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    }.getOrDefault(1f)
    private var musicAway = false
    private var humLevel = HUM_LEVEL

    /** Builds the sounds off the main thread; anything asked before is silently skipped. */
    suspend fun prepare() = withContext(Dispatchers.Default) {
        runCatching {
            engage = track(engageWave())
            hum = track(humWave()).apply { setLoopPoints(0, HUM_SAMPLES, -1) }
            locks = LOCK_NOTES.mapIndexed { i, hz -> track(lockWave(hz, chord = i == LOCK_NOTES.lastIndex)) }
            arrivePcm = arriveWave()
            homecomingPcm = homecomingWave()
        }
    }

    fun engage() = replay(engage)

    /** Satellite [n] (1 to 4) locked: a soft round note, a step higher each time, the fourth a chord. */
    fun lock(n: Int) = replay(locks.getOrNull(n - 1))

    /** The lock-on, the jump and the boom, in one sound: the pictures follow it ([heardAt]). */
    fun arrive() = play(FlightSound.ARRIVE, arrivePcm)

    /** The way back at the end of a drive; the music ducks a moment under it. */
    fun homecoming() {
        play(FlightSound.HOMECOMING, homecomingPcm)
        music.duckFor(HOMECOMING_MS.toLong(), holdMs = 500)
    }

    /**
     * When the first sample of [kind] reaches the ear: when it was started, plus the output's delay
     * read from the track's timestamps (Bluetooth included when Android knows it) once they come.
     */
    fun heardAt(kind: FlightSound): Long? {
        val track = moment ?: return null
        if (momentKind != kind) return null
        if (momentLagNs == null) {
            val stamp = AudioTimestamp()
            if (runCatching { track.getTimestamp(stamp) }.getOrDefault(false) && stamp.framePosition > 0) {
                val first = stamp.nanoTime - stamp.framePosition * 1_000_000_000L / RATE
                momentLagNs = (first - momentStartNs).coerceIn(0L, MAX_LAG_NS)
            }
        }
        return momentStartNs + (momentLagNs ?: 0L)
    }

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
        // Cut short or not, the music always ends up back at its level.
        try {
            delay(MUSIC_RISE_MS)
        } finally {
            music.release(holdMs = 0)
        }
    }

    /** Flight cut short: the music comes back at once. */
    fun musicNow() {
        musicAway = false
        music.shutdown()
    }

    fun startHum() {
        val h = hum ?: return
        runCatching {
            humLevel = HUM_LEVEL * gain()
            h.setVolume(humLevel)
            if (h.playState != AudioTrack.PLAYSTATE_PLAYING) {
                h.preferredDevice = music.bluetoothMedia()
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
            runCatching { h.setVolume(humLevel * k / steps) }
            delay(ms / steps)
        }
        runCatching { h.pause() }
    }

    fun release() {
        (listOfNotNull(engage, hum, moment) + locks).forEach { runCatching { it.release() } }
        engage = null
        hum = null
        moment = null
        locks = emptyList()
        musicNow()
    }

    private fun play(kind: FlightSound, pcm: ShortArray?) {
        pcm ?: return
        runCatching {
            moment?.release()
            moment = null
            val track = track(pcm)
            moment = track
            momentKind = kind
            momentLagNs = null
            momentStartNs = System.nanoTime()
            track.preferredDevice = music.bluetoothMedia()
            track.setVolume(gain())
            track.play()
        }
    }

    private fun replay(track: AudioTrack?) {
        val t = track ?: return
        runCatching {
            t.stop()
            t.reloadStaticData()
            t.preferredDevice = music.bluetoothMedia()
            t.setVolume(gain())
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
        const val TWO_PI = 2 * PI.toFloat()

        /** Two seconds: every partial of the hum fits a whole number of cycles, so the loop is seamless. */
        const val HUM_SAMPLES = 2 * RATE

        /** The hum's own level, under the other flight sounds. */
        const val HUM_LEVEL = 0.55f

        /** After the boom, the music stays low this long under the chord before rising. */
        const val MUSIC_RISE_MS = 800L

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
                phase += TWO_PI * (55f + 300f * k * k) / RATE
                val env = smooth(k / 0.75f) * (1f - smooth((k - 0.75f) / 0.25f))
                env * (0.55f * sin(phase) + 0.2f * sin(phase * 2.01f) + noise * (0.15f + 0.6f * k))
            }
        }

        /** The hum of the tunnel: deep partials breathing slowly, a faint shimmer on top. */
        fun humWave(): ShortArray = pcm(HUM_SAMPLES.toFloat() / RATE) { t, _ ->
            val breath = 1f + 0.3f * sin(TWO_PI * 0.5f * t)
            val w = TWO_PI * t
            0.32f * breath * (sin(w * 55f) + 0.5f * sin(w * 110f) + 0.25f * sin(w * 165f)) + 0.03f * breath * sin(w * 880f)
        }

        /**
         * The arrival, timed on the pictures: at the lock-on a deep tone swells, rising; the rush of
         * the jump rises fast out of it; at the flash a round deep boom, the
         * air settling and a warm chord swelling under it, the whole dying away slowly: an arrival,
         * not a cut.
         */
        fun arriveWave(): ShortArray {
            val hold = HOLD_MS / 1_000f
            val impact = JUMP_IMPACT_MS / 1_000f
            val length = impact + 3.6f
            var charge = 0f
            var rush = 0f
            var boom = 0f
            return pcm(length) { t, noise ->
                var v = 0f
                if (t < impact + 0.05f) {
                    // The lock-on: from the hum's 55 Hz up an octave, swelling at once, then handing over to the rush.
                    charge += TWO_PI * (55f + 55f * smooth(t / impact)) / RATE
                    val swell = smooth(t / 0.06f) * (0.32f + 0.2f * smooth(t / hold)) * (1f - smooth((t - hold) / (impact - hold)))
                    v += swell * (sin(charge) + 0.4f * sin(charge * 2f)) + noise * 0.2f * smooth(t / hold) * (1f - smooth((t - hold) / 0.4f))
                    // The rush, starting under the lock-on, gone 50 ms after the flash.
                    val k = ((t - hold + 0.15f) / (impact - hold + 0.15f)).coerceIn(0f, 1f)
                    if (k > 0f) {
                        rush += TWO_PI * (120f + 900f * k * k * k) / RATE
                        val out = if (t <= impact) 1f else 1f - (t - impact) / 0.05f
                        v += out * k.pow(1.5f) * (0.35f * sin(rush) + noise * 0.9f)
                    }
                }
                if (t >= impact) {
                    val d = t - impact
                    // Pitch dropping from 70 to 35 Hz as it decays: the body of the boom.
                    boom += TWO_PI * (35f + 35f * exp(-d / 0.25f)) / RATE
                    // A round, deep landing (soft attack), no bell: the air settles, a warm chord carries on.
                    v += 1.0f * smooth(d / 0.03f) * exp(-d / 0.9f) * sin(boom)
                    v += noise * 0.9f * smooth(d / 0.03f) * exp(-d / 0.35f)
                    val w = TWO_PI * d
                    val pad = 0.14f * smooth(d / 0.5f) * exp(-d / 1.8f)
                    v += pad * (sin(w * 110f) + sin(w * 138.6f) + sin(w * 164.8f) + 0.5f * sin(w * 220f))
                }
                // Last half second: down to silence, never a click.
                v * (1f - smooth((t - (length - 0.5f)) / 0.5f))
            }
        }

        /**
         * The way back: a soft breath of air drifting down with the Earth and the warm chord of
         * home settling in; no impact.
         */
        fun homecomingWave(): ShortArray {
            val recede = RECEDE_MS / 1_000f
            val length = HOMECOMING_MS / 1_000f + 0.8f
            var sweep = 0f
            return pcm(length) { t, noise ->
                val u = (t / recede).coerceIn(0f, 1f)
                sweep += TWO_PI * (440f - 330f * smooth(u)) / RATE
                val air = smooth(t / 0.12f) * (1f - smooth(u))
                var v = air * (0.18f * sin(sweep) + 0.45f * noise)
                val c = t - 0.35f
                if (c > 0f) {
                    val w = TWO_PI * c
                    val pad = 0.11f * smooth(c / 0.4f) * exp(-c / 1.2f)
                    v += pad * (sin(w * 164.81f) + sin(w * 207.65f) + sin(w * 246.94f) + 0.5f * sin(w * 329.63f))
                }
                v * (1f - smooth((t - (length - 0.6f)) / 0.6f))
            }
        }

        /** A lock: a soft sine with a slow fade and a quiet octave for air; no bell, no click. */
        fun lockWave(hz: Float, chord: Boolean): ShortArray = pcm(1.1f) { t, _ ->
            val w = TWO_PI * t
            val env = smooth(t / 0.04f) * exp(-t / if (chord) 0.4f else 0.24f)
            var v = sin(w * hz) + 0.2f * sin(w * hz * 2f)
            if (chord) v += 0.7f * sin(w * hz * 1.2599f) + 0.55f * sin(w * hz * 1.4983f)
            0.3f * env * v * (1f - smooth((t - 0.9f) / 0.2f))
        }
    }
}

/**
 * Sounds of the flight for this screen, built once, released with it. The music is given back as
 * soon as the screen goes off or away: the flight is not watched, nothing to step aside for.
 */
@Composable
fun rememberWarpSound(): WarpSound {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sound = remember { WarpSound(context) }
    LaunchedEffect(sound) { sound.prepare() }
    DisposableEffect(sound, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) sound.musicNow() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            sound.release()
        }
    }
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
