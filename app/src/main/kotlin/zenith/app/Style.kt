package zenith.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Translucent "glass" of the drive screen, shared by the music strip, chips, cards and buttons. */
val Glass = Color(0xA61F1F24)

/** Hairline that catches the light on the edge of a glass surface. */
private val GlassEdge = Color.White.copy(alpha = 0.09f)

/** A glass surface of [shape]: clipped, translucent fill, light hairline edge. */
fun Modifier.glass(shape: Shape, color: Color = Glass): Modifier =
    clip(shape).background(color).border(0.5.dp, GlassEdge, shape)

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
