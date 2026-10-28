package zenith.app

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import zenith.core.PlaybackProgress

private val BarSurface = Color(0xFF16161A)
private val BarDim = Color(0xFFB0B0B5)
private val Liked = Color(0xFF1ED760)

/** Dark on the left for the text, lighter where the backdrop shows, so it melts into the card. */
private val BarScrim = Brush.horizontalGradient(
    0f to Color.Black.copy(alpha = 0.72f),
    0.45f to Color.Black.copy(alpha = 0.45f),
    1f to Color.Black.copy(alpha = 0.3f),
)

private enum class Glyph { PREVIOUS, PLAY, PAUSE, NEXT }

/**
 * Music card under the alerts, for any player (Spotify, YouTube, Deezer…): cover, title, artist
 * and the buttons a driver needs, over a dark backdrop: the Spotify Canvas looping when the track
 * has one ([canvas] on), else the cover blurred. The heart (Spotify) shows when the card is wide
 * enough; a thin line at the bottom follows the track. A tap on the cover or the title opens the
 * player. [calm] while an alert shows: the backdrop steps back.
 */
@Composable
fun MediaBar(
    media: MediaInfo,
    canvas: Boolean = true,
    calm: Boolean = false,
    onOpen: () -> Unit = {},
    onLike: () -> Unit = {},
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    val video = rememberCanvas(media.spotifyTrackId, canvas)
    var videoShown by remember(video) { mutableStateOf(false) }
    var videoFailed by remember(video) { mutableStateOf(false) }
    val videoAlpha by animateFloatAsState(if (!videoShown) 0f else if (calm) 0.3f else 0.9f, tween(900), label = "canvas")
    val artAlpha by animateFloatAsState(if (calm) 0.3f else 0.6f, tween(600), label = "backdrop")
    val backdrop = remember(media.art) { media.art?.let(::tiny) }

    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 10.dp).height(80.dp).clip(RoundedCornerShape(20.dp)).background(BarSurface)) {
        // Below that width the heart would leave the title almost no room.
        val roomForLike = maxWidth >= 340.dp
        // Blurred cover: a tiny copy upscaled is already soft, the blur (Android 12+) smooths it out.
        backdrop?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(16.dp).alpha(artAlpha),
            )
        }
        if (video != null && !videoFailed) {
            CanvasVideo(
                video,
                playing = media.playing,
                modifier = Modifier.fillMaxSize().alpha(videoAlpha),
                onShown = { videoShown = true },
                onFailed = {
                    videoFailed = true
                    media.spotifyTrackId?.let { SpotifyCanvas.discard(it, video) }
                },
            )
        }
        Box(Modifier.fillMaxSize().background(BarScrim))

        val halo = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 12f))
        Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).fillMaxHeight().clickable(onClick = onOpen).semantics { contentDescription = "Ouvrir le lecteur" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2C2C30))) {
                    media.art?.let {
                        Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        media.title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = halo,
                    )
                    media.artist?.let { Text(it, color = BarDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = halo) }
                }
            }
            val like = media.like
            if (like != null && roomForLike) HeartButton(like.liked, onLike)
            GlyphButton(Glyph.PREVIOUS, "Précédent", onPrevious)
            PlayButton(media.playing, onPlayPause)
            GlyphButton(Glyph.NEXT, "Suivant", onNext)
        }
        media.progress?.let { ProgressLine(it, media.playing, Modifier.align(Alignment.BottomStart)) }
    }
}

/** Thin line along the bottom edge; redrawn four times a second while playing, never recomposed. */
@Composable
private fun ProgressLine(progress: PlaybackProgress, playing: Boolean, modifier: Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(progress, playing) {
        now = SystemClock.elapsedRealtime()
        while (playing) {
            delay(250)
            now = SystemClock.elapsedRealtime()
        }
    }
    Box(
        modifier.fillMaxWidth().height(3.dp).drawBehind {
            drawRect(Color.White.copy(alpha = 0.16f))
            drawRect(Color.White.copy(alpha = 0.85f), size = Size(size.width * progress.fractionAt(now, playing), size.height))
        },
    )
}

/** Big touch target, small drawn glyph: no icon library needed. */
@Composable
private fun GlyphButton(glyph: Glyph, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) { drawGlyph(glyph, Color.White) }
    }
}

/** Play / pause, the main button: a white disc. */
@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick).padding(3.dp).background(Color.White, CircleShape)
            .semantics { contentDescription = if (playing) "Pause" else "Lecture" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) { drawGlyph(if (playing) Glyph.PAUSE else Glyph.PLAY, Color.Black) }
    }
}

/** Spotify's Liked Songs: outlined heart, green and filled once liked. */
@Composable
private fun HeartButton(liked: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick)
            .semantics { contentDescription = if (liked) "Retirer des titres likés" else "Ajouter aux titres likés" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(19.dp)) {
            val heart = heart(size.width, size.height)
            if (liked) drawPath(heart, Liked) else drawPath(heart, Color.White, style = Stroke(width = 1.8.dp.toPx()))
        }
    }
}

private fun heart(w: Float, h: Float) = Path().apply {
    moveTo(w / 2, h * 0.93f)
    cubicTo(w * 0.28f, h * 0.76f, 0f, h * 0.56f, 0f, h * 0.32f)
    cubicTo(0f, h * 0.14f, w * 0.13f, h * 0.04f, w * 0.28f, h * 0.04f)
    cubicTo(w * 0.38f, h * 0.04f, w * 0.46f, h * 0.1f, w / 2, h * 0.19f)
    cubicTo(w * 0.54f, h * 0.1f, w * 0.62f, h * 0.04f, w * 0.72f, h * 0.04f)
    cubicTo(w * 0.87f, h * 0.04f, w, h * 0.14f, w, h * 0.32f)
    cubicTo(w, h * 0.56f, w * 0.72f, h * 0.76f, w / 2, h * 0.93f)
    close()
}

private fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val w = size.width
    val h = size.height
    fun triangle(fromX: Float, toX: Float) = Path().apply {
        moveTo(fromX, 0f)
        lineTo(toX, h / 2)
        lineTo(fromX, h)
        close()
    }
    when (glyph) {
        Glyph.PLAY -> drawPath(triangle(w * 0.12f, w), color)
        Glyph.PAUSE -> {
            drawRect(color, topLeft = Offset(w * 0.12f, 0f), size = Size(w * 0.28f, h))
            drawRect(color, topLeft = Offset(w * 0.6f, 0f), size = Size(w * 0.28f, h))
        }
        Glyph.NEXT -> {
            drawPath(triangle(0f, w * 0.78f), color)
            drawRect(color, topLeft = Offset(w * 0.8f, 0f), size = Size(w * 0.2f, h))
        }
        Glyph.PREVIOUS -> {
            drawPath(triangle(w, w * 0.22f), color)
            drawRect(color, topLeft = Offset(0f, 0f), size = Size(w * 0.2f, h))
        }
    }
}

/** A 24-pixel-wide copy of the cover: upscaled, it is a soft wash of its colors. */
private fun tiny(art: Bitmap): Bitmap? {
    if (art.width <= 0 || art.height <= 0) return null
    return runCatching { Bitmap.createScaledBitmap(art, 24, (24 * art.height / art.width).coerceIn(1, 48), true) }.getOrNull()
}
