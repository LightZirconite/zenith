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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import zenith.core.ControlZone
import zenith.core.DriveSnapshot
import zenith.core.Radar
import java.io.File

private val Red = Color(0xFFFF3B30)

/**
 * Picture-in-picture window shown over Waze or Maps while driving: Zenith in miniature. The map
 * fills the window; the next radar sits on top, the speed and the limit at the bottom. Everything
 * scales with the window size. Over the limit near a radar, a red frame pulses.
 * While a Spotify Canvas plays ([canvas] on), it fills the window behind the map, which stays
 * whole and turns barely see-through toward the bottom: the Canvas only colors it from behind,
 * the map always readable (wish of the author). The map always fills the window.
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
    val digitsColor by animateColorAsState(speedColor(speed, limit), label = "pipSpeed")
    val pulse = rememberInfiniteTransition(label = "pipPulse")
    val frame by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "pipFrame")

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val unit = minOf(maxWidth, maxHeight).value / 100f // 1 % of the window's short side, in dp
        val halo = TextStyle(shadow = Shadow(Color.Black, blurRadius = 18f))

        val video = rememberCanvas(media?.spotifyTrackId, canvas)
        var videoShown by remember(video) { mutableStateOf(false) }
        var videoFailed by remember(video) { mutableStateOf(false) }
        // The Canvas shows through only once its first frame is up: never onto black.
        val split = video != null && videoShown && !videoFailed
        val blend by animateFloatAsState(if (split) 1f else 0f, tween(900), label = "pipBlend")
        video?.takeIf { !videoFailed }?.let { v ->
            PipCanvas(
                v,
                media,
                calm = alert != null,
                onShown = { videoShown = true },
                onFailed = { videoFailed = true },
                // Its top edge stays under the part of the map that never fades.
                modifier = Modifier.fillMaxSize(),
            )
        }
        DriveMap(
            s,
            radars,
            zones,
            Modifier.fillMaxSize().then(if (blend > 0f) Modifier.verticalFade(MeltStops.map { (y, a) -> y to 1f - blend * (1f - a) }) else Modifier),
            // Split: the car rides in the part of the map that stays.
            carAt = 0.5,
            overlay = PipMapShade,
        )
        Box(Modifier.fillMaxSize().background(PipBottomShade))

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
                        distanceText(distance),
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
                    color = digitsColor,
                    fontSize = (unit * 30).sp,
                    lineHeight = (unit * 30).sp,
                    fontWeight = FontWeight.Bold,
                    style = halo.copy(fontFeatureSettings = "tnum"),
                )
                Text("km/h", color = Color.White.copy(alpha = 0.6f), fontSize = (unit * 7).sp, style = halo)
            }
            if (limit != null) {
                Box(Modifier.padding(bottom = (unit * 3).dp)) {
                    LimitSign(limit, uncertain = s.limitSource.isGuess(), size = (unit * 26).dp)
                }
            }
        }

        // Too fast near a radar: pulsing red frame around the whole window.
        if (danger) {
            Box(Modifier.fillMaxSize().alpha(frame).border((unit * 2.5f).dp, Red, RoundedCornerShape((unit * 4).dp)))
        }
    }
}

/** The map is whole above this height fraction; below, it melts into the Canvas. */

/**
 * Opacity of the map once a Canvas plays behind it: the map stays the window, whole at the top and
 * barely see-through lower down, so the Canvas only colors it from behind, never covering a road.
 */
private val MeltStops = listOf(0f to 1f, 0.35f to 0.92f, 1f to 0.68f)

/** The map, calmer than on the drive screen, darker under the radar pill at the top. */
private val PipMapShade = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.55f),
    0.25f to Color.Black.copy(alpha = 0.2f),
    1f to Color.Black.copy(alpha = 0.25f),
)

/** Over the map and the Canvas alike, so both darken the same way: the speed and the limit stay readable. */
private val PipBottomShade = Brush.verticalGradient(
    0.45f to Color.Transparent,
    0.75f to Color.Black.copy(alpha = 0.45f),
    1f to Color.Black.copy(alpha = 0.88f),
)

/** The Spotify Canvas under the lower part of the map, as dim as the map where they meet. */
@Composable
private fun PipCanvas(video: File, media: MediaInfo?, calm: Boolean, onShown: () -> Unit, onFailed: () -> Unit, modifier: Modifier) {
    var shown by remember(video) { mutableStateOf(false) }
    val opacity by animateFloatAsState(if (!shown) 0f else if (calm) 0.6f else 1f, tween(900), label = "pipCanvas")
    Box(modifier.alpha(opacity)) {
        CanvasVideo(
            video,
            playing = media?.playing == true,
            modifier = Modifier.fillMaxSize(),
            onShown = {
                shown = true
                onShown()
            },
            onFailed = {
                media?.spotifyTrackId?.let { SpotifyCanvas.discard(it, video) }
                onFailed()
            },
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
    }
}
