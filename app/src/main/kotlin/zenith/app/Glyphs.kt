package zenith.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Small line icons drawn from 24 × 24 path data (no icon library: the APK stays light). Same
 * drawings as the iPhone's, which uses SF Symbols where they exist.
 */
object Glyphs {
    private fun line(name: String, d: String, width: Float = 2f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.White),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()

    val gear by lazy {
        line(
            "gear",
            "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 " +
                "1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 " +
                "1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 " +
                "2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 " +
                "2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
            1.8f,
        )
    }
    val camera by lazy { line("camera", "M5 7h9a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2zM16 10.5L21 8v8l-5-2.5", 2.4f) }
    val check by lazy { line("check", "M5 12.5l4.5 4.5L19 7.5", 3.2f) }
    val section by lazy { line("section", "M4 18h3M17 18h3M7 18l3-12h4l3 12M12 9v2M12 14v2", 2.2f) }
    val voice by lazy { line("voice", "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5 11a7 7 0 0 0 14 0M12 18v3") }
    val volume by lazy { line("volume", "M4 9h4l5-4v14l-5-4H4zM16 9a4 4 0 0 1 0 6M19 6a8 8 0 0 1 0 12") }
    val pause by lazy { line("pause", "M9 5v14M15 5v14", 2.6f) }
    val learner by lazy { line("learner", "M12 3l9 4-9 4-9-4zM7 9v5c0 2 2.5 3 5 3s5-1 5-3V9") }
    val rain by lazy { line("rain", "M7 15a4 4 0 1 1 1-7.9A5 5 0 0 1 18 9a3.5 3.5 0 0 1-1 6.8M8 19l-1 2M12 19l-1 2M16 19l-1 2") }
    val auto by lazy { line("auto", "M12 3v4M12 17v4M3 12h4M17 12h4M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6") }
    val car by lazy { line("car", "M5 16h14M6 16l1.5-5h9L18 16M8 16v2M16 16v2") }
    val park by lazy { line("park", "M7 21V3h6a4 4 0 0 1 0 8H7") }
    val link by lazy { line("link", "M10 14a4 4 0 0 0 6 0l3-3a4 4 0 0 0-6-6l-1 1M14 10a4 4 0 0 0-6 0l-3 3a4 4 0 0 0 6 6l1-1") }
    val music by lazy { line("music", "M9 18V5l11-2v13M9 18a3 3 0 1 1-6 0a3 3 0 1 1 6 0M20 16a3 3 0 1 1-6 0a3 3 0 1 1 6 0") }
    val canvas by lazy { line("canvas", "M5 4h14v16H5zM10 9v6l5-3z") }
    val pip by lazy { line("pip", "M3 5h18v14H3zM12 12h7v5h-7z") }
    val update by lazy { line("update", "M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5") }
    val trip by lazy { line("trip", "M5 19c4-1 2-6 7-7s3-6 7-7M5 17.5a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3M19 3.5a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3") }
    val sound by lazy { line("sound", "M3 12h2M7 8v8M11 5v14M15 8v8M19 11v2") }
    val bell by lazy { line("bell", "M6 16V11a6 6 0 0 1 12 0v5l2 2H4zM10 20a2 2 0 0 0 4 0") }
}

/** The coloured rounded square of a settings row, the iOS way, with its white glyph. */
@Composable
fun GlyphTile(icon: ImageVector, color: Color, size: Dp = 30.dp) {
    Box(Modifier.size(size).background(color, RoundedCornerShape(size * 0.27f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}
