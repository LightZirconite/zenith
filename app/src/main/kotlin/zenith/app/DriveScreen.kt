package zenith.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import zenith.core.AlertEvent
import zenith.core.Cockpit
import zenith.core.Daylight
import zenith.core.ControlZone
import zenith.core.DriveSnapshot
import zenith.core.LatLon
import zenith.core.LimitSource
import zenith.core.Radar
import zenith.core.TripStats
import zenith.core.TripSummary
import kotlin.math.roundToInt

private val Bg = Color(0xFF000000)
private val Red = Color(0xFFFF3B30)
private val Orange = Color(0xFFFF9F0A)
private val Green = Color(0xFF30D158)
private val Dim = Color(0xFF8E8E93)

/**
 * Shows the content through a circle around the tunnel's heart ([HEART_Y] down the screen), with a
 * soft rim: 0 nothing, 1 everything, no mask at all.
 */
private fun Modifier.portalReveal(progress: Float): Modifier {
    if (progress >= 1f) return this
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val reach = kotlin.math.hypot(size.width, size.height)
        val r = (progress * 1.15f).coerceIn(0.001f, 1.15f)
        drawRect(
            Brush.radialGradient(
                0f to Color.Black,
                (r * 0.82f) to Color.Black,
                r to Color.Transparent,
                center = Offset(size.width / 2, size.height * HEART_Y),
                radius = reach,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
}

/** What the middle of the drive screen shows: home, the flight while the GPS searches, the speed. */
private enum class HomeView { IDLE, FLIGHT, DRIVE }

/** The flight lasts at least this long, even when the position comes at once (demo, warm GPS). */
private const val MIN_FLIGHT_MS = 3_500L

/** The music steps aside for the flight, but not longer than this if the GPS takes its time. */
private const val MUSIC_AWAY_MAX_MS = 12_000L

/** A position without a trusted speed (indoors) still lands after this wait. */
private const val WEAK_FIX_WAIT_MS = 15_000L

/** Position locked, the roads around it still loading (slow network): the flight lands anyway after this. */
private const val ROADS_WAIT_MS = 20_000L

/** Home's buttons rise back into place after a flight cut short, and sink away at Start. */
private val BottomIn = fadeIn(tween(500, delayMillis = 400)) +
    slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)) { it }

/** After a drive, once the Earth has drifted away into the stars. */
private val BottomInLate = fadeIn(tween(500, delayMillis = 1_100)) +
    slideInVertically(tween(700, delayMillis = 1_000, easing = EaseOutBack)) { it }
private val BottomOut = fadeOut(tween(200)) + slideOutVertically(tween(300)) { it / 2 }

/** The drive's cards step back and fade with the scene. */
private val DriveOut = fadeOut(tween(220)) + scaleOut(tween(320), targetScale = 0.9f)

/** Chips pop in with a spring and shrink away. */
private val ChipIn = fadeIn(tween(300)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), initialScale = 0.7f)
private val ChipOut = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.85f)

/** The map's height, as a share of the screen: lower than where it visibly ends, for its faint tail. */
private const val MAP_HEIGHT = 0.8f

/** Alone, the map fades into the black where it always did (62 % of the screen), black below. */
private val MapAlone = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.45f),
    0.093f to Color.Transparent,
    0.326f to Color.Transparent,
    0.48f to Color.Black.copy(alpha = 0.35f),
    0.62f to Color.Black.copy(alpha = 0.75f),
    0.775f to Color.Black,
    1f to Color.Black,
)

/** With music: darker only under the chips at the top, a light veil for the speed; the mask does the rest. */
private val MapOverMusic = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.45f),
    0.093f to Color.Transparent,
    0.35f to Color.Black.copy(alpha = 0.1f),
    1f to Color.Black.copy(alpha = 0.3f),
)

/** No mask: the map whole (no music). Same number of stops as the others, to blend between them. */
private val MapWhole = listOf(0f to 1f, 0.35f to 1f, 0.45f to 1f, 0.575f to 1f, 0.7f to 1f, 0.8f to 1f, 0.925f to 1f, 1f to 1f)

/**
 * With music, the map's opacity along its height: the crossfade with the Canvas sits where the old
 * fade was (half and half near the middle of the screen), then a long faint tail lower down.
 */
private val MapOverCanvas = listOf(0f to 1f, 0.35f to 1f, 0.45f to 0.85f, 0.575f to 0.55f, 0.7f to 0.3f, 0.8f to 0.15f, 0.925f to 0.05f, 1f to 0f)

/** Music mode: the map keeps only the top of the screen, the Canvas takes the rest. */
private val MapInMusicMode = listOf(0f to 1f, 0.15f to 1f, 0.25f to 0.6f, 0.33f to 0.35f, 0.42f to 0.18f, 0.52f to 0.08f, 0.65f to 0.02f, 1f to 0f)

/** Landscape: the share of the width for the map (and home) on the left; the speed and buttons take the rest. */
private const val LAND_SPLIT = 0.56f

/** Landscape: the map's width, as a share of the screen; it melts away under the right column. */
private const val LAND_MAP_WIDTH = 0.66f

/** Landscape: the Canvas's width from the right edge, as a share of the screen; wider in music mode. */
private const val LAND_CANVAS_WIDTH = 0.62f
private const val LAND_CANVAS_WIDE = 0.85f

/** Landscape, the map's opacity across its width: alone, it fades into the black under the speed. */
private val LandMapAlone = listOf(0f to 1f, 0.62f to 1f, 0.78f to 0.65f, 0.9f to 0.25f, 1f to 0f)

/** Landscape with music: the crossfade with the Canvas starts a little earlier. */
private val LandMapOverCanvas = listOf(0f to 1f, 0.52f to 1f, 0.7f to 0.55f, 0.86f to 0.18f, 1f to 0f)

/** Landscape music mode: the map keeps the left, the Canvas comes in from the right. */
private val LandMapInMusicMode = listOf(0f to 1f, 0.32f to 1f, 0.52f to 0.45f, 0.72f to 0.12f, 1f to 0f)

/** Landscape: a darker band only under the chips at the top. */
private val LandMapShade = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.45f),
    0.14f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.2f),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DriveScreen(
    running: Boolean,
    snapshot: DriveSnapshot?,
    radars: List<Radar>,
    zones: List<ControlZone> = emptyList(),
    gps: GpsInfo,
    data: DataStatus,
    update: UpdateState,
    canInstall: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onInstallUpdate: () -> Unit,
    navApps: List<NavApp> = emptyList(),
    onOpenNavApp: (NavApp) -> Unit = {},
    demo: Boolean = false,
    onDemo: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /** Track playing in Spotify or another player (notification access granted). */
    media: MediaInfo? = null,
    /** Spotify Canvas behind the music card (setting). */
    mediaCanvas: Boolean = true,
    onMediaOpen: () -> Unit = {},
    onMediaLike: () -> Unit = {},
    onMediaPrevious: () -> Unit = {},
    onMediaPlayPause: () -> Unit = {},
    onMediaNext: () -> Unit = {},
    onMediaSeek: (Long) -> Unit = {},
    /** The music's room chosen by a tap on the music card, until the speed agrees ([Cockpit]). */
    musicChoice: Boolean? = null,
    onMusicChoice: (Boolean) -> Unit = {},
    /** The last crash's report, kept until shared or put away. */
    crashReport: String? = null,
    onShareCrash: () -> Unit = {},
) {
    // The start is a flight through space: stars at home, a tunnel while the GPS searches, a jump at
    // the first good position, and the map comes in at the flash. Opened mid-drive: straight to the map.
    // Opened while a drive was already running (back from Waze, the small window): no flight. A
    // drive started from this screen always flies, even when its first position comes before the
    // screen sees it running (the demo's comes within milliseconds).
    var openedMidDrive by remember { mutableStateOf(running) }
    LaunchedEffect(running) { if (!running) openedMidDrive = false }
    var landed by remember(running) { mutableStateOf(running && openedMidDrive && snapshot?.speedKmh != null) }
    var flightOver by remember(running) { mutableStateOf(landed) }
    // The jump itself has begun: the flight's words rush past.
    var diving by remember(running) { mutableStateOf(false) }
    var minFlightDone by remember(running) { mutableStateOf(false) }
    var weakFixWaited by remember(running) { mutableStateOf(false) }
    val hasFix = snapshot != null
    LaunchedEffect(running) {
        if (running) {
            delay(MIN_FLIGHT_MS)
            minFlightDone = true
        }
    }
    // Indoors or in a deep street the speed may stay unknown: the map comes anyway after a while.
    LaunchedEffect(running, hasFix) {
        if (running && hasFix) {
            delay(WEAK_FIX_WAIT_MS)
            weakFixWaited = true
        }
    }
    val fixGood = hasFix && (snapshot?.speedKmh != null || weakFixWaited || demo)
    // Locked on: the flight stays in the tunnel while the roads around load, so the map lands
    // whole (speed limits and radars on the road); offline, it does not wait forever.
    var roadsWaited by remember(running) { mutableStateOf(false) }
    LaunchedEffect(running, fixGood) {
        if (running && fixGood) {
            delay(ROADS_WAIT_MS)
            roadsWaited = true
        }
    }
    val roadsOk = demo || data.roadsReady || roadsWaited
    val phase = when {
        !running -> FlightPhase.IDLE
        landed || (fixGood && roadsOk && minFlightDone) -> FlightPhase.ARRIVE
        else -> FlightPhase.SEARCH
    }
    // The demo has no GPS: its satellites lock by themselves during the flight.
    var demoLocked by remember(running) { mutableIntStateOf(0) }
    LaunchedEffect(running, demo) {
        while (running && demo && demoLocked < 4) {
            delay(650)
            demoLocked++
        }
    }
    val sky = if (demo) GpsInfo(used = demoLocked, visible = 9) else gps
    // A drive that had reached the map just ended (seen at the change itself): the way back, the
    // arrival backwards. A flight cut short only lets the tunnel slow down.
    val wasLanded = remember { booleanArrayOf(false) }
    val comingHome = remember(running) { !running && wasLanded[0] }
    wasLanded[0] = running && landed
    // Where the driver is, kept after the drive: the globe dives onto it, and the way back leaves from it.
    var home by remember { mutableStateOf<LatLon?>(null) }
    LaunchedEffect(snapshot?.position) { snapshot?.position?.let { home = it } }
    val sound = rememberWarpSound()
    val soundScope = rememberCoroutineScope()
    val hostView = LocalView.current
    var heardPhase by remember { mutableStateOf<FlightPhase?>(null) }
    LaunchedEffect(phase, flightOver) {
        val before = heardPhase
        heardPhase = phase
        when {
            phase == FlightPhase.SEARCH && !flightOver -> {
                if (before == FlightPhase.IDLE) {
                    sound.musicAway()
                    sound.engage()
                    delay(1_400)
                }
                sound.startHum()
            }
            phase == FlightPhase.ARRIVE && before == FlightPhase.SEARCH -> {
                // The lock-on, the jump and the boom are one sound, the pictures follow it; the
                // hum dies away under it.
                sound.arrive()
                sound.fadeHum(400)
            }
            else -> {
                if (phase == FlightPhase.IDLE) {
                    sound.musicNow()
                    if (comingHome) sound.homecoming()
                }
                sound.fadeHum()
            }
        }
    }
    // The way back takes the whole screen too: the status bar comes back with home.
    var homeBound by remember { mutableStateOf(false) }
    LaunchedEffect(comingHome) {
        if (!comingHome) return@LaunchedEffect
        homeBound = true
        delay(HOMECOMING_MS.toLong())
        homeBound = false
    }
    // A long search (indoors, cold GPS): the music does not wait for the jump.
    LaunchedEffect(phase) {
        if (phase != FlightPhase.SEARCH) return@LaunchedEffect
        delay(MUSIC_AWAY_MAX_MS)
        soundScope.launch { sound.musicBack() }
    }
    // Each satellite locked during the search: a soft note, higher each time.
    var heardLocks by remember(running) { mutableIntStateOf(0) }
    LaunchedEffect(sky.used, phase) {
        val n = sky.used.coerceAtMost(4)
        if (phase == FlightPhase.SEARCH && n > heardLocks) {
            heardLocks = n
            sound.lock(n)
        }
    }

    val showMap = running && hasFix && landed
    // Music mode: slow or stopped, the Canvas rises up the screen, more the slower; from town speeds
    // the map has it. A tap on the music card decides instead until the speed agrees; an alert
    // always brings the map back.
    val alerting = snapshot?.alerts?.isNotEmpty() == true || snapshot?.section != null
    val focus by animateFloatAsState(
        if (media == null) 0f else Cockpit.music(snapshot?.speedKmh, musicChoice, alerting),
        spring(dampingRatio = 1f, stiffness = Spring.StiffnessVeryLow),
        label = "musicMode",
    )
    // On the road the buttons step back (smaller, the shortcuts folded away): nothing to touch by
    // accident, more room for the road; slow or stopped, they come back.
    var cruising by remember(running) { mutableStateOf(false) }
    LaunchedEffect(snapshot?.speedKmh) { cruising = Cockpit.cruising(snapshot?.speedKmh, cruising) }
    // Landscape (phone in a holder across): map on the left, speed and buttons in a column on the right.
    val screen = LocalConfiguration.current
    val landscape = screen.screenWidthDp > screen.screenHeightDp
    // A tablet or car screen across has the height for the full-size speed; a phone across does not.
    val tall = screen.screenHeightDp >= 560
    // The flight takes the whole screen: the status bar (time, battery) folds away until the map is there.
    // The island at the top: the time and battery would sit over it, they step aside while it speaks.
    var islandUp by remember { mutableStateOf(false) }
    ImmersiveBars(hidden = (running && !flightOver) || homeBound || (running && islandUp))
    val withMusic by animateFloatAsState(if (media != null) 1f else 0f, tween(900), label = "withMusic")
    // The arrival: the whole scene (map and Canvas, loaded during the flight) opens like a portal
    // from the heart of the tunnel at the flash, rushing in from a zoom, with a shock ring. At the
    // stop it simply steps back and fades into the stars.
    val portal = remember { Animatable(if (landed) 1f else 0f) }
    var sceneUp by remember { mutableStateOf(running) }
    LaunchedEffect(running, showMap) {
        when {
            showMap -> {
                sceneUp = true
                portal.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
            }
            running -> {
                sceneUp = true
                portal.snapTo(0f)
            }
            portal.value > 0f -> {
                portal.animateTo(0f, tween(CLOSE_MS, easing = FastOutSlowInEasing))
                sceneUp = false
            }
            else -> sceneUp = false
        }
    }
    // Each radar (or section) passed: a band of light sweeps down the screen, as under a gantry.
    var passes by remember { mutableIntStateOf(0) }
    var passFlash by remember { mutableStateOf(false) }
    var passedAtMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(snapshot) {
        val flash = snapshot?.events?.firstNotNullOfOrNull { event ->
            when (event) {
                is AlertEvent.Passed -> event.wouldFlash
                is AlertEvent.SectionEnded -> event.wouldFlash
                else -> null
            }
        } ?: return@LaunchedEffect
        passFlash = flash
        passedAtMs = System.currentTimeMillis()
        passes++
    }
    val pass = remember { Animatable(1f) }
    LaunchedEffect(passes) {
        if (passes > 0) {
            pass.snapTo(0f)
            pass.animateTo(1f, tween(1_200, easing = LinearOutSlowInEasing))
        }
    }
    // At night the map and the Canvas dim a little more ([Daylight]), checked every minute.
    var night by remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        while (running) {
            DriveState.snapshot.value?.position?.let { night = Daylight.isNight(it, System.currentTimeMillis()) }
            delay(60_000)
        }
    }
    val nightDim by animateFloatAsState(if (night) Daylight.NIGHT_DIM else 1f, tween(2_000), label = "night")
    val lastTrip by DriveState.lastTrip.collectAsState()
    Box(Modifier.fillMaxSize().background(Bg)) {
        AnimatedVisibility(
            visible = !flightOver,
            // The way back starts under the fading scene at once.
            enter = if (comingHome) EnterTransition.None else fadeIn(tween(800)),
            exit = fadeOut(tween(200)),
        ) {
            Starfield(
                phase,
                sky,
                onDive = { diving = true },
                onLanded = {
                    landed = true
                    // At the boom: the music comes back, low under the chord, then rises.
                    soundScope.launch { sound.musicBack() }
                },
                onGone = { flightOver = true },
                modifier = Modifier.fillMaxSize(),
                comingHome = comingHome,
                home = home,
                heardAt = sound::heardAt,
            )
        }
        if (running || sceneUp) {
            Box(
                Modifier.fillMaxSize().portalReveal(if (running) portal.value else 1f).graphicsLayer {
                    val k = portal.value
                    // Opening: a portal from the heart. Leaving: the whole scene steps back and fades.
                    alpha = if (running) (if (k <= 0f) 0f else 1f) else k
                    val scale = if (running) 1.35f - 0.35f * k else 0.88f + 0.12f * k
                    scaleX = scale
                    scaleY = scale
                },
            ) {
                // Music ambience from the bottom: the Spotify Canvas (else the cover, blurred), under
                // the map, which melts into it. Built at Start like the map: the Canvas downloads
                // during the flight and is there at the arrival.
                media?.let {
                    MusicBackdrop(
                        it,
                        canvas = mediaCanvas,
                        // An alert on screen: the music steps back.
                        calm = snapshot?.alerts?.isNotEmpty() == true,
                        focus = focus,
                        // Landscape: the right part only (the 9:16 video less cropped), a little wider
                        // in music mode; its fade is the map's in reverse, so the two always add up.
                        modifier = if (landscape) {
                            Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(LAND_CANVAS_WIDTH + (LAND_CANVAS_WIDE - LAND_CANVAS_WIDTH) * focus).alpha(nightDim)
                        } else {
                            Modifier.fillMaxSize().alpha(nightDim)
                        },
                        fromRight = landscape,
                    )
                }
                // Tilted map over the top of the screen. It reaches low: alone, it fades into the black
                // at the same place as ever; with music, it melts into the Canvas, a few of its lines
                // still faint over it lower down.
                DriveMap(
                    snapshot,
                    radars,
                    zones,
                    Modifier.alpha(nightDim).then(when {
                        // Landscape: the whole height on the left, melting away under the right column.
                        landscape -> Modifier.fillMaxHeight().fillMaxWidth(LAND_MAP_WIDTH)
                            .horizontalFade(blendStops(LandMapAlone, blendStops(LandMapOverCanvas, LandMapInMusicMode, focus), withMusic))
                        withMusic > 0f -> Modifier.fillMaxWidth().fillMaxHeight(MAP_HEIGHT)
                            .verticalFade(blendStops(MapWhole, blendStops(MapOverCanvas, MapInMusicMode, focus), withMusic))
                        else -> Modifier.fillMaxWidth().fillMaxHeight(MAP_HEIGHT)
                    }),
                    // The car where it always was on screen, the map now taller; in landscape a
                    // little low, to see far ahead in the short height.
                    carAt = if (landscape) 0.62 else 0.28 / MAP_HEIGHT,
                    overlay = when {
                        landscape -> LandMapShade
                        media != null -> MapOverMusic
                        else -> MapAlone
                    },
                )
            }
            if (running) ShockRing(portal.value, modifier = Modifier.fillMaxSize())
        }
        // The flight's words, under the globe at the tunnel's heart; "Position verrouillée" a beat,
        // then they rush past the viewer as the jump begins.
        AnimatedVisibility(
            visible = phase == FlightPhase.SEARCH || (phase == FlightPhase.ARRIVE && !diving && !landed),
            enter = fadeIn(tween(700, delayMillis = 900)) +
                slideInVertically(tween(900, delayMillis = 900, easing = FastOutSlowInEasing)) { it / 4 },
            exit = fadeOut(tween(300)) + scaleOut(tween(400), targetScale = 1.8f),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val globe = minOf(maxWidth, maxHeight) * GLOBE_SHARE
                FlightCaption(
                    sky,
                    demo,
                    Modifier.align(Alignment.TopCenter).padding(top = maxHeight * HEART_Y + globe * 1.75f + 16.dp),
                    locked = phase == FlightPhase.ARRIVE,
                    roadsLoading = fixGood && !roadsOk,
                )
            }
        }
        // Too fast near a radar: the edges of the screen pulse red.
        DangerGlow(on = landed && snapshot?.alerts?.firstOrNull()?.overLimit == true, modifier = Modifier.fillMaxSize())
        RadarPassWave(pass.value, passFlash, Modifier.fillMaxSize())
        // Home, flight and speed swap with a zoom: the flight's caption rushes past the viewer as the
        // speed arrives. A little lower than the middle while driving, so the car arrow on the map stays clear.
        val view = when {
            !running -> HomeView.IDLE
            !landed -> HomeView.FLIGHT
            else -> HomeView.DRIVE
        }
        val homeView: @Composable () -> Unit = {
            AnimatedContent(
                view,
                transitionSpec = {
                    when {
                        // Start: home rushes past the viewer as the tunnel forms.
                        initialState == HomeView.IDLE && targetState == HomeView.FLIGHT ->
                            EnterTransition.None
                                .togetherWith(fadeOut(tween(450)) + scaleOut(tween(600, easing = FastOutSlowInEasing), targetScale = 2.4f))
                        initialState == HomeView.FLIGHT && targetState == HomeView.DRIVE ->
                            (fadeIn(tween(250, delayMillis = 50)) + scaleIn(spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow), initialScale = 1.5f))
                                .togetherWith(fadeOut(tween(300)) + scaleOut(tween(350), targetScale = 2.6f))
                        // Back from a drive: the speed steps back with the scene; home comes out of
                        // the distance as the Earth drifts away.
                        // The take-off backwards: home comes back from the viewer's side, the way it
                        // left, and lands with a little give.
                        initialState == HomeView.DRIVE && targetState == HomeView.IDLE ->
                            (fadeIn(tween(350, delayMillis = 950)) + scaleIn(tween(950, delayMillis = 900, easing = EaseOutBack), initialScale = 2.4f))
                                .togetherWith(fadeOut(tween(250)) + scaleOut(tween(400), targetScale = 0.85f))
                        // A flight cut short: home comes out of the distance as the stars slow down.
                        targetState == HomeView.IDLE ->
                            (fadeIn(tween(700, delayMillis = 350)) + scaleIn(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessVeryLow), initialScale = 0.55f))
                                .togetherWith(fadeOut(tween(300)) + scaleOut(tween(500), targetScale = 0.7f))
                        else ->
                            (fadeIn(tween(500, delayMillis = 150)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow), initialScale = 0.9f))
                                .togetherWith(fadeOut(tween(250)) + scaleOut(tween(250), targetScale = 1.05f))
                    }.using(SizeTransform(clip = false))
                },
                contentAlignment = Alignment.Center,
                label = "home",
            ) { shown ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when (shown) {
                        HomeView.IDLE -> Idle(data) {
                            UiSound.play(UiSound.Kind.SUCCESS, hostView, long = true)
                            onDemo()
                        }
                        // The flight's words sit under the globe, drawn over the whole screen.
                        HomeView.FLIGHT -> Spacer(Modifier.height(1.dp))
                        // In landscape the speed has its own column, beside the map.
                        HomeView.DRIVE -> if (landscape) Spacer(Modifier.height(1.dp)) else Dashboard(snapshot, Modifier.padding(top = if (showMap) 72.dp else 0.dp))
                    }
                }
            }
        }
        val musicCard: @Composable ColumnScope.() -> Unit = {
            // After the arrival, the rest settles in one after the other, from the speed down to the music.
            AnimatedVisibility(
                visible = running && landed && media != null,
                enter = fadeIn(tween(400, delayMillis = 200)) + slideInVertically(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { it / 2 },
                exit = DriveOut,
            ) {
                media?.let {
                    MediaBar(
                        it,
                        onTap = {
                            val big = Cockpit.toggle(focus)
                            UiSound.play(if (big) UiSound.Kind.MUSIC_UP else UiSound.Kind.MUSIC_DOWN, hostView)
                            onMusicChoice(big)
                        },
                        onOpen = onMediaOpen,
                        onLike = onMediaLike,
                        onPrevious = onMediaPrevious,
                        onPlayPause = onMediaPlayPause,
                        onNext = onMediaNext,
                        onSeek = onMediaSeek,
                    )
                }
            }
        }
        val buttons: @Composable ColumnScope.() -> Unit = {
            if (!landscape) {
                // One row, one height, words only: the first app, Stop, the second app. On the road
                // the apps fold away and Stop steps back; at home, Start alone across.
                val shortcuts = running && landed && navApps.isNotEmpty() && !cruising
                val rowHeight by animateDpAsState(
                    when {
                        !running -> 58.dp
                        cruising -> 44.dp
                        else -> 52.dp
                    },
                    spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow),
                    label = "buttonRow",
                )
                Row(Modifier.fillMaxWidth().height(rowHeight), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    navApps.getOrNull(0)?.let { app ->
                        AnimatedVisibility(
                            visible = shortcuts,
                            enter = fadeIn(tween(400, delayMillis = 350)) + expandHorizontally(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)),
                            exit = fadeOut(tween(200)) + shrinkHorizontally(tween(350)),
                        ) { NavAppButton(app, onOpenNavApp, Modifier.width(96.dp).fillMaxHeight()) }
                    }
                    StartStop(running, onStart, onStop, compact = running && cruising, modifier = Modifier.weight(1f).fillMaxHeight(), fixedHeight = true)
                    navApps.getOrNull(1)?.let { app ->
                        AnimatedVisibility(
                            visible = shortcuts,
                            enter = fadeIn(tween(400, delayMillis = 450)) + expandHorizontally(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), expandFrom = Alignment.Start),
                            exit = fadeOut(tween(200)) + shrinkHorizontally(tween(350), shrinkTowards = Alignment.Start),
                        ) { NavAppButton(app, onOpenNavApp, Modifier.width(96.dp).fillMaxHeight()) }
                    }
                }
            } else {
                // Landscape: Stop at the bottom of the right column, the apps level with it on the left.
                val rowHeight by animateDpAsState(
                    when {
                        !running -> 58.dp
                        cruising -> 44.dp
                        else -> 52.dp
                    },
                    spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow),
                    label = "landButton",
                )
                StartStop(running, onStart, onStop, compact = running && cruising, modifier = Modifier.fillMaxWidth().height(rowHeight), fixedHeight = true)
            }
            // The map's credits, required, in one short line (the full ones are in the settings).
            AnimatedVisibility(visible = !running, enter = if (comingHome) BottomInLate else BottomIn, exit = BottomOut) {
                Text(
                    "© OpenStreetMap · OpenFreeMap",
                    color = Dim.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                )
            }
        }
        Column(
            // The status bar's room is kept while it is folded away for the flight: nothing jumps.
            // Upright on a tablet: one column in the middle, not buttons stretched across the screen.
            modifier = Modifier.align(Alignment.TopCenter).fillMaxHeight()
                .then(if (landscape) Modifier.fillMaxWidth() else Modifier.widthIn(max = PAGE_MAX_WIDTH).fillMaxWidth())
                .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.statusBarsIgnoringVisibility))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // At home the settings button sits at the top right: the chips leave it room.
            Box(Modifier.padding(end = if (running) 0.dp else 56.dp)) { StatusRow(running && landed, gps, data, demo, snapshot) }
            UpdateBanner(update, canInstall, onInstallUpdate)
            CrashBanner(visible = !running && crashReport != null, onShareCrash)
            if (!landscape) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { homeView() }
                Spacer(Modifier.height(12.dp))
                TripCard(lastTrip.takeIf { !running }, comingHome)
                musicCard()
                buttons()
            } else {
                // Landscape: the map (or home) on the left with the alerts under it, the speed and
                // the buttons in a column on the right, all within the screen's short height.
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.weight(LAND_SPLIT).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) { homeView() }
                        // Waze and Maps at the bottom on the left, on the same line and height as Stop.
                        AnimatedVisibility(
                            visible = running && landed && navApps.isNotEmpty() && !cruising,
                            modifier = Modifier.align(Alignment.Start),
                            enter = fadeIn(tween(400, delayMillis = 350)) + slideInVertically(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { it / 2 },
                            exit = fadeOut(tween(250)) + slideOutVertically(tween(300)) { it / 2 },
                        ) {
                            Row(Modifier.height(52.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                for (app in navApps) NavAppButton(app, onOpenNavApp, Modifier.width(110.dp).fillMaxHeight())
                            }
                        }
                    }
                    Spacer(Modifier.width(20.dp))
                    Column(
                        // A wide car screen: the column keeps a sensible width, centered in its room.
                        // As upright: the speed in the middle of the room left, the music, then the
                        // buttons resting at the bottom, level with the alerts on the left.
                        Modifier.weight(1f - LAND_SPLIT).fillMaxHeight().wrapContentWidth().widthIn(max = 520.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AnimatedVisibility(
                                visible = view == HomeView.DRIVE,
                                enter = fadeIn(tween(250, delayMillis = 50)) + scaleIn(spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow), initialScale = 1.5f),
                                exit = fadeOut(tween(250)) + scaleOut(tween(400), targetScale = 0.85f),
                            ) {
                                Dashboard(snapshot, compact = !tall)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        TripCard(lastTrip.takeIf { !running }, comingHome)
                        musicCard()
                        buttons()
                    }
                }
            }
        }
        // Radars, sections and passes: the island out of the camera, over everything.
        if (running && landed) AlertIsland(snapshot, passedAtMs, passFlash, landscape, Modifier.fillMaxSize(), onShown = { islandUp = it })
        // Home: the settings, a round glass button at the top right.
        AnimatedVisibility(
            visible = !running,
            modifier = Modifier.align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.statusBarsIgnoringVisibility))
                .padding(top = 8.dp, end = 20.dp),
            enter = if (comingHome) BottomInLate else BottomIn,
            exit = fadeOut(tween(200)) + scaleOut(tween(250), targetScale = 0.8f),
        ) {
            Box(
                Modifier.size(44.dp).tap(shape = CircleShape, sound = UiSound.Kind.OPEN, lift = 1.12f, onClick = onOpenSettings).glass(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Glyphs.gear, contentDescription = "Réglages", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** GPS and data chips; they appear while something is pending and fade once all is fine. */
@Composable
private fun StatusRow(
    running: Boolean,
    gps: GpsInfo,
    data: DataStatus,
    demo: Boolean,
    snapshot: DriveSnapshot?,
) {
    val estimated = snapshot?.estimated == true
    val gpsGood = (gps.accuracyM ?: 99.0) <= 15
    var showGps by remember { mutableStateOf(true) }
    LaunchedEffect(gpsGood, running) {
        if (gpsGood) {
            delay(3_000)
            showGps = false
        } else {
            showGps = true
        }
    }
    Row(Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(visible = running && demo, enter = ChipIn, exit = ChipOut) {
            Chip("Démo · trajet simulé", Color(0xFF0A84FF))
        }
        AnimatedVisibility(visible = running && !demo && estimated, enter = ChipIn, exit = ChipOut) {
            Chip("Tunnel · position estimée", Orange)
        }
        // Control itinerary: a movable radar may be anywhere along it.
        val zone = snapshot?.zone
        AnimatedVisibility(visible = running && zone != null && !estimated, enter = ChipIn, exit = ChipOut) {
            Chip("Itinéraire de contrôle · ${((zone?.remainingM ?: 0.0) / 1_000).roundToInt().coerceAtLeast(1)} km", Orange)
        }
        AnimatedVisibility(visible = running && !demo && !estimated && zone == null && showGps, enter = ChipIn, exit = ChipOut) {
            val text = when {
                gps.accuracyM == null -> "Satellites ${gps.used}/${gps.visible} · recherche"
                gpsGood -> "GPS ±${gps.accuracyM.roundToInt()} m · ${gps.used} sat."
                else -> "GPS faible ±${gps.accuracyM.roundToInt()} m · ${gps.used}/${gps.visible}"
            }
            Chip(text, if (gpsGood) Green else Orange)
        }
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(visible = data.loading || data.error != null, enter = ChipIn, exit = ChipOut) {
            Chip(data.error ?: "Chargement des routes…", if (data.error != null) Orange else Dim)
        }
    }
}

@Composable
private fun Chip(text: String, dot: Color, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.then(if (onClick != null) Modifier.tap(shape = RoundedCornerShape(50), onClick = onClick) else Modifier).glass(RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontSize = 13.sp)
    }
}

/** After a crash, at home: one tap shares what happened (the driver decides where it goes). */
@Composable
private fun CrashBanner(visible: Boolean, onShare: () -> Unit) {
    AnimatedVisibility(visible = visible) {
        Text(
            "Zenith s'est fermé la dernière fois · toucher pour envoyer le rapport",
            color = Color.White,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                .tap(shape = RoundedCornerShape(12.dp), sound = UiSound.Kind.OPEN, lift = 1.03f, onClick = onShare)
                .glass(RoundedCornerShape(12.dp)).padding(12.dp),
        )
    }
}

@Composable
private fun UpdateBanner(update: UpdateState, canInstall: Boolean, onInstall: () -> Unit) {
    val text = when (update) {
        is UpdateState.Downloading -> "Téléchargement de la version ${update.version}… ${(update.progress * 100).roundToInt()} %"
        is UpdateState.Ready ->
            if (canInstall) "Version ${update.version} prête · toucher pour installer"
            else "Version ${update.version} prête · toucher pour autoriser les mises à jour"
        is UpdateState.Installing -> "Installation de la version ${update.version}…"
        is UpdateState.NeedsConfirmation -> "Version ${update.version} · confirme l'installation"
        else -> null
    }
    AnimatedVisibility(visible = text != null) {
        Text(
            text.orEmpty(),
            color = Color.White,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                .tap(enabled = update is UpdateState.Ready, shape = RoundedCornerShape(12.dp), lift = 1.03f, onClick = onInstall)
                .glass(RoundedCornerShape(12.dp)).padding(12.dp),
        )
    }
}

@Composable
private fun Idle(data: DataStatus, onDemo: () -> Unit) {
    // Logo breathing softly, with a red glow behind the needle.
    val breath = rememberInfiniteTransition(label = "logo")
    val scale by breath.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(2_200), RepeatMode.Reverse), label = "scale")
    val glow by breath.animateFloat(0.10f, 0.28f, infiniteRepeatable(tween(2_200), RepeatMode.Reverse), label = "glow")
    // Each time home appears (opening, back from a drive), the red glow blooms wide and settles.
    val bloom = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        delay(900)
        bloom.animateTo(0f, tween(1_600, easing = FastOutSlowInEasing))
    }
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(150.dp).scale(scale * (1f + 0.7f * bloom.value))
                .background(Brush.radialGradient(listOf(Red.copy(alpha = glow + 0.35f * bloom.value), Color.Transparent)), CircleShape),
        )
        // Hidden: a long press on the logo starts the simulated drive. The icon drawing leaves a
        // margin for launcher masks: drawn larger here so the dial keeps its size on screen.
        Image(
            painterResource(R.drawable.ic_logo_foreground),
            contentDescription = null,
            modifier = Modifier.size(174.dp).scale(scale).pointerInput(Unit) { detectTapGestures(onLongPress = { onDemo() }) },
        )
    }
    Text("Zenith", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    // One quiet line: how many radars, and how fresh the base is (the dot says it at a glance).
    // The radars in memory count up as the screen opens, like a dashboard waking up.
    val counted = remember { Animatable(0f) }
    LaunchedEffect(data.radarCount) { counted.animateTo(data.radarCount.toFloat(), tween(1_400, easing = FastOutSlowInEasing)) }
    val radars = counted.value.roundToInt()
    val updated = data.radarsUpdatedMs
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (data.radarCount > 0 && updated != null) {
            val fresh = System.currentTimeMillis() - updated < 24 * 3_600_000L
            Box(Modifier.size(7.dp).background(if (fresh) Green else Orange, CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            when {
                data.radarCount == 0 -> "Radars et limitations chargés au démarrage"
                updated == null -> "${thousands(radars)} radars"
                else -> "${thousands(radars)} radars · à jour ${freshness(updated)}"
            },
            color = Dim,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** "à 15:35" today, "hier à 15:35", else "le 31/10". */
private fun freshness(ms: Long): String {
    val then = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val now = java.util.Calendar.getInstance()
    val days = (now.get(java.util.Calendar.YEAR) - then.get(java.util.Calendar.YEAR)) * 366 +
        now.get(java.util.Calendar.DAY_OF_YEAR) - then.get(java.util.Calendar.DAY_OF_YEAR)
    val time = "%02d:%02d".format(then.get(java.util.Calendar.HOUR_OF_DAY), then.get(java.util.Calendar.MINUTE))
    return when (days) {
        0 -> "à $time"
        1 -> "hier à $time"
        else -> "le %02d/%02d".format(then.get(java.util.Calendar.DAY_OF_MONTH), then.get(java.util.Calendar.MONTH) + 1)
    }
}

/** Back home after a drive: what it was, in a few numbers; fades by itself or at a touch. */
@Composable
private fun TripCard(summary: TripSummary?, comingHome: Boolean) {
    // Kept while it leaves, so it does not empty before fading.
    var shown by remember { mutableStateOf<TripSummary?>(null) }
    if (summary != null) shown = summary
    LaunchedEffect(summary) {
        if (summary == null) return@LaunchedEffect
        delay(TripStats.SHOWN_MS + if (comingHome) HOMECOMING_MS.toLong() else 0L)
        DriveState.clearLastTrip()
    }
    AnimatedVisibility(
        visible = summary != null,
        enter = fadeIn(tween(500, delayMillis = if (comingHome) 1_300 else 300)) +
            scaleIn(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow), initialScale = 0.9f),
        exit = fadeOut(tween(300)) + scaleOut(tween(300), targetScale = 0.95f),
    ) {
        val trip = shown ?: return@AnimatedVisibility
        Column(
            Modifier.fillMaxWidth().padding(bottom = 12.dp)
                .tap(shape = RoundedCornerShape(20.dp), lift = 0.98f) { DriveState.clearLastTrip() }
                .glass(RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Text("Trajet terminé", color = Dim, fontSize = 13.sp)
            Spacer(Modifier.height(2.dp))
            Text(trip.headline(), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(trip.details(), color = Dim, fontSize = 14.sp)
        }
    }
}

/** One navigation app: its name only, centred, the same glass and height as Stop beside it. */
@Composable
private fun NavAppButton(app: NavApp, onOpen: (NavApp) -> Unit, modifier: Modifier) {
    Box(
        modifier.tap(shape = RoundedCornerShape(18.dp), sound = UiSound.Kind.OPEN) { onOpen(app) }.glass(RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(app.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Dashboard(s: DriveSnapshot?, modifier: Modifier = Modifier, compact: Boolean = false) {
    val limit = s?.limitKmh
    // Straight from each GPS fix, ahead of the rest of the drive (the demo has only its snapshot).
    val live by DriveState.liveSpeed.collectAsState()
    val speed = live ?: s?.speedKmh
    // Short roll between two fixes: the number glides but keeps up with the car.
    val animatedSpeed by animateIntAsState(speed ?: 0, tween(250, easing = LinearOutSlowInEasing), label = "speed")
    val color by animateColorAsState(speedColor(speed, limit), label = "speedColor")
    // Waiting for the GPS: the dashes breathe instead of sitting still.
    val searching = rememberInfiniteTransition(label = "searching")
    val searchAlpha by searching.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "searchAlpha")
    // Dark halo so the digits stay readable over the map.
    val halo = TextStyle(shadow = Shadow(Color.Black, blurRadius = 28f))
    // The ring: 260 upright, smaller on a phone lying across.
    val ring = if (compact) 190.dp else 260.dp
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(s?.road?.segment?.name ?: " ", color = Dim, fontSize = if (compact) 14.sp else 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = halo)
        Spacer(Modifier.height(4.dp))
        SpeedRing(
            speed,
            limit,
            color,
            ring,
            sign = { LimitSign(limit ?: 0, uncertain = s?.limitSource.isGuess(), size = ring * (60f / 260f)) },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (speed == null) "--" else animatedSpeed.toString(),
                    color = color,
                    fontSize = (ring.value * 108f / 260f).sp,
                    fontWeight = FontWeight.Bold,
                    // Fixed-width digits, set tight: the number does not jump as it changes.
                    letterSpacing = (-4).sp,
                    style = halo.copy(fontFeatureSettings = "tnum"),
                    modifier = Modifier.alpha(if (speed == null) searchAlpha else 1f),
                )
                Text("km/h", color = Dim, fontSize = if (compact) 13.sp else 15.sp)
            }
        }
        val ahead = s?.limitAhead
        Text(
            ahead?.let { "${it.limitKmh} dans ${distanceText(it.distanceM)}" } ?: " ",
            color = Dim,
            fontSize = 15.sp,
            style = halo,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Round limit sign; gray ring when the limit is only inferred (road type, built-up area or not). */
@Composable
internal fun LimitSign(limitKmh: Int, uncertain: Boolean, size: Dp = 104.dp) {
    // A new limit pops in and sends out a ring, so the eye catches the change.
    val pop = remember { Animatable(1f) }
    val ring = remember { Animatable(1f) }
    LaunchedEffect(limitKmh) {
        launch {
            ring.snapTo(0f)
            ring.animateTo(1f, tween(900, easing = LinearOutSlowInEasing))
        }
        pop.snapTo(1.15f)
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
    }
    val edge = if (uncertain) Dim else Red
    Box(
        modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }.size(size)
            .drawBehind {
                val k = ring.value
                if (k < 1f) {
                    drawCircle(edge.copy(alpha = 0.6f * (1f - k)), radius = this.size.minDimension / 2 * (1f + 0.7f * k), style = Stroke(3.dp.toPx() * (1f - k) + 0.5f))
                }
            }
            .background(Color.White, CircleShape)
            .border(size * 0.106f, edge, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            limitKmh.toString(),
            color = Color.Black,
            fontSize = (size.value * if (limitKmh >= 100) 0.327f else 0.385f).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Start, then Stop; [compact] on the road: lower and quieter, still there. */
@Composable
private fun StartStop(
    running: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth(),
    /** In a row of fixed height: the word centred in it, no padding of its own. */
    fixedHeight: Boolean = false,
) {
    val bg by animateColorAsState(if (running) Glass else Color.White, label = "buttonBg")
    val height by animateDpAsState(if (compact) 9.dp else 18.dp, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow), label = "buttonHeight")
    val quiet by animateFloatAsState(if (compact) 0.7f else 1f, tween(500), label = "buttonQuiet")
    // At home, the Start button sends out a soft ring of light now and then: the one thing to touch.
    val ping = rememberInfiniteTransition(label = "startPing")
    val wave by ping.animateFloat(0f, 1f, infiniteRepeatable(tween(2_600, easing = FastOutSlowInEasing)), label = "startWave")
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .drawBehind {
                if (running || wave > 0.7f) return@drawBehind
                val k = wave / 0.7f
                val grow = 10.dp.toPx() * k
                drawRoundRect(
                    Color.White.copy(alpha = 0.35f * (1f - k)),
                    topLeft = Offset(-grow, -grow),
                    size = Size(size.width + grow * 2, size.height + grow * 2),
                    cornerRadius = CornerRadius(18.dp.toPx() + grow),
                    style = Stroke(1.5.dp.toPx()),
                )
            }
            // Only the touch: the flight and the way back are the sound.
            .tap(
                shape = RoundedCornerShape(18.dp),
                sound = UiSound.Kind.SILENT,
                lift = 1.04f,
                glow = if (running) PressLight else PressShade,
                onClick = if (running) onStop else onStart,
            )
            .glass(RoundedCornerShape(18.dp), bg)
            .then(if (fixedHeight) Modifier else Modifier.padding(vertical = height))
            .alpha(quiet),
    ) {
        // The word rolls over, like a switch flipping.
        AnimatedContent(
            running,
            transitionSpec = {
                (slideInVertically(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(250)))
                    .togetherWith(slideOutVertically(tween(250)) { -it } + fadeOut(tween(200)))
            },
            label = "startStop",
        ) { on ->
            Text(
                if (on) "Arrêter" else "Démarrer",
                color = if (on) Color.White else Color.Black,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Speed digits: white, orange just under the limit, red above it. */
internal fun speedColor(speedKmh: Int?, limitKmh: Int?): Color = when {
    speedKmh == null || limitKmh == null -> Color.White
    speedKmh > limitKmh -> Red
    speedKmh >= limitKmh - 3 -> Orange
    else -> Color.White
}

/** Limit inferred rather than signposted: shown with a gray ring. */
fun LimitSource?.isGuess() = this == LimitSource.INFERRED || this == LimitSource.AREA

private fun thousands(n: Int) = "%,d".format(n).replace(',', ' ')

internal fun distanceText(m: Double): String =
    if (m < 1_000) "${(m / 10).roundToInt() * 10} m" else "%.1f km".format(m / 1_000).replace('.', ',')
