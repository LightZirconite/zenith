package zenith.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Translucent "glass" of the drive screen, shared by the music strip, chips, cards and buttons. */
val Glass = Color(0xA61F1F24)

/**
 * The rim of a glass surface, as liquid glass catches the light: bright on the upper left edge,
 * almost gone along the middle, a softer glint on the lower right.
 */
private val GlassRim = Brush.linearGradient(
    0f to Color.White.copy(alpha = 0.3f),
    0.35f to Color.White.copy(alpha = 0.07f),
    0.7f to Color.White.copy(alpha = 0.05f),
    1f to Color.White.copy(alpha = 0.16f),
)

/** Light catching the top of a glass surface. */
private val GlassSheen = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.09f), 0.5f to Color.Transparent)

/** Light under the finger on a dark surface; a white surface darkens a little instead ([PressShade]). */
val PressLight = Color.White.copy(alpha = 0.2f)
val PressShade = Color.Black.copy(alpha = 0.1f)

/** A glass surface of [shape]: clipped, translucent fill lit from above, a rim catching the light. */
fun Modifier.glass(shape: Shape, color: Color = Glass): Modifier =
    clip(shape).background(color).background(GlassSheen).border(0.75.dp, GlassRim, shape)

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
    val collapseAtPx = with(LocalDensity.current) { 56.dp.toPx() }
    Box(Modifier.fillMaxSize()) {
        SpaceBackdrop(Modifier.fillMaxSize())
        // Tablet or car screen: a readable column in the middle rather than rows across the whole width.
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxHeight().widthIn(max = PAGE_MAX_WIDTH).fillMaxWidth()
                .safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            // Once the large title has gone into the bar, the back button follows it there.
            val collapsed by remember { derivedStateOf { scroll.value > collapseAtPx / 2 } }
            Box(Modifier.fillMaxWidth().height(44.dp)) {
                androidx.compose.animation.AnimatedVisibility(
                    collapsed,
                    Modifier.align(Alignment.CenterStart),
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                ) { leading() }
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
                // The back button beside the large title ("‹ Réglages"), as the author asked.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.graphicsLayer {
                        val k = collapse(scroll)
                        alpha = 1f - k
                        val s = 1f - 0.12f * k
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    },
                ) {
                    leading()
                    Spacer(Modifier.width(12.dp))
                    Text(title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                content()
            }
            bottom()
        }
    }
}

/** Widest a page or the drive screen's column grows on a tablet or a car screen. */
val PAGE_MAX_WIDTH = 640.dp

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
 * Clickable the liquid-glass way, instead of the Material ripple: under the finger the surface
 * swells toward it ([lift]; below 1 it sinks, for big rows) and lights up from the touch point
 * ([glow], within [shape]); let go, it springs back with a little bounce. Each press has its
 * [sound] and a light tick of the phone's touch feedback ([UiSound]); a long press opens.
 * Put it before the surface's own clip and background so the whole surface moves.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tap(
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    sound: UiSound.Kind? = UiSound.Kind.TAP,
    lift: Float = 1.05f,
    glow: Color = PressLight,
    interaction: MutableInteractionSource? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val view = LocalView.current
    return combinedClickable(
        interactionSource = interaction,
        indication = LiquidPress(shape, lift, glow),
        enabled = enabled,
        onLongClick = onLongClick?.let { long ->
            {
                UiSound.play(UiSound.Kind.OPEN, view, long = true)
                long()
            }
        },
        // The long press's buzz comes from UiSound, with its sound: not twice.
        hapticFeedbackEnabled = false,
        onClick = {
            sound?.let { UiSound.play(it, view) }
            onClick()
        },
    )
}

/**
 * Fades the content out along its height: [stops] are (height fraction, opacity), 1 whole, 0 gone.
 * A mask on its own layer, not a dark band: what lies beneath shows through, as in a crossfade.
 */
fun Modifier.verticalFade(stops: List<Pair<Float, Float>>): Modifier =
    fadeMask(Brush.verticalGradient(*stops.map { (y, a) -> y to Color.Black.copy(alpha = a.coerceIn(0f, 1f)) }.toTypedArray()))

/** Same as [verticalFade], across the width: [stops] are (width fraction, opacity). */
fun Modifier.horizontalFade(stops: List<Pair<Float, Float>>): Modifier =
    fadeMask(Brush.horizontalGradient(*stops.map { (x, a) -> x to Color.Black.copy(alpha = a.coerceIn(0f, 1f)) }.toTypedArray()))

private fun Modifier.fadeMask(mask: Brush): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(mask, blendMode = BlendMode.DstIn)
        }

/** Stop by stop, [from] at 0 to [to] at 1; both lists the same length. */
fun blendStops(from: List<Pair<Float, Float>>, to: List<Pair<Float, Float>>, k: Float): List<Pair<Float, Float>> =
    from.zip(to) { (y0, a0), (y1, a1) -> (y0 + (y1 - y0) * k) to (a0 + (a1 - a0) * k) }

private data class LiquidPress(val shape: Shape, val lift: Float, val glow: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = LiquidPressNode(interactionSource, shape, lift, glow)
}

private class LiquidPressNode(
    private val interactions: InteractionSource,
    private val shape: Shape,
    private val lift: Float,
    private val glow: Color,
) : Modifier.Node(), DrawModifierNode {
    private val swell = Animatable(1f)
    private val light = Animatable(0f)
    private var at = Offset.Unspecified

    override fun onAttach() {
        coroutineScope.launch {
            interactions.interactions.collect {
                when (it) {
                    is PressInteraction.Press -> {
                        at = it.pressPosition
                        launch { swell.animateTo(lift, spring(dampingRatio = 0.7f, stiffness = 520f)) }
                        launch { light.animateTo(1f, tween(120)) }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        // A quick tap still shows: the release carries on toward the press, then bounces back.
                        val quick = light.value < 0.6f
                        launch {
                            swell.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 380f), initialVelocity = if (quick) (lift - 1f) * 16f else 0f)
                        }
                        launch {
                            if (quick) light.animateTo(0.8f, tween(70))
                            light.animateTo(0f, tween(480))
                        }
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val k = light.value
        scale(swell.value) {
            this@draw.drawContent()
            if (k > 0.01f) {
                val center = if (at.isSpecified) at else this.center
                drawOutline(
                    shape.createOutline(size, layoutDirection, this),
                    Brush.radialGradient(
                        listOf(glow.copy(alpha = glow.alpha * k), glow.copy(alpha = glow.alpha * k * 0.35f), Color.Transparent),
                        center = center,
                        radius = size.maxDimension * 0.85f,
                    ),
                )
            }
        }
    }
}

/** A chevron pointing back (‹), drawn: no icon library needed. */
@Composable
fun Chevron(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = 2.4.dp.toPx()
        val path = Path().apply {
            moveTo(size.width - w / 2, w / 2)
            lineTo(w / 2, size.height / 2)
            lineTo(size.width - w / 2, size.height - w / 2)
        }
        drawPath(path, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * The iOS switch: the knob springs across and the track turns green; held down ([interaction]
 * pressed), the knob stretches toward where it will go.
 */
@Composable
fun GlassSwitch(on: Boolean, interaction: MutableInteractionSource? = null) {
    val pressed = interaction?.collectIsPressedAsState()?.value == true
    val track by animateColorAsState(if (on) SwitchOn else SwitchOff, tween(250), label = "track")
    val width by animateDpAsState(if (pressed) 33.dp else 27.dp, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium), label = "knobWidth")
    val x by animateDpAsState(if (on) 47.dp - width else 0.dp, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow), label = "knobX")
    Box(Modifier.size(width = 51.dp, height = 31.dp).clip(RoundedCornerShape(50)).background(track).padding(2.dp)) {
        Box(
            Modifier.offset(x = x).size(width = width, height = 27.dp).shadow(3.dp, RoundedCornerShape(50))
                .background(Color.White, RoundedCornerShape(50)),
        )
    }
}

private val SwitchOn = Color(0xFF30D158)
private val SwitchOff = Color(0xFF39393D)
