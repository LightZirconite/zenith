package zenith.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BarSurface = Color(0xFF16161A)
private val BarDim = Color(0xFF8E8E93)

private enum class Glyph { PREVIOUS, PLAY, PAUSE, NEXT }

/** One line under the alerts: cover, title, artist, and the three buttons a driver needs. */
@Composable
fun MediaBar(media: MediaInfo, onPrevious: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp)).background(BarSurface)
            .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2C2C30))) {
            media.art?.let {
                Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(media.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            media.artist?.let { Text(it, color = BarDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        GlyphButton(Glyph.PREVIOUS, onPrevious)
        GlyphButton(if (media.playing) Glyph.PAUSE else Glyph.PLAY, onPlayPause)
        GlyphButton(Glyph.NEXT, onNext)
    }
}

/** Big touch target, small drawn glyph: no icon library needed. */
@Composable
private fun GlyphButton(glyph: Glyph, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(if (glyph == Glyph.PLAY || glyph == Glyph.PAUSE) 18.dp else 15.dp)) { drawGlyph(glyph) }
    }
}

private fun DrawScope.drawGlyph(glyph: Glyph) {
    val w = size.width
    val h = size.height
    fun triangle(fromX: Float, toX: Float) = Path().apply {
        moveTo(fromX, 0f)
        lineTo(toX, h / 2)
        lineTo(fromX, h)
        close()
    }
    when (glyph) {
        Glyph.PLAY -> drawPath(triangle(w * 0.12f, w), Color.White)
        Glyph.PAUSE -> {
            drawRect(Color.White, topLeft = Offset(w * 0.12f, 0f), size = Size(w * 0.28f, h))
            drawRect(Color.White, topLeft = Offset(w * 0.6f, 0f), size = Size(w * 0.28f, h))
        }
        Glyph.NEXT -> {
            drawPath(triangle(0f, w * 0.78f), Color.White)
            drawRect(Color.White, topLeft = Offset(w * 0.8f, 0f), size = Size(w * 0.2f, h))
        }
        Glyph.PREVIOUS -> {
            drawPath(triangle(w, w * 0.22f), Color.White)
            drawRect(Color.White, topLeft = Offset(0f, 0f), size = Size(w * 0.2f, h))
        }
    }
}
