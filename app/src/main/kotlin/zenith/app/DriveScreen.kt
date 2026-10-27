package zenith.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import zenith.core.ActiveAlert
import zenith.core.DriveSnapshot
import zenith.core.LimitSource
import zenith.core.SectionStatus
import kotlin.math.roundToInt

private val Red = Color(0xFFE53935)
private val Orange = Color(0xFFFFA726)
private val Dim = Color(0xFF8A8A8A)
private val Card = Color(0xFF1C1C1C)

@Composable
fun DriveScreen(running: Boolean, snapshot: DriveSnapshot?, onStart: () -> Unit, onStop: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.CenterStart) {
            snapshot?.limitKmh?.let { LimitSign(it, uncertain = snapshot.limitSource == LimitSource.INFERRED) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Speed(running, snapshot)
        }
        snapshot?.section?.let { SectionCard(it) }
        snapshot?.alerts?.firstOrNull()?.let { AlertCard(it) }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = if (running) onStop else onStart,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (running) Card else Color.White, contentColor = if (running) Color.White else Color.Black),
        ) {
            Text(if (running) "Arrêter" else "Démarrer", fontSize = 18.sp)
        }
    }
}

@Composable
private fun Speed(running: Boolean, s: DriveSnapshot?) {
    val limit = s?.limitKmh
    val color = when {
        s == null || limit == null -> Color.White
        s.speedKmh > limit -> Red
        s.speedKmh >= limit - 3 -> Orange
        else -> Color.White
    }
    Text(
        text = s?.speedKmh?.toString() ?: "--",
        color = color,
        fontSize = 160.sp,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = when {
            !running -> "Arrêté"
            s == null -> "Recherche du GPS…"
            else -> "km/h"
        },
        color = Dim,
        fontSize = 24.sp,
    )
}

/** Round limit sign; gray border when the limit is only inferred from the road type. */
@Composable
private fun LimitSign(limitKmh: Int, uncertain: Boolean) {
    Box(
        modifier = Modifier.size(110.dp).background(Color.White, CircleShape).border(12.dp, if (uncertain) Dim else Red, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(limitKmh.toString(), color = Color.Black, fontSize = 40.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AlertCard(a: ActiveAlert) {
    Row(
        modifier = Modifier.fillMaxWidth().background(if (a.overLimit) Red else Card, RoundedCornerShape(16.dp)).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(a.radar.type.label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            val details = listOfNotNull(
                a.limitKmh?.let { "Limite $it" },
                a.ageMs?.let { "signalé il y a ${(it / 60_000).coerceAtLeast(1)} min" },
            )
            if (details.isNotEmpty()) Text(details.joinToString(" · "), color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
        }
        Text(displayDistance(a.distanceM), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionCard(s: SectionStatus) {
    val over = s.averageKmh > s.limitKmh
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            .background(if (over) Red else Card, RoundedCornerShape(16.dp)).padding(20.dp),
    ) {
        Text("Tronçon · moyenne ${s.averageKmh.roundToInt()} / ${s.limitKmh}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        val target = s.maxSpeedToFinishKmh?.let { "max ${it.roundToInt()} km/h jusqu'à la fin" } ?: "aucune contrainte"
        Text("${displayDistance(s.remainingM)} restants · $target", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
    }
}

private fun displayDistance(m: Double): String =
    if (m < 1_000) "${(m / 10).roundToInt() * 10} m" else "%.1f km".format(m / 1_000).replace('.', ',')
