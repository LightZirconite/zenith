package zenith.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import zenith.core.ActiveAlert
import zenith.core.ControlZone
import zenith.core.DriveSnapshot
import zenith.core.LimitSource
import zenith.core.Radar
import zenith.core.RadarType
import zenith.core.SectionStatus
import zenith.core.UpcomingRadar
import kotlin.math.roundToInt

private val Bg = Color(0xFF000000)
private val Red = Color(0xFFFF3B30)
private val Orange = Color(0xFFFF9F0A)
private val Green = Color(0xFF30D158)
private val Dim = Color(0xFF8E8E93)

/** With music: the map darkens toward its bottom but never turns into a black band, the Canvas rises over it. */
private val MapOverMusic = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.45f),
    0.12f to Color.Transparent,
    0.45f to Color.Transparent,
    0.75f to Color.Black.copy(alpha = 0.4f),
    1f to Color.Black.copy(alpha = 0.85f),
)

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
) {
    val showMap = running && snapshot != null
    Box(Modifier.fillMaxSize().background(Bg)) {
        // Tilted map over the top of the screen, melting progressively into the background. Built as
        // soon as the drive starts, hidden: its style and tiles load while the GPS searches, and it
        // shows, already in place, with the first position.
        val mapAlpha by animateFloatAsState(if (showMap) 1f else 0f, tween(if (showMap) 500 else 300), label = "mapIn")
        val mapScale by animateFloatAsState(if (showMap) 1f else 1.03f, tween(700, easing = FastOutSlowInEasing), label = "mapScale")
        if (running) {
            // With music, the bottom of the map stays half visible: the Canvas fades in over it.
            DriveMap(
                snapshot,
                radars,
                zones,
                Modifier.fillMaxWidth().fillMaxHeight(0.62f).graphicsLayer {
                    alpha = mapAlpha
                    scaleX = mapScale
                    scaleY = mapScale
                },
                overlay = if (media != null) MapOverMusic else DriveFade,
            )
        }
        // Music ambience from the bottom: the Spotify Canvas (else the cover, blurred) melting into
        // the black, together with the map, never before it.
        AnimatedVisibility(
            visible = showMap && media != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(1_200)),
            exit = fadeOut(tween(600)),
        ) {
            media?.let {
                MusicBackdrop(
                    it,
                    canvas = mediaCanvas,
                    // An alert on screen: the music steps back.
                    calm = snapshot?.alerts?.isNotEmpty() == true,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.62f),
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusRow(running, gps, data, demo, snapshot)
            UpdateBanner(update, canInstall, onInstallUpdate)
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Idle logo and speed swap with a soft zoom; a little lower than the middle while
                // driving, so the car arrow on the map stays clear.
                AnimatedContent(
                    running,
                    transitionSpec = {
                        (fadeIn(tween(500, delayMillis = 150)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow), initialScale = 0.9f))
                            .togetherWith(fadeOut(tween(250)) + scaleOut(tween(250), targetScale = 1.05f))
                    },
                    contentAlignment = Alignment.Center,
                    label = "home",
                ) { driving ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (driving) Dashboard(snapshot, Modifier.padding(top = if (showMap) 72.dp else 0.dp)) else Idle(data, onDemo)
                    }
                }
            }
            AlertArea(snapshot.takeIf { running })
            Spacer(Modifier.height(12.dp))
            AnimatedVisibility(visible = running && media != null, enter = fadeIn(), exit = fadeOut()) {
                media?.let {
                    MediaBar(
                        it,
                        onOpen = onMediaOpen,
                        onLike = onMediaLike,
                        onPrevious = onMediaPrevious,
                        onPlayPause = onMediaPlayPause,
                        onNext = onMediaNext,
                    )
                }
            }
            AnimatedVisibility(visible = running && navApps.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                NavApps(navApps, onOpenNavApp)
            }
            StartStop(running, onStart, onStop)
            AnimatedVisibility(visible = !running, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    "Réglages",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).tap(onClick = onOpenSettings).glass(RoundedCornerShape(18.dp))
                        .padding(vertical = 14.dp),
                )
            }
            AnimatedVisibility(visible = !running, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    "Cartes © OpenFreeMap © OpenStreetMap · radars data.gouv.fr, OpenStreetMap, Lufop",
                    color = Dim.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
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
        AnimatedVisibility(visible = running && demo, enter = fadeIn(), exit = fadeOut()) {
            Chip("Démo · trajet simulé", Color(0xFF0A84FF))
        }
        AnimatedVisibility(visible = running && !demo && estimated, enter = fadeIn(), exit = fadeOut()) {
            Chip("Tunnel · position estimée", Orange)
        }
        // Control itinerary: a movable radar may be anywhere along it.
        val zone = snapshot?.zone
        AnimatedVisibility(visible = running && zone != null && !estimated, enter = fadeIn(), exit = fadeOut()) {
            Chip("Itinéraire de contrôle · ${((zone?.remainingM ?: 0.0) / 1_000).roundToInt().coerceAtLeast(1)} km", Orange)
        }
        AnimatedVisibility(visible = running && !demo && !estimated && zone == null && showGps, enter = fadeIn(), exit = fadeOut()) {
            val text = when {
                gps.accuracyM == null -> "Satellites ${gps.used}/${gps.visible} · recherche"
                gpsGood -> "GPS ±${gps.accuracyM.roundToInt()} m · ${gps.used} sat."
                else -> "GPS faible ±${gps.accuracyM.roundToInt()} m · ${gps.used}/${gps.visible}"
            }
            Chip(text, if (gpsGood) Green else Orange)
        }
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(visible = data.loading || data.error != null, enter = fadeIn(), exit = fadeOut()) {
            Chip(data.error ?: "Chargement des routes…", if (data.error != null) Orange else Dim)
        }
    }
}

@Composable
private fun Chip(text: String, dot: Color, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.then(if (onClick != null) Modifier.tap(onClick = onClick) else Modifier).glass(RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontSize = 13.sp)
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
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).tap(enabled = update is UpdateState.Ready, onClick = onInstall)
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
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(150.dp).scale(scale)
                .background(Brush.radialGradient(listOf(Red.copy(alpha = glow), Color.Transparent)), CircleShape),
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
    Spacer(Modifier.height(8.dp))
    Text(
        when {
            data.radarCount == 0 -> "Radars et limitations chargés au démarrage"
            data.zoneCount == 0 -> "${thousands(data.radarCount)} radars en mémoire"
            else -> "${thousands(data.radarCount)} radars · ${thousands(data.zoneCount)} itinéraires de contrôle"
        },
        color = Dim,
        fontSize = 16.sp,
        textAlign = TextAlign.Center,
    )
}

/** Shortcuts to the navigation apps installed: Zenith keeps alerting from behind them. */
@Composable
private fun NavApps(apps: List<NavApp>, onOpen: (NavApp) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (app in apps) {
            Text(
                "Ouvrir ${app.label}",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).tap { onOpen(app) }.glass(RoundedCornerShape(14.dp)).padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun Dashboard(s: DriveSnapshot?, modifier: Modifier = Modifier) {
    val limit = s?.limitKmh
    val speed = s?.speedKmh
    val animatedSpeed by animateIntAsState(speed ?: 0, tween(400), label = "speed")
    val color by animateColorAsState(speedColor(speed, limit), label = "speedColor")
    // Waiting for the GPS: the dashes breathe instead of sitting still.
    val searching = rememberInfiniteTransition(label = "searching")
    val searchAlpha by searching.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "searchAlpha")
    // Dark halo so the digits stay readable over the map.
    val halo = TextStyle(shadow = Shadow(Color.Black, blurRadius = 28f))
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(s?.road?.segment?.name ?: " ", color = Dim, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = halo)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (speed == null) "--" else animatedSpeed.toString(),
                    color = color,
                    fontSize = 150.sp,
                    fontWeight = FontWeight.Bold,
                    // Fixed-width digits, set tight: the number does not jump as it changes.
                    letterSpacing = (-4).sp,
                    style = halo.copy(fontFeatureSettings = "tnum"),
                    modifier = Modifier.alpha(if (speed == null) searchAlpha else 1f),
                )
                Text("km/h", color = Dim, fontSize = 20.sp)
            }
            AnimatedVisibility(visible = limit != null, enter = fadeIn(), exit = fadeOut()) {
                Row {
                    Spacer(Modifier.width(20.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LimitSign(limit ?: 0, uncertain = s?.limitSource.isGuess())
                        val ahead = s?.limitAhead
                        Text(
                            ahead?.let { "${it.limitKmh} dans ${distanceText(it.distanceM)}" } ?: " ",
                            color = Dim,
                            fontSize = 15.sp,
                            style = halo,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Round limit sign; gray ring when the limit is only inferred (road type, built-up area or not). */
@Composable
internal fun LimitSign(limitKmh: Int, uncertain: Boolean, size: Dp = 104.dp) {
    // A new limit pops in, so the eye catches the change.
    val pop = remember { Animatable(1f) }
    LaunchedEffect(limitKmh) {
        pop.snapTo(1.15f)
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
    }
    Box(
        modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }.size(size).background(Color.White, CircleShape)
            .border(size * 0.106f, if (uncertain) Dim else Red, CircleShape),
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

/** Section under way, then the radar alert or, without one, the next radar on the road. */
@Composable
private fun AlertArea(s: DriveSnapshot?) {
    s?.section?.let { SectionCard(it) }
    val alert = s?.alerts?.firstOrNull()
    AnimatedVisibility(
        visible = alert != null,
        enter = slideInVertically(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { it / 2 } +
            fadeIn(tween(250)) + scaleIn(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.92f),
        exit = slideOutVertically(tween(300)) { it / 3 } + fadeOut(tween(250)) + scaleOut(tween(300), targetScale = 0.95f),
    ) {
        alert?.let { AlertCard(it) }
    }
    AnimatedVisibility(
        visible = alert == null && s?.upcoming != null,
        enter = fadeIn(tween(400)) + expandVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)),
        exit = fadeOut(tween(250)) + shrinkVertically(tween(300)),
    ) {
        s?.upcoming?.let { UpcomingStrip(it) }
    }
}

@Composable
private fun AlertCard(a: ActiveAlert) {
    // Distance at first sight of this radar, to draw the countdown bar.
    var start by remember(a.radar.id) { mutableFloatStateOf(a.distanceM.toFloat()) }
    if (a.distanceM > start) start = a.distanceM.toFloat()
    val progress by animateFloatAsState(1f - (a.distanceM.toFloat() / start).coerceIn(0f, 1f), label = "progress")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(1f, 0.55f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "alpha")
    val bg by animateColorAsState(if (a.overLimit) Red else Glass, tween(300), label = "alertBg")

    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp), bg).alpha(if (a.overLimit) alpha else 1f)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            RadarBadge(a.radar.type, a.limitKmh, 54.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(a.radar.type.label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                val details = listOfNotNull(
                    if (a.radar.directionDeg == null) "sens non vérifié" else null,
                    a.ageMs?.let { "signalé il y a ${(it / 60_000).coerceAtLeast(1)} min" },
                )
                if (details.isNotEmpty()) Text(details.joinToString(" · "), color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
            }
            Text(distanceText(a.distanceM), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).background(Color.White.copy(alpha = 0.15f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Color.White))
        }
    }
}

@Composable
private fun UpcomingStrip(u: UpcomingRadar) {
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadarBadge(u.radar.type, u.limitKmh)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(u.radar.type.label, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            u.roadName?.let { Text("sur $it", color = Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text(distanceText(u.distanceM), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionCard(s: SectionStatus) {
    // One number to read (the average, next to the limit sign), one sentence saying what to do.
    val slowDown = s.lost || s.targetKmh < s.limitKmh
    val progress by animateFloatAsState((1 - s.remainingM / s.lengthM).toFloat().coerceIn(0f, 1f), label = "section")
    val bg by animateColorAsState(if (slowDown) Red else Glass, tween(400), label = "sectionBg")
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).glass(RoundedCornerShape(22.dp), bg)) {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            RadarBadge(RadarType.SECTION_START, s.limitKmh, 54.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Ta moyenne sur le tronçon", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
                Text("${s.averageKmh.roundToInt()} km/h", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("encore", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
                Text(distanceText(s.remainingM), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            when {
                s.lost -> "Moyenne trop haute : elle finira au-dessus de ${s.limitKmh}"
                slowDown -> "Pour finir sous ${s.limitKmh} : pas plus de ${s.targetKmh} jusqu'au bout"
                else -> "Moyenne bonne : roule jusqu'à ${s.limitKmh}, pas plus"
            },
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
        )
        Box(Modifier.fillMaxWidth().height(6.dp).background(Color.White.copy(alpha = 0.15f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Color.White))
        }
    }
}

@Composable
private fun StartStop(running: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val bg by animateColorAsState(if (running) Glass else Color.White, label = "buttonBg")
    Text(
        if (running) "Arrêter" else "Démarrer",
        color = if (running) Color.White else Color.Black,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().tap(onClick = if (running) onStop else onStart).glass(RoundedCornerShape(18.dp), bg)
            .padding(vertical = 18.dp),
    )
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
