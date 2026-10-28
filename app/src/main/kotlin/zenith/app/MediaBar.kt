package zenith.app

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import zenith.core.PlaybackProgress
import kotlin.math.abs

private val BarDim = Color(0xFFB0B0B5)

/** How far the strip must travel for a swipe to change track (about 120 dp of finger). */
private val SWIPE_THRESHOLD = 72.dp

/** No second change within this delay: a quick double swipe skips one track, not two. */
private const val SWIPE_COOLDOWN_MS = 1_000L
private val Liked = Color(0xFF1ED760)

private enum class Glyph { PREVIOUS, PLAY, PAUSE, NEXT }

/**
 * Ambience of the drive screen while music plays: the Spotify Canvas looping when the track has one
 * ([canvas] on), else the cover blurred, rising from the bottom of the screen under the map, which
 * melts into it. Dark enough for the speed to stay readable; [calm] while an alert shows, it steps
 * back further. [focus] (music mode, 0 to 1) brings it up the screen, brighter.
 */
@Composable
fun MusicBackdrop(media: MediaInfo, canvas: Boolean, calm: Boolean, focus: Float = 0f, modifier: Modifier = Modifier) {
    val video = rememberCanvas(media.spotifyTrackId, canvas)
    var videoShown by remember(video) { mutableStateOf(false) }
    var videoFailed by remember(video) { mutableStateOf(false) }
    val videoAlpha by animateFloatAsState(if (!videoShown) 0f else if (calm) 0.35f else 0.85f, tween(900), label = "canvas")
    val artAlpha by animateFloatAsState(if (calm) 0.25f else 0.5f, tween(600), label = "backdrop")
    val backdrop = remember(media.art) { media.art?.let(::tiny) }
    // The top fades to transparent (a mask, not a black band): faint high up, whole where the map has gone.
    Box(modifier.verticalFade(blendStops(BackdropMask, BackdropMaskFocus, focus))) {
        // Blurred cover: a tiny copy upscaled is already soft, the blur (Android 12+) smooths it out.
        backdrop?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(24.dp).alpha((artAlpha + 0.3f * focus).coerceAtMost(1f)),
            )
        }
        if (video != null && !videoFailed) {
            CanvasVideo(
                video,
                playing = media.playing,
                modifier = Modifier.fillMaxSize().alpha((videoAlpha + 0.15f * focus).coerceAtMost(1f)),
                onShown = { videoShown = true },
                onFailed = {
                    videoFailed = true
                    media.spotifyTrackId?.let { SpotifyCanvas.discard(it, video) }
                },
            )
        }
        // Darkening for the speed and the buttons to stay readable, stronger at the bottom; lighter in music mode.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.35f - 0.2f * focus),
                    0.5f to Color.Black.copy(alpha = 0.45f - 0.2f * focus),
                    1f to Color.Black.copy(alpha = 0.75f - 0.05f * focus),
                ),
            ),
        )
    }
}

/**
 * Opacity of the backdrop along its height (it starts at a fifth of the screen): a faint tail high
 * up, whole from the middle of the screen, where the map has melted away.
 */
private val BackdropMask = listOf(0f to 0f, 0.125f to 0.5f, 0.275f to 1f, 1f to 1f)

/** Music mode: whole almost from its top, the map having stepped back. */
private val BackdropMaskFocus = listOf(0f to 0f, 0.04f to 0.6f, 0.1f to 1f, 1f to 1f)

/**
 * Music controls under the alerts, for any player (Spotify, YouTube, Deezer…): cover, title,
 * artist and the buttons a driver needs, on a translucent strip over [MusicBackdrop]. The heart
 * (Spotify) shows when the strip is wide enough; a thin line at the bottom follows the track. A
 * tap on the cover or the title switches the music mode, a long press opens the player. A video
 * (YouTube) shows its thumbnail wide. Swiping the strip changes track like on a
 * phone's lock screen: to the left the next one, to the right the previous one. Never by
 * accident: only a long, clearly sideways swipe counts, a tick and a label tell when it will,
 * coming back before letting go cancels it, and a second change waits a second.
 */
@Composable
fun MediaBar(
    media: MediaInfo,
    /** A tap on the cover or the title: music mode. */
    onTap: () -> Unit = {},
    /** A long press: the player itself. */
    onOpen: () -> Unit = {},
    onLike: () -> Unit = {},
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    /** A video: ten seconds back or forward instead of the previous and next ones. */
    onSeek: (Long) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val next by rememberUpdatedState(onNext)
    val previous by rememberUpdatedState(onPrevious)
    val swipe = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).height(72.dp).glass(RoundedCornerShape(20.dp))
            .pointerInput(Unit) {
                var drift = 0f
                var armed = false
                var lastChangeMs = 0L
                fun settle() = scope.launch { swipe.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) }
                detectDragGestures(
                    onDragStart = { _ ->
                        drift = 0f
                        armed = false
                    },
                    onDragEnd = {
                        val x = swipe.value
                        // Sideways enough (a scroll or a fumble drifts up or down) and not right after a change.
                        val now = SystemClock.elapsedRealtime()
                        if (armed && drift < abs(x) / 0.6f * 0.5f && now - lastChangeMs > SWIPE_COOLDOWN_MS) {
                            lastChangeMs = now
                            if (x < 0) next() else previous()
                        }
                        settle()
                    },
                    onDragCancel = { settle() },
                ) { change, drag ->
                    change.consume()
                    drift += abs(drag.y)
                    // Resistance: the strip moves slower than the finger.
                    val x = swipe.value + drag.x * 0.6f
                    scope.launch { swipe.snapTo(x) }
                    val past = abs(x) >= threshold
                    // A tick when the swipe would now change track, and coming back cancels it.
                    if (past && !armed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    armed = past
                }
            },
    ) {
        // What letting go will do, revealed on the side the strip leaves.
        val reveal = (abs(swipe.value) / threshold).coerceIn(0f, 1f)
        if (swipe.value != 0f) {
            Text(
                if (swipe.value < 0) "Suivant ›" else "‹ Précédent",
                color = Color.White.copy(alpha = if (reveal >= 1f) 0.95f else reveal * 0.5f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(if (swipe.value < 0) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 18.dp),
            )
        }
        // Below that width the heart would leave the title almost no room.
        val roomForLike = maxWidth >= 340.dp

        val halo = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 12f))
        Row(
            Modifier.fillMaxSize().graphicsLayer {
                translationX = swipe.value
                alpha = 1f - (abs(swipe.value) / (threshold * 2.5f)).coerceIn(0f, 0.6f)
            }.padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).fillMaxHeight().tap(onLongClick = onOpen, onClick = onTap)
                    .semantics { contentDescription = "Mode musique ; appui long : ouvrir le lecteur" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(width = if (media.video) 84.dp else 52.dp, height = if (media.video) 48.dp else 52.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Color(0xFF2C2C30)),
                ) {
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
            if (media.video) SeekButton("−10", "Reculer de 10 secondes") { onSeek(-10_000) } else GlyphButton(Glyph.PREVIOUS, "Précédent", onPrevious)
            PlayButton(media.playing, onPlayPause)
            if (media.video) SeekButton("+10", "Avancer de 10 secondes") { onSeek(10_000) } else GlyphButton(Glyph.NEXT, "Suivant", onNext)
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
        Modifier.size(44.dp).tap(onClick = onClick).clip(CircleShape).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) { drawGlyph(glyph, Color.White) }
    }
}

/** Ten seconds back or forward in a video: the number in a thin ring. */
@Composable
private fun SeekButton(text: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).tap(onClick = onClick).clip(CircleShape).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape), contentAlignment = Alignment.Center) {
            Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
    }
}

/** Play / pause, the main button: a white disc. */
@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).tap(onClick = onClick).clip(CircleShape).padding(3.dp).background(Color.White, CircleShape)
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
        Modifier.size(44.dp).tap(onClick = onClick).clip(CircleShape)
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
