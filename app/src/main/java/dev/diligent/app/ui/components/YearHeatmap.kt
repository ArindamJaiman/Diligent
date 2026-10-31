package dev.diligent.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.diligent.app.tracking.HeatDay
import dev.diligent.app.tracking.HeatState
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DotMatrix
import java.time.LocalDate

/**
 * A year of one activity as dots, GitHub-style: Monday-first columns, the newest week on the
 * right (scrolled into view). Tapping a day opens it for editing.
 */
@Composable
fun YearHeatmap(
    weeks: List<List<HeatDay>>,
    selected: LocalDate?,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val cell = 11.dp
    val gap = 3.dp
    val labelHeight = 14.dp
    val density = LocalDensity.current
    val cellPx = with(density) { cell.toPx() }
    val gapPx = with(density) { gap.toPx() }
    val labelPx = with(density) { labelHeight.toPx() }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = DotMatrix, fontSize = 8.sp, color = DiligentColors.Gray600)

    // Starts scrolled to the end: the scroll value is clamped to the content width.
    val scroll = rememberScrollState(Int.MAX_VALUE)
    Column(modifier.horizontalScroll(scroll)) {
        Canvas(
            modifier = Modifier
                .width((cell + gap) * weeks.size)
                .height(labelHeight + (cell + gap) * 7)
                .pointerInput(weeks) {
                    detectTapGestures { offset ->
                        val column = (offset.x / (cellPx + gapPx)).toInt()
                        val row = ((offset.y - labelPx) / (cellPx + gapPx)).toInt()
                        val day = weeks.getOrNull(column)?.getOrNull(row) ?: return@detectTapGestures
                        if (day.state != HeatState.FUTURE) onDayClick(day.date)
                    }
                }
        ) {
            weeks.forEachIndexed { column, week ->
                val x = column * (cellPx + gapPx)
                // Month initial above the first week that starts in it
                val firstDay = week.first().date
                if (firstDay.dayOfMonth <= 7) {
                    drawText(measurer, firstDay.month.name.take(3).lowercase(), Offset(x, 0f), labelStyle)
                }
                week.forEachIndexed { row, day ->
                    val topLeft = Offset(x, labelPx + row * (cellPx + gapPx))
                    val size = Size(cellPx, cellPx)
                    val radius = CornerRadius(cellPx / 4)
                    when (day.state) {
                        HeatState.FUTURE -> Unit
                        HeatState.SKIPPED, HeatState.REST -> drawRoundRect(
                            color = if (day.state == HeatState.SKIPPED) DiligentColors.Gray600 else DiligentColors.Gray300,
                            topLeft = topLeft, size = size, cornerRadius = radius, style = Stroke(width = 1.dp.toPx())
                        )
                        else -> drawRoundRect(color = colorFor(day), topLeft = topLeft, size = size, cornerRadius = radius)
                    }
                    if (day.date == selected) {
                        drawRoundRect(
                            color = DiligentColors.White,
                            topLeft = topLeft - Offset(1.5f, 1.5f),
                            size = Size(cellPx + 3f, cellPx + 3f),
                            cornerRadius = radius,
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }
            }
        }
    }
}

private fun colorFor(day: HeatDay): Color = when (day.state) {
    HeatState.DONE -> DiligentColors.CompletedGreen
    HeatState.OVER -> DiligentColors.OverRed
    HeatState.PARTIAL -> DiligentColors.White.copy(alpha = 0.2f + 0.6f * day.level)
    HeatState.BEFORE -> DiligentColors.Gray100
    else -> DiligentColors.Gray200
}
