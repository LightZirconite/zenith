package zenith.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Translucent "glass" of the drive screen, shared by the music strip, chips, cards and buttons. */
val Glass = Color(0xA61F1F24)

/** Hairline that catches the light on the edge of a glass surface. */
private val GlassEdge = Color.White.copy(alpha = 0.09f)

/** Light catching the top of a glass surface. */
private val GlassSheen = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.07f), 0.55f to Color.Transparent)

/** A glass surface of [shape]: clipped, translucent fill lit from above, light hairline edge. */
fun Modifier.glass(shape: Shape, color: Color = Glass): Modifier =
    clip(shape).background(color).background(GlassSheen).border(0.5.dp, GlassEdge, shape)

/** A band of light crossing the surface once as it appears, like glass catching the sun. After the clip. */
@Composable
fun Modifier.sheen(): Modifier {
    val pass = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pass.animateTo(1f, tween(1_100, delayMillis = 150, easing = FastOutSlowInEasing)) }
    return drawWithContent {
        drawContent()
        val k = pass.value
        if (k <= 0f || k >= 1f) return@drawWithContent
        val x = size.width * (-0.4f + 1.8f * k)
        val band = size.width * 0.3f
        drawRect(
            Brush.linearGradient(
                0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = 0.2f),
                1f to Color.Transparent,
                start = Offset(x - band, 0f),
                end = Offset(x + band, size.height),
            ),
        )
    }
}

/** Arrives in turn with the rest of its screen: rises a little and fades in, [index] × 50 ms after the first. */
@Composable
fun Modifier.cascade(index: Int): Modifier {
    val k = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(80L + 50L * index)
        k.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow))
    }
    return graphicsLayer {
        val v = k.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * 36.dp.toPx()
        val s = 0.97f + 0.03f * v
        scaleX = s
        scaleY = s
    }
}

/**
 * A full page the iOS way over the sky of home: a large [title] that shrinks into the bar as the
 * page scrolls, [leading] in the bar (a back button), [bottom] pinned under the scrolling [content].
 */
@Composable
fun LargeTitlePage(
    title: String,
    leading: @Composable () -> Unit = {},
    bottom: @Composable () -> Unit = {},
    scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        SpaceBackdrop(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Box(Modifier.fillMaxWidth().height(44.dp)) {
                Box(Modifier.align(Alignment.CenterStart)) { leading() }
                Text(
                    title,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center).graphicsLayer {
                        val k = collapse(scroll)
                        alpha = k
                        translationY = (1f - k) * 10.dp.toPx()
                    },
                )
            }
            Column(Modifier.weight(1f).verticalScroll(scroll)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    title,
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.graphicsLayer {
                        val k = collapse(scroll)
                        alpha = 1f - k
                        val s = 1f - 0.12f * k
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    },
                )
                content()
            }
            bottom()
        }
    }
}

/** Folds the status bar (time, battery) away while [hidden]; a swipe from the top shows it a moment. */
@Composable
fun ImmersiveBars(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(hidden) {
        val bars = view.context.findActivity()?.window?.let { WindowCompat.getInsetsController(it, view) }
        if (hidden) {
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            bars?.show(WindowInsetsCompat.Type.statusBars())
        }
        onDispose { bars?.show(WindowInsetsCompat.Type.statusBars()) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** How far the large title has gone into the bar: 0 at the top of the page, 1 after 56 dp of scroll. */
private fun Density.collapse(scroll: ScrollState): Float =
    (scroll.value / 56.dp.toPx()).coerceIn(0f, 1f)

/**
 * Clickable that sinks a little under the finger and springs back, instead of the Material ripple.
 * Put it before the surface's own clip and background so the whole surface moves.
 */
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(interactionSource = null, indication = PressScale, enabled = enabled, onClick = onClick)

/** [tap] with a long press as well. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tap(onLongClick: () -> Unit, onClick: () -> Unit): Modifier =
    combinedClickable(interactionSource = null, indication = PressScale, onLongClick = onLongClick, onClick = onClick)

/**
 * Fades the content out along its height: [stops] are (height fraction, opacity), 1 whole, 0 gone.
 * A mask on its own layer, not a dark band: what lies beneath shows through, as in a crossfade.
 */
fun Modifier.verticalFade(stops: List<Pair<Float, Float>>): Modifier {
    val mask = Brush.verticalGradient(*stops.map { (y, a) -> y to Color.Black.copy(alpha = a.coerceIn(0f, 1f)) }.toTypedArray())
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(mask, blendMode = BlendMode.DstIn)
        }
}

/** Stop by stop, [from] at 0 to [to] at 1; both lists the same length. */
fun blendStops(from: List<Pair<Float, Float>>, to: List<Pair<Float, Float>>, k: Float): List<Pair<Float, Float>> =
    from.zip(to) { (y0, a0), (y1, a1) -> (y0 + (y1 - y0) * k) to (a0 + (a1 - a0) * k) }

private object PressScale : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressScaleNode(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 0x5ca1e
}

private class PressScaleNode(private val interactions: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private val press = Animatable(1f)

    override fun onAttach() {
        coroutineScope.launch {
            interactions.interactions.collectLatest {
                when (it) {
                    is PressInteraction.Press -> press.animateTo(0.96f, spring(stiffness = Spring.StiffnessMedium))
                    is PressInteraction.Release, is PressInteraction.Cancel ->
                        press.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow))
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        scale(press.value) { this@draw.drawContent() }
    }
}
