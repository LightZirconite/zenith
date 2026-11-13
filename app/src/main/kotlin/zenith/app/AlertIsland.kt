package zenith.app

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import zenith.core.ActiveAlert
import zenith.core.DriveSnapshot
import zenith.core.Island
import zenith.core.SectionStatus
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val IslandRed = Color(0xFFFF453A)
private val IslandOrange = Color(0xFFFF9F0A)
private val IslandGreen = Color(0xFF32D74B)
private val IslandBlue = Color(0xFF3D8BFF)
private val IslandDim = Color(0xFFEBEBF5).copy(alpha = 0.6f)
private val IslandPale = Color(0xFFFFE1DC).copy(alpha = 0.8f)

/** What the island holds: the data is kept with it, so a shrinking island still shows its last words. */
private sealed interface IslandContent {
    data object None : IslandContent
    data class Approach(val alert: ActiveAlert, val progress: Float, val detail: String?) : IslandContent
    data class SlowDown(val alert: ActiveAlert, val progress: Float, val over: String?) : IslandContent
    data class Section(val section: SectionStatus) : IslandContent
    data class Passed(val flash: Boolean) : IslandContent
}

/**
 * The alert island: radars, sections and passes are not cards on the drive screen any more, a black
 * island grows out of the camera's punch hole and says what matters now (the same as the
 * iPhone's, which grows out of its Dynamic Island). The rules are [Island]'s, shared with the iPhone.
 * Laid over the whole screen (edge to edge); [passedAtMs] changes at each radar or section passed.
 */
@Composable
fun AlertIsland(
    s: DriveSnapshot?,
    passedAtMs: Long,
    passedFlash: Boolean,
    landscape: Boolean,
    modifier: Modifier = Modifier,
    /** The island starts or stops saying something: the status bar steps aside meanwhile. */
    onShown: (Boolean) -> Unit = {},
) {
    val alert = s?.alerts?.firstOrNull()
    var passedShown by remember { mutableStateOf(false) }
    LaunchedEffect(passedAtMs) {
        if (passedAtMs <= 0L) return@LaunchedEffect
        passedShown = true
        delay(Island.PASSED_MS)
        passedShown = false
    }
    // Distance at first sight of this radar, for the approach bar.
    var start by remember(alert?.radar?.id) { mutableFloatStateOf(alert?.distanceM?.toFloat() ?: 0f) }
    if (alert != null && alert.distanceM > start) start = alert.distanceM.toFloat()
    val state = Island.state(alert != null, alert?.overLimit == true, s?.section != null, if (passedShown) 0L else -1L)
    val progress = alert?.let { Island.progress(it.distanceM, start.toDouble()) } ?: 0f
    val up = state != Island.State.NONE
    LaunchedEffect(up) { onShown(up) }
    DisposableEffect(Unit) { onDispose { onShown(false) } }
    val content: IslandContent = when (state) {
        Island.State.APPROACH -> IslandContent.Approach(alert!!, progress, detail(alert, s))
        Island.State.SLOW_DOWN -> IslandContent.SlowDown(alert!!, progress, Island.overText(s?.speedKmh, alert.limitKmh))
        Island.State.SECTION -> IslandContent.Section(s!!.section!!)
        Island.State.PASSED -> IslandContent.Passed(passedFlash)
        Island.State.NONE -> IslandContent.None
    }

    // Where the camera is: the island is born there. A punch hole off to a side (or none, or
    // before Android 9): the island sits at the top in the middle.
    val view = LocalView.current
    val density = LocalDensity.current
    val hole = remember(view, landscape) {
        if (Build.VERSION.SDK_INT < 28) null
        else view.rootWindowInsets?.displayCutout?.boundingRects
            ?.firstOrNull { it.top <= 0 && it.width() < view.width / 3 && kotlin.math.abs(it.centerX() - view.width / 2) < view.width / 8 }
    }
    val holeCenterY = with(density) { hole?.exactCenterY()?.toDp() } ?: 22.dp
    // The screen's own rounded corner: the grown island keeps its corners inside it. Before
    // Android 12 the radius is unknown: a cautious one (a square screen only gets a little lower).
    val screenCorner = remember(view) {
        if (Build.VERSION.SDK_INT >= 31) {
            with(density) { (view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0).toDp() }
        } else {
            32.dp
        }
    }
    val wide = if (landscape) 420.dp else 370.dp

    BoxWithConstraints(modifier.fillMaxSize()) {
        val bigWidth = minOf(maxWidth - 24.dp, wide)
        val (w, h, r) = when (state) {
            Island.State.NONE -> Triple(30.dp, 30.dp, 15.dp)
            Island.State.APPROACH -> Triple(bigWidth, 88.dp, 30.dp)
            Island.State.SLOW_DOWN -> Triple(bigWidth, 136.dp, 32.dp)
            Island.State.SECTION -> Triple(bigWidth, 110.dp, 32.dp)
            Island.State.PASSED -> Triple(230.dp, 37.dp, 19.dp)
        }
        val grow = spring<Dp>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
        val width by animateDpAsState(w, grow, label = "islandW")
        val height by animateDpAsState(h, grow, label = "islandH")
        val radius by animateDpAsState(r, grow, label = "islandR")
        val shown by animateFloatAsState(if (state == Island.State.NONE) 0f else 1f, tween(if (state == Island.State.NONE) 450 else 120), label = "islandAlpha")
        val halo by animateColorAsState(
            when (state) {
                Island.State.APPROACH -> IslandOrange
                Island.State.SLOW_DOWN -> IslandRed
                Island.State.SECTION -> IslandBlue
                Island.State.PASSED -> if (passedFlash) IslandRed else IslandGreen
                Island.State.NONE -> Color.Transparent
            },
            tween(400),
            label = "islandHalo",
        )
        val top = maxOf((holeCenterY - 15.dp).coerceAtLeast(4.dp), cornerSafeTop(maxWidth, width, radius, screenCorner))
        val shape = RoundedCornerShape(radius)
        val glow = if (state == Island.State.SLOW_DOWN) 0.5f else 0.32f
        Box(
            Modifier
                .offset { IntOffset(((maxWidth - width) / 2).roundToPx(), top.roundToPx()) }
                .size(width, height)
                .alpha(shown)
                // Its colour as a soft light under it, no outline: the island stays a clean black shape.
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            0f to halo.copy(alpha = halo.alpha * glow),
                            1f to Color.Transparent,
                            center = Offset(size.width / 2, size.height * 0.75f),
                            radius = size.width * 0.62f,
                        ),
                        topLeft = Offset(-size.width * 0.15f, 0f),
                        size = Size(size.width * 1.3f, size.height + size.width * 0.35f),
                    )
                }
                .background(Brush.verticalGradient(listOf(Color(0xFF111115), Color.Black)), shape)
                .border(0.5.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.1f), Color.Transparent)), shape)
                .clip(shape)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = describe(content)
                },
        ) {
            AnimatedContent(
                content,
                contentKey = { it::class },
                transitionSpec = { fadeIn(tween(220, delayMillis = 140)) togetherWith fadeOut(tween(120)) },
                label = "islandContent",
            ) { shownContent ->
                when (shownContent) {
                    is IslandContent.Approach -> Approach(shownContent)
                    is IslandContent.SlowDown -> SlowDown(shownContent)
                    is IslandContent.Section -> SectionUnderWay(shownContent.section)
                    is IslandContent.Passed -> Passed(shownContent.flash)
                    IslandContent.None -> Box(Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * Lowest top for an island [w] wide with corners of radius [r] on a screen [screen] wide whose own
 * corners have radius [corner]: the island's corner arcs stay inside the screen's, 6 dp clear.
 */
private fun cornerSafeTop(screen: Dp, w: Dp, r: Dp, corner: Dp): Dp {
    val big = corner.value
    val arc = r.value
    val clear = big - arc - 6f
    val dx = big - ((screen.value - w.value) / 2 + arc)
    if (big <= 0f || dx <= 0f) return 0.dp
    if (clear <= dx) return (big - arc).coerceAtLeast(0f).dp
    return (big - arc - sqrt(clear * clear - dx * dx)).coerceAtLeast(0f).dp
}

private fun detail(a: ActiveAlert, s: DriveSnapshot?): String? = when {
    a.ageMs != null -> "signalé il y a ${(a.ageMs!! / 60_000).coerceAtLeast(1)} min"
    s?.road?.segment?.name != null -> "sur ${s.road!!.segment.name}"
    a.radar.directionDeg == null -> "sens non vérifié"
    else -> null
}

private fun describe(c: IslandContent): String = when (c) {
    is IslandContent.Approach -> "${c.alert.radar.type.label} dans ${distanceText(c.alert.distanceM)}"
    is IslandContent.SlowDown -> "Ralentis, ${c.alert.radar.type.label} dans ${distanceText(c.alert.distanceM)}"
    is IslandContent.Section -> "Tronçon, moyenne ${c.section.averageKmh.roundToInt()} km/h"
    is IslandContent.Passed -> if (c.flash) "Trop vite au radar" else "Radar passé"
    IslandContent.None -> ""
}

/** A radar's little camera in its colour. */
@Composable
private fun RadarTile(color: Color, size: Dp = 26.dp) {
    Box(Modifier.size(size).background(color, RoundedCornerShape(size * 0.3f)), contentAlignment = Alignment.Center) {
        Icon(Glyphs.camera, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.62f))
    }
}

@Composable
private fun Distance(m: Double) {
    val text = distanceText(m)
    val number = text.substringBeforeLast(' ')
    val unit = text.substringAfterLast(' ')
    Row(verticalAlignment = Alignment.Bottom) {
        Text(number, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        Text(" $unit", color = IslandDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
private fun Bar(progress: Float, color: Color, modifier: Modifier) {
    val p by animateFloatAsState(progress, tween(300), label = "islandBar")
    Box(modifier.height(5.dp).background(Color.White.copy(alpha = 0.14f), CircleShape)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(color, CircleShape))
    }
}

/** The top row of a radar: its camera and kind at the left, the distance at the right. */
@Composable
private fun RadarRow(a: ActiveAlert) {
    Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        RadarTile(Color(radarColorArgb(a.radar.type)))
        Spacer(Modifier.width(9.dp))
        Text(a.radar.type.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Distance(a.distanceM)
    }
}

@Composable
private fun Approach(c: IslandContent.Approach) {
    Column(Modifier.fillMaxSize()) {
        RadarRow(c.alert)
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp).height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            c.detail?.let {
                Text(it, color = IslandDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(12.dp))
            }
            Bar(c.progress, IslandOrange, Modifier.weight(1f))
            c.alert.limitKmh?.let {
                Spacer(Modifier.width(12.dp))
                LimitSign(it, uncertain = false, size = 28.dp)
            }
        }
    }
}

@Composable
private fun SlowDown(c: IslandContent.SlowDown) {
    Column(Modifier.fillMaxSize()) {
        RadarRow(c.alert)
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ralentis", color = IslandRed, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, maxLines = 1)
                c.over?.let { Text(it, color = IslandPale, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
            }
            c.alert.limitKmh?.let {
                Box(Modifier.size(64.dp).background(IslandRed.copy(alpha = 0.22f), CircleShape), contentAlignment = Alignment.Center) {
                    LimitSign(it, uncertain = false, size = 52.dp)
                }
            }
        }
        Bar(c.progress, IslandRed, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp))
    }
}

@Composable
private fun SectionUnderWay(s: SectionStatus) {
    val slowDown = s.lost || s.targetKmh < s.limitKmh
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).background(Color(0xFFBF5AF2), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Icon(Glyphs.section, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(9.dp))
            Text("Tronçon", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("encore ", color = IslandDim, fontSize = 13.sp)
            Distance(s.remainingM)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(50.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${s.averageKmh.roundToInt()} km/h", color = if (slowDown) IslandRed else Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    when {
                        s.lost -> "Moyenne trop haute : elle finira au-dessus de ${s.limitKmh}"
                        slowDown -> "Pas plus de ${s.targetKmh} jusqu'au bout"
                        else -> "Moyenne bonne : roule jusqu'à ${s.limitKmh}"
                    },
                    color = IslandDim,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            LimitSign(s.limitKmh, uncertain = false, size = 40.dp)
        }
        val progress = (1 - s.remainingM / s.lengthM).toFloat().coerceIn(0f, 1f)
        Bar(progress, Color.White, Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp))
    }
}

@Composable
private fun Passed(flash: Boolean) {
    val color = if (flash) IslandRed else IslandGreen
    Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.size(22.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Glyphs.check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(13.dp))
        }
        Text(if (flash) "Trop vite au radar" else "Radar passé", color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
