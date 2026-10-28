package zenith.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import zenith.core.ControlZone
import zenith.core.DriveSnapshot
import zenith.core.Radar
import java.io.File
import kotlin.math.roundToInt

private val Red = Color(0xFFFF3B30)
private val Orange = Color(0xFFFF9F0A)

/** Darker map for the small window, deepening toward the speed at the bottom. */
private val PipFade = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.55f),
    0.25f to Color.Black.copy(alpha = 0.25f),
    0.5f to Color.Black.copy(alpha = 0.3f),
    1f to Color.Black.copy(alpha = 0.92f),
)

/**
 * Picture-in-picture window shown over Waze or Maps while driving: Zenith in miniature. The map
 * fills the window, darkened; the next radar sits on top, the speed and the limit at the bottom.
 * Everything scales with the window size. Over the limit near a radar, a red frame pulses.
 * While a Spotify Canvas plays ([canvas] on), the window shows both, like the drive screen: the
 * map on top melting into black, the Canvas rising from the bottom under the speed and the limit.
 * Without a canvas (other players, no canvas for the track), the map fills the window.
 */
@Composable
fun PipScreen(
    s: DriveSnapshot?,
    radars: List<Radar>,
    zones: List<ControlZone> = emptyList(),
    media: MediaInfo? = null,
    canvas: Boolean = false,
) {
    val alert = s?.alerts?.firstOrNull()
    val limit = s?.limitKmh
    val speed = s?.speedKmh
    val danger = alert?.overLimit == true
    val speedColor by animateColorAsState(
        when {
            speed == null || limit == null -> Color.White
            speed > limit -> Red
            speed >= limit - 3 -> Orange
            else -> Color.White
        },
        label = "pipSpeed",
    )
    val pulse = rememberInfiniteTransition(label = "pipPulse")
    val frame by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "pipFrame")

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val unit = minOf(maxWidth, maxHeight).value / 100f // 1 % of the window's short side, in dp
        val halo = TextStyle(shadow = Shadow(Color.Black, blurRadius = 18f))

        val video = rememberCanvas(media?.spotifyTrackId, canvas)
        var videoFailed by remember(video) { mutableStateOf(false) }
        val split = video != null && !videoFailed
        val mapHeight by animateFloatAsState(if (split) 0.62f else 1f, tween(700), label = "pipMap")
        DriveMap(s, radars, zones, Modifier.fillMaxWidth().fillMaxHeight(mapHeight), carAt = 0.5, overlay = if (split) PipMapFade else PipFade)
        video?.takeIf { !videoFailed }?.let { v ->
            PipCanvas(
                v,
                media,
                calm = alert != null,
                onFailed = { videoFailed = true },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.6f),
            )
        }

        // Next radar: pill at the top, red while too fast for it.
        val next = alert?.let { Triple(it.radar, it.limitKmh, it.distanceM) }
            ?: s?.upcoming?.let { Triple(it.radar, it.limitKmh, it.distanceM) }
        AnimatedVisibility(
            visible = next != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = (unit * 5).dp),
        ) {
            next?.let { (radar, radarLimit, distance) ->
                Row(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (danger) Red else Color(0xE616161A))
                        .padding(start = (unit * 2).dp, end = (unit * 4).dp, top = (unit * 2).dp, bottom = (unit * 2).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadarBadge(radar.type, radarLimit, (unit * 14).dp)
                    Spacer(Modifier.width((unit * 3).dp))
                    Text(
                        pipDistance(distance),
                        color = Color.White,
                        fontSize = (unit * 11).sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }

        // Speed and limit, big, at the bottom.
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = (unit * 6).dp, vertical = (unit * 4).dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    speed?.toString() ?: "--",
                    color = speedColor,
                    fontSize = (unit * 30).sp,
                    lineHeight = (unit * 30).sp,
                    fontWeight = FontWeight.Bold,
                    style = halo,
                )
                Text("km/h", color = Color.White.copy(alpha = 0.6f), fontSize = (unit * 7).sp, style = halo)
            }
            if (limit != null) {
                val sign = (unit * 26).dp
                Box(
                    Modifier.padding(bottom = (unit * 3).dp).size(sign).background(Color.White, CircleShape)
                        .border(sign / 9, if (s?.limitSource.isGuess()) Color(0xFF8E8E93) else Red, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        limit.toString(),
                        color = Color.Black,
                        fontSize = (unit * if (limit >= 100) 9 else 11).sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Too fast near a radar: pulsing red frame around the whole window.
        if (danger) {
            Box(Modifier.fillMaxSize().alpha(frame).border((unit * 2.5f).dp, Red, RoundedCornerShape((unit * 4).dp)))
        }
    }
}

/** Map on top of a split window: clear at the top, darker where the Canvas melts in. */
private val PipMapFade = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.5f),
    0.25f to Color.Black.copy(alpha = 0.15f),
    0.7f to Color.Black.copy(alpha = 0.45f),
    1f to Color.Black.copy(alpha = 0.85f),
)

/** Canvas at the bottom of a split window: darker under the speed. */
private val PipCanvasFade = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.3f),
    0.6f to Color.Black.copy(alpha = 0.45f),
    1f to Color.Black.copy(alpha = 0.85f),
)

/** Opacity of the Canvas: nothing at its top edge, it melts into the map rather than meeting it on a black band. */
private val PipCanvasMask = Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Black, 1f to Color.Black)

/** The Spotify Canvas in the lower part of the window, faded in at its first frame. */
@Composable
private fun PipCanvas(video: File, media: MediaInfo?, calm: Boolean, onFailed: () -> Unit, modifier: Modifier) {
    var shown by remember(video) { mutableStateOf(false) }
    val opacity by animateFloatAsState(if (!shown) 0f else if (calm) 0.6f else 1f, tween(900), label = "pipCanvas")
    Box(
        modifier.graphicsLayer {
            alpha = opacity
            compositingStrategy = CompositingStrategy.Offscreen
        }.drawWithContent {
            drawContent()
            drawRect(PipCanvasMask, blendMode = BlendMode.DstIn)
        },
    ) {
        CanvasVideo(
            video,
            playing = media?.playing == true,
            modifier = Modifier.fillMaxSize(),
            onShown = { shown = true },
            onFailed = {
                media?.spotifyTrackId?.let { SpotifyCanvas.discard(it, video) }
                onFailed()
            },
        )
        Box(Modifier.fillMaxSize().background(PipCanvasFade))
    }
}

private fun pipDistance(m: Double) =
    if (m < 1_000) "${(m / 10).roundToInt() * 10} m" else "%.1f km".format(m / 1_000).replace('.', ',')
