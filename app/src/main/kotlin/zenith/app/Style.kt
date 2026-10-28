package zenith.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
