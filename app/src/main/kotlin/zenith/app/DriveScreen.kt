package zenith.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import zenith.core.ActiveAlert
import zenith.core.DriveSnapshot
import zenith.core.LimitSource
import zenith.core.Radar
import zenith.core.SectionStatus
import zenith.core.UpcomingRadar
import kotlin.math.roundToInt

private val Bg = Color(0xFF000000)
private val Surface = Color(0xFF16161A)
private val Red = Color(0xFFFF3B30)
private val Orange = Color(0xFFFF9F0A)
private val Green = Color(0xFF30D158)
private val Dim = Color(0xFF8E8E93)

@Composable
fun DriveScreen(
    running: Boolean,
    snapshot: DriveSnapshot?,
    radars: List<Radar>,
    gps: GpsInfo,
    data: DataStatus,
    update: UpdateState,
    canInstall: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    val showMap = running && snapshot != null
    Box(Modifier.fillMaxSize().background(Bg)) {
        // Small tilted map at the top, melting into the background; the speed sits right under it.
        AnimatedVisibility(visible = showMap, enter = fadeIn(tween(800)), exit = fadeOut()) {
            DriveMap(snapshot, radars, Modifier.fillMaxWidth().fillMaxHeight(0.46f))
        }
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusRow(running, gps, data)
            UpdateBanner(update, canInstall, onInstallUpdate)
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = if (showMap) Arrangement.Bottom else Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (running) Dashboard(snapshot) else Idle(data)
            }
            AlertArea(snapshot)
            Spacer(Modifier.height(12.dp))
            StartStop(running, onStart, onStop)
        }
    }
}

/** GPS and data chips; they appear while something is pending and fade once all is fine. */
@Composable
private fun StatusRow(running: Boolean, gps: GpsInfo, data: DataStatus) {
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
        AnimatedVisibility(visible = running && showGps, enter = fadeIn(), exit = fadeOut()) {
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
private fun Chip(text: String, dot: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Surface).padding(horizontal = 12.dp, vertical = 6.dp),
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
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Surface)
                .clickable(enabled = update is UpdateState.Ready, onClick = onInstall).padding(12.dp),
        )
    }
}

@Composable
private fun Idle(data: DataStatus) {
    Text("Zenith", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(
        if (data.radarCount > 0) "${"%,d".format(data.radarCount).replace(',', ' ')} radars en mémoire" else "Radars et limitations chargés au démarrage",
        color = Dim,
        fontSize = 16.sp,
    )
}

@Composable
private fun Dashboard(s: DriveSnapshot?) {
    val limit = s?.limitKmh
    val speed = s?.speedKmh
    val animatedSpeed by animateIntAsState(speed ?: 0, tween(400), label = "speed")
    val color by animateColorAsState(
        when {
            speed == null || limit == null -> Color.White
            speed > limit -> Red
            speed >= limit - 3 -> Orange
            else -> Color.White
        },
        label = "speedColor",
    )
    Text(s?.road?.segment?.name ?: " ", color = Dim, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (speed == null) "--" else animatedSpeed.toString(),
                color = color,
                fontSize = 150.sp,
                fontWeight = FontWeight.Bold,
            )
            Text("km/h", color = Dim, fontSize = 20.sp)
        }
        AnimatedVisibility(visible = limit != null, enter = fadeIn(), exit = fadeOut()) {
            Row {
                Spacer(Modifier.width(20.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LimitSign(limit ?: 0, uncertain = s?.limitSource == LimitSource.INFERRED)
                    val ahead = s?.limitAhead
                    Text(
                        ahead?.let { "${it.limitKmh} dans ${distanceText(it.distanceM)}" } ?: " ",
                        color = Dim,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/** Round limit sign; gray ring when the limit is only inferred from the road type. */
@Composable
private fun LimitSign(limitKmh: Int, uncertain: Boolean) {
    Box(
        modifier = Modifier.size(104.dp).background(Color.White, CircleShape).border(11.dp, if (uncertain) Dim else Red, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(limitKmh.toString(), color = Color.Black, fontSize = if (limitKmh >= 100) 34.sp else 40.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AlertArea(s: DriveSnapshot?) {
    s?.section?.let { SectionCard(it) }
    val alert = s?.alerts?.firstOrNull()
    AnimatedVisibility(
        visible = alert != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        alert?.let { AlertCard(it) }
    }
    AnimatedVisibility(visible = alert == null && s?.upcoming != null, enter = fadeIn(), exit = fadeOut()) {
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
    val bg = if (a.overLimit) Red else Surface

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(bg).alpha(if (a.overLimit) alpha else 1f)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            a.limitKmh?.let {
                LimitSignSmall(it)
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(a.radar.type.label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                val details = listOfNotNull(
                    if (a.radar.directionDeg == null) "sens non vérifié" else null,
                    a.ageMs?.let { "signalé il y a ${(it / 60_000).coerceAtLeast(1)} min" },
                )
                if (details.isNotEmpty()) Text(details.joinToString(" · "), color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
            }
            Text(distanceText(a.distanceM), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).background(Color.White.copy(alpha = 0.15f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Color.White))
        }
    }
}

@Composable
private fun LimitSignSmall(limitKmh: Int) {
    Box(
        Modifier.size(54.dp).background(Color.White, CircleShape).border(6.dp, Red, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(limitKmh.toString(), color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun UpcomingStrip(u: UpcomingRadar) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Prochain", color = Dim, fontSize = 15.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(u.radar.type.label, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            u.roadName?.let { Text("sur $it", color = Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        u.limitKmh?.let { Text("$it · ", color = Dim, fontSize = 17.sp) }
        Text(distanceText(u.distanceM), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionCard(s: SectionStatus) {
    val over = s.averageKmh > s.limitKmh
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(22.dp))
            .background(if (over) Red else Surface).padding(20.dp),
    ) {
        Text("Tronçon · moyenne ${s.averageKmh.roundToInt()} / ${s.limitKmh}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        val target = s.maxSpeedToFinishKmh?.let { "max ${it.roundToInt()} km/h jusqu'à la fin" } ?: "aucune contrainte"
        Text("${distanceText(s.remainingM)} restants · $target", color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp)
    }
}

@Composable
private fun StartStop(running: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val bg by animateColorAsState(if (running) Surface else Color.White, label = "buttonBg")
    Text(
        if (running) "Arrêter" else "Démarrer",
        color = if (running) Color.White else Color.Black,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(bg)
            .clickable(onClick = if (running) onStop else onStart).padding(vertical = 18.dp),
    )
}

private fun distanceText(m: Double): String =
    if (m < 1_000) "${(m / 10).roundToInt() * 10} m" else "%.1f km".format(m / 1_000).replace('.', ',')
