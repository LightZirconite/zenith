package zenith.app

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import zenith.core.DriveSnapshot
import kotlin.math.roundToInt

/**
 * Picture-in-picture window shown over Waze or Maps while driving: speed, limit and the radar
 * being approached. The whole window turns red when over the limit near a radar.
 */
@Composable
fun PipScreen(s: DriveSnapshot?) {
    val alert = s?.alerts?.firstOrNull()
    val limit = s?.limitKmh
    val speed = s?.speedKmh
    val over = speed != null && limit != null && speed > limit
    val bg by animateColorAsState(
        when {
            alert?.overLimit == true -> Color(0xFFFF3B30)
            alert != null -> Color(0xFF2C2C30)
            else -> Color.Black
        },
        label = "pipBg",
    )
    Column(
        Modifier.fillMaxSize().background(bg).padding(10.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                speed?.toString() ?: "--",
                color = if (over && alert?.overLimit != true) Color(0xFFFF3B30) else Color.White,
                fontSize = 46.sp,
                fontWeight = FontWeight.Bold,
            )
            if (limit != null) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(40.dp).background(Color.White, CircleShape).border(5.dp, Color(0xFFFF3B30), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(limit.toString(), color = Color.Black, fontSize = if (limit >= 100) 13.sp else 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        val next = alert?.let { it.radar.type.label to it.distanceM } ?: s?.upcoming?.let { it.radar.type.label to it.distanceM }
        if (next != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "${next.first} · ${pipDistance(next.second)}",
                color = Color.White.copy(alpha = if (alert != null) 1f else 0.7f),
                fontSize = 12.sp,
                fontWeight = if (alert != null) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun pipDistance(m: Double) =
    if (m < 1_000) "${(m / 10).roundToInt() * 10} m" else "%.1f km".format(m / 1_000).replace('.', ',')
