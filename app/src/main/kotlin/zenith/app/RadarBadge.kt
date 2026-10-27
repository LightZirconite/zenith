package zenith.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import zenith.core.RadarType

/** Color of each radar kind, shared by the map badges and the screen. */
fun radarColorArgb(type: RadarType): Long = when (type) {
    RadarType.RED_LIGHT -> 0xFFFF9F0A
    RadarType.SECTION_START, RadarType.SECTION_END -> 0xFFBF5AF2
    RadarType.WORKZONE -> 0xFFFFD60A
    RadarType.MOBILE, RadarType.MOBILE_VEHICLE, RadarType.POLICE -> 0xFFFF375F
    else -> 0xFFFF3B30
}

/** "#RRGGBB" for MapLibre styles. */
fun radarColorHex(type: RadarType): String = "#%06X".format(radarColorArgb(type) and 0xFFFFFF)

/** Round badge like the ones on the map: white disc, ring in the radar's color, limit inside. */
@Composable
fun RadarBadge(type: RadarType, limitKmh: Int?, size: Dp = 44.dp) {
    Box(
        Modifier.size(size).background(Color.White, CircleShape).border(size / 9, Color(radarColorArgb(type)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            limitKmh?.toString() ?: "!",
            color = Color.Black,
            fontSize = (size.value * if ((limitKmh ?: 0) >= 100) 0.30f else 0.36f).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
