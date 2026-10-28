package zenith.app

import android.graphics.Rect
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import zenith.core.DriveSnapshot
import kotlin.math.roundToInt

private val Red = Color(0xFFFF3B30)
private val Orange = Color(0xFFFF9F0A)
private val Blue = Color(0xFF0A84FF)
private val Dim = Color(0xFF8E8E93)

/** The car map, a little darker at the top for the chips and at the bottom edge. */
private val CarMapShade = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.35f),
    0.18f to Color.Transparent,
    0.82f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.45f),
)

/**
 * The car screen, the phone's drive screen laid out for a wide display: the map fills it and melts
 * into black on the left, where the alerts, the speed and the limit stand, glass cards included;
 * the car rides in the free part on the right, low enough to see far down the road. Laid out
 * inside [stableArea] (pixels), which the host's own buttons never cover. Idle: the breathing logo
 * and [hint]. No music here: Android Auto shows its own, and video is not allowed while driving.
 */
@Composable
fun CarDashboard(stableArea: StateFlow<Rect>, hint: StateFlow<String>) {
    val running by DriveState.running.collectAsState()
    val snapshot by DriveState.snapshot.collectAsState()
    val radars by DriveState.nearbyRadars.collectAsState()
    val zones by DriveState.nearbyZones.collectAsState()
    val gps by DriveState.gps.collectAsState()
    val data by DriveState.data.collectAsState()
    val demo by DriveState.demo.collectAsState()
    val area by stableArea.collectAsState()
    val idleHint by hint.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val safe = if (area.isEmpty) Rect(0, 0, widthPx.toInt(), heightPx.toInt()) else area
        val start = with(density) { safe.left.toDp() }
        val top = with(density) { safe.top.toDp() }
        val bottom = with(density) { (heightPx - safe.bottom).coerceAtLeast(0f).toDp() }
        val panel = (maxWidth * 0.38f).coerceIn(280.dp, 400.dp)
        val panelEnd = with(density) { (start + panel).toPx() }
        // The car in the middle of what the panel and the host's buttons leave free.
        val carX = ((panelEnd + safe.right) / 2f / widthPx).toDouble().coerceIn(0.5, 0.8)
        val showMap = running && snapshot != null
        val s = snapshot.takeIf { running }

        AnimatedVisibility(
            visible = showMap,
            enter = fadeIn(tween(1_200)) + scaleIn(tween(1_600, easing = FastOutSlowInEasing), initialScale = 1.08f),
            exit = fadeOut(tween(600)),
        ) {
            Box(Modifier.fillMaxSize()) {
                DriveMap(snapshot, radars, zones, Modifier.fillMaxSize(), carAt = 0.66, carX = carX, overlay = CarMapShade)
                // The map melts into black under the panel, like into the bottom of the phone screen.
                val edge = (panelEnd / widthPx).coerceIn(0.2f, 0.7f)
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.92f),
                            edge * 0.7f to Color.Black.copy(alpha = 0.82f),
                            edge to Color.Black.copy(alpha = 0.45f),
                            (edge * 1.35f).coerceAtMost(1f) to Color.Transparent,
                        ),
                    ),
                )
            }
        }

        AnimatedContent(
            running,
            transitionSpec = {
                (fadeIn(tween(500, delayMillis = 150)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow), initialScale = 0.9f))
                    .togetherWith(fadeOut(tween(250)) + scaleOut(tween(250), targetScale = 1.05f))
            },
            label = "carHome",
        ) { driving ->
            if (driving) {
                Column(
                    Modifier.fillMaxHeight().width(start + panel)
                        .padding(start = start + 20.dp, top = top + 16.dp, bottom = bottom + 16.dp),
                ) {
                    CarStatus(s, gps, data, demo)
                    Spacer(Modifier.height(10.dp))
                    AlertArea(s, CompactCards)
                    Spacer(Modifier.weight(1f))
                    CarSpeed(s, digits = (maxHeight.value * 0.2f).coerceIn(64f, 120f).sp)
                }
            } else {
                Column(
                    Modifier.fillMaxSize().padding(top = top, bottom = bottom),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Idle(data, onDemo = {})
                    Spacer(Modifier.height(10.dp))
                    Text(idleHint, color = Color.White.copy(alpha = 0.75f), fontSize = 16.sp, textAlign = TextAlign.Center)
                }
            }
        }

        // Too fast near a radar: the whole screen's edge pulses red, as on the small window.
        if (s?.alerts?.firstOrNull()?.overLimit == true) {
            val pulse = rememberInfiniteTransition(label = "carPulse")
            val frame by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "carFrame")
            Box(Modifier.fillMaxSize().alpha(frame).border(6.dp, Red, RoundedCornerShape(12.dp)))
        }
    }
}

/** Road name, then the speed, big, with the limit sign beside it and the next limit under it. */
@Composable
private fun CarSpeed(s: DriveSnapshot?, digits: TextUnit) {
    val speed = s?.speedKmh
    val limit = s?.limitKmh
    val color by animateColorAsState(speedColor(speed, limit), label = "carSpeed")
    val halo = TextStyle(shadow = Shadow(Color.Black, blurRadius = 24f))
    Column {
        Text(s?.road?.segment?.name ?: " ", color = Dim, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = halo)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    speed?.toString() ?: "--",
                    color = color,
                    fontSize = digits,
                    lineHeight = digits,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-3).sp,
                    style = halo.copy(fontFeatureSettings = "tnum"),
                )
                Text("km/h", color = Dim, fontSize = 16.sp)
            }
            AnimatedVisibility(visible = limit != null, enter = fadeIn(), exit = fadeOut()) {
                Row {
                    Spacer(Modifier.width(18.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LimitSign(limit ?: 0, uncertain = s?.limitSource.isGuess(), size = 84.dp)
                        Text(
                            s?.limitAhead?.let { "${it.limitKmh} dans ${distanceText(it.distanceM)}" } ?: " ",
                            color = Dim,
                            fontSize = 14.sp,
                            maxLines = 1,
                            style = halo,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** One chip at a time, the one that matters most: demo, tunnel, control itinerary, weak GPS, data. */
@Composable
private fun CarStatus(s: DriveSnapshot?, gps: GpsInfo, data: DataStatus, demo: Boolean) {
    val zone = s?.zone
    val accuracy = gps.accuracyM
    val error = data.error
    val chip = when {
        demo -> "Démo · trajet simulé" to Blue
        s?.estimated == true -> "Tunnel · position estimée" to Orange
        zone != null -> "Itinéraire de contrôle · ${(zone.remainingM / 1_000).roundToInt().coerceAtLeast(1)} km" to Orange
        accuracy == null -> "Satellites ${gps.used}/${gps.visible} · recherche" to Orange
        accuracy > 15 -> "GPS faible ±${accuracy.roundToInt()} m" to Orange
        error != null -> error to Orange
        data.loading -> "Chargement des routes…" to Dim
        else -> null
    }
    // Keeps its height when empty: the cards below do not jump.
    Box(Modifier.height(32.dp)) {
        AnimatedVisibility(visible = chip != null, enter = fadeIn(), exit = fadeOut()) {
            chip?.let { (text, dot) -> Chip(text, dot) }
        }
    }
}
