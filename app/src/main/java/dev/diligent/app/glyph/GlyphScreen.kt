package dev.diligent.app.glyph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.diligent.app.tools.ToolCard
import dev.diligent.app.tools.ToolChip
import dev.diligent.app.tools.ToolColors
import dev.diligent.app.tools.ToolNote
import dev.diligent.app.tools.ToolScaffold
import dev.diligent.app.tools.toolLabel
import dev.diligent.app.ui.theme.DiligentColors

/** What the back of a Phone (3) shows, previewed on any phone. */
@Composable
fun GlyphScreen(onBack: () -> Unit) {
    var view by remember { mutableIntStateOf(0) }
    val frame = when (view) {
        0 -> GlyphFrames.timer(23, 0.55f)
        1 -> GlyphFrames.today(4, 7)
        else -> GlyphFrames.streak(31)
    }
    ToolScaffold(title = "Glyph Matrix", onBack = onBack) {
        ToolCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("FOCUS", "TODAY", "STREAK").forEachIndexed { i, label -> ToolChip(label, view == i) { view = i } }
            }
            Spacer(Modifier.height(12.dp))
            MatrixPreview(frame, Modifier.fillMaxWidth().aspectRatio(1f))
        }
        ToolCard {
            val ready = GlyphBridge.sdkPresent()
            Text(
                when {
                    !GlyphBridge.isNothingPhone() -> "This isn't a Nothing phone: the preview above is what a Phone (3) shows."
                    ready -> "Glyph SDK found. Pick Diligent in Settings → Glyph Interface → Glyph Toys."
                    else -> "Add Nothing's Glyph Matrix SDK to the build to light the back (docs/GLYPH.md)."
                },
                style = toolLabel(11.sp), color = if (ready) ToolColors.Good else DiligentColors.White
            )
            ToolNote("Focus: ring fills over the planned length, minutes left inside. Today: goals done. Long press the Glyph button for your streak.")
        }
    }
}

@Composable
fun MatrixPreview(frame: FloatArray, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val size = GlyphFrames.SIZE
        val pitch = minOf(this.size.width, this.size.height) / size
        for (y in 0 until size) for (x in 0 until size) {
            // The Phone (3) matrix is round: skip the corners outside the circle.
            val dx = x - 12
            val dy = y - 12
            if (dx * dx + dy * dy > 13 * 13) continue
            val v = frame[y * size + x]
            drawCircle(
                color = if (v > 0f) Color.White.copy(alpha = 0.15f + 0.85f * v) else Color(0xFF1A1A1A),
                radius = pitch * 0.36f,
                center = Offset(x * pitch + pitch / 2, y * pitch + pitch / 2)
            )
        }
    }
}
