package dev.diligent.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import kotlin.math.cos
import kotlin.math.sin

/**
 * Text whose digits roll like an odometer when they change: each changed character slides up
 * (counting up) or down (counting down) on its own; unchanged ones stay still.
 */
@Composable
fun RollingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    Row(modifier) {
        text.forEachIndexed { index, char ->
            AnimatedContent(
                targetState = char,
                transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically(tween(260)) { if (up) it else -it } + fadeIn(tween(200))) togetherWith
                        (slideOutVertically(tween(260)) { if (up) -it else it } + fadeOut(tween(160)))
                },
                label = "roll$index"
            ) { c ->
                Text(c.toString(), style = style, color = color)
            }
        }
    }
}

/**
 * A burst of dots from the centre, played once each time [key] changes to a new non-null
 * value (a goal just met). Draws nothing at rest.
 */
@Composable
fun DotBurst(key: Any?, color: Color, modifier: Modifier = Modifier, dots: Int = 14) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(key) {
        if (key == null) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        val t = progress.value
        if (t >= 1f) return@Canvas
        val center = Offset(size.width / 2, size.height / 2)
        val reach = minOf(size.width, size.height) * 0.9f
        repeat(dots) { i ->
            val angle = 2 * Math.PI * i / dots
            val r = reach * t
            drawCircle(
                color = color.copy(alpha = (1f - t) * 0.9f),
                radius = 3.5f * (1f - t * 0.6f) * density,
                center = center + Offset((cos(angle) * r).toFloat(), (sin(angle) * r * 0.45f).toFloat())
            )
        }
    }
}
