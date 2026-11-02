package zenith.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import zenith.core.Gauge
import kotlin.math.cos
import kotlin.math.sin

private val RingRed = Color(0xFFFF453A)

/**
 * The speed in its ring: the arc fills with the speed in the speed's colour, a tick marks the limit
 * (it glides to a new limit with a little overshoot), the limit sign sits in the opening at the
 * bottom. [digits] go in the middle. Measures are those of a 260-wide ring, scaled to [size].
 */
@Composable
fun SpeedRing(
    speedKmh: Int?,
    limitKmh: Int?,
    color: Color,
    size: Dp,
    sign: @Composable () -> Unit,
    digits: @Composable BoxScope.() -> Unit,
) {
    val fill by animateFloatAsState(Gauge.fill(speedKmh, limitKmh), spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow), label = "ringFill")
    val tickAt = Gauge.tick(limitKmh)
    val tick by animateFloatAsState(tickAt ?: 0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessVeryLow), label = "ringTick")
    val tickAlpha by animateFloatAsState(if (tickAt == null) 0f else 1f, tween(400), label = "ringTickAlpha")
    val over = speedKmh != null && limitKmh != null && speedKmh > limitKmh
    val tickColor by animateColorAsState(if (over) Color.White else RingRed, label = "ringTickColor")
    Box(Modifier.width(size).height(size * (268f / 260f))) {
        Canvas(Modifier.width(size).height(size)) {
            val k = this.size.width / 260f
            val r = 112f * k
            val c = Offset(this.size.width / 2, this.size.width / 2)
            val stroke = Stroke(6f * k, cap = StrokeCap.Round)
            val topLeft = Offset(c.x - r, c.y - r)
            val box = Size(r * 2, r * 2)
            drawArc(Color.White.copy(alpha = 0.10f), Gauge.START_DEG, Gauge.SWEEP_DEG, false, topLeft, box, style = stroke)
            if (fill > 0.002f) drawArc(color, Gauge.START_DEG, Gauge.SWEEP_DEG * fill, false, topLeft, box, style = stroke)
            if (tickAlpha > 0f) {
                val a = Math.toRadians((Gauge.START_DEG + Gauge.SWEEP_DEG * tick).toDouble())
                val inner = r - 11f * k
                val outer = r + 11f * k
                drawLine(
                    tickColor.copy(alpha = tickAlpha),
                    Offset(c.x + inner * cos(a).toFloat(), c.y + inner * sin(a).toFloat()),
                    Offset(c.x + outer * cos(a).toFloat(), c.y + outer * sin(a).toFloat()),
                    strokeWidth = 3f * k,
                    cap = StrokeCap.Round,
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = size * (40f / 260f)), contentAlignment = Alignment.TopCenter, content = digits)
        AnimatedVisibility(
            visible = limitKmh != null,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = size * (198f / 260f)),
            enter = fadeIn() + scaleIn(spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow), initialScale = 0.6f),
            exit = fadeOut() + scaleOut(targetScale = 0.6f),
        ) { sign() }
    }
}
