package dev.diligent.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import dev.diligent.app.data.repository.GithubContributionFetcher.ParsedContribution
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * GitHub-style colour themes shared by both GitHub widgets.
 */
internal object GithubTheme {

    private val gridPalettes = mapOf(
        "emerald" to intArrayOf(0xFF161B22.toInt(), 0xFF0E4429.toInt(), 0xFF006D32.toInt(), 0xFF26A641.toInt(), 0xFF39D353.toInt()),
        "crimson" to intArrayOf(0xFF161B22.toInt(), 0xFF4C0519.toInt(), 0xFF881337.toInt(), 0xFFBE123C.toInt(), 0xFFF43F5E.toInt()),
        "blue" to intArrayOf(0xFF161B22.toInt(), 0xFF172554.toInt(), 0xFF1E3A8A.toInt(), 0xFF3B82F6.toInt(), 0xFF00D2FF.toInt()),
        "gold" to intArrayOf(0xFF161B22.toInt(), 0xFF451A03.toInt(), 0xFF78350F.toInt(), 0xFFD97706.toInt(), 0xFFFBBF24.toInt()),
        "purple" to intArrayOf(0xFF161B22.toInt(), 0xFF3B0764.toInt(), 0xFF581C87.toInt(), 0xFF8B5CF6.toInt(), 0xFFD8B4FE.toInt())
    )

    private val accents = mapOf(
        "emerald" to 0xFF4ADE80.toInt(),
        "crimson" to 0xFFF43F5E.toInt(),
        "blue" to 0xFF3B82F6.toInt(),
        "gold" to 0xFFFBBF24.toInt(),
        "purple" to 0xFFA855F7.toInt()
    )

    fun palette(name: String, fallback: String = "emerald"): IntArray =
        gridPalettes[name] ?: gridPalettes.getValue(fallback)

    fun accent(name: String, fallback: String = "emerald"): Int =
        accents[name] ?: accents.getValue(fallback)
}

/**
 * Builds and draws contribution calendars.
 *
 * The grid is drawn into a single bitmap instead of hundreds of Glance boxes:
 * Glance only renders the first 10 children of a Row/Column, which previously cut
 * the calendar down to the 5 oldest weeks and 5 days, and a single image is far
 * cheaper for the launcher to inflate.
 */
internal object ContributionGrid {

    /** Marks a day after today; it is left blank like on GitHub. */
    const val FUTURE = -1

    data class Geometry(val weeks: Int, val cellDp: Float, val gapDp: Float) {
        val widthDp: Float get() = weeks * cellDp + (weeks - 1) * gapDp
        val heightDp: Float get() = 7 * cellDp + 6 * gapDp
    }

    /**
     * Picks the cell size and number of weeks that fit the available space.
     */
    fun fit(
        availableWidthDp: Float,
        availableHeightDp: Float,
        preferredCellDp: Float,
        gapDp: Float,
        minCellDp: Float,
        maxCellDp: Float,
        minWeeks: Int,
        maxWeeks: Int
    ): Geometry {
        val maxVerticalCell = (availableHeightDp - gapDp * 6) / 7f
        val cell = minOf(preferredCellDp, maxVerticalCell).coerceIn(minCellDp, maxCellDp)
        val weeks = ((availableWidthDp + gapDp) / (cell + gapDp)).toInt().coerceIn(minWeeks, maxWeeks)
        return Geometry(weeks, cell, gapDp)
    }

    fun levelsByDate(contributions: List<ParsedContribution>): Map<String, Int> =
        contributions.associate { it.date to it.level }

    /**
     * Returns [weeks] columns (oldest first), each holding 7 levels from Sunday to Saturday.
     * The last column is the current week.
     */
    fun buildWeeks(levels: Map<String, Int>, weeks: Int, today: LocalDate = LocalDate.now()): List<IntArray> {
        val offsetToSunday = today.dayOfWeek.value % 7 // Mon=1 … Sat=6, Sun=0
        val startSunday = today.minusDays(offsetToSunday.toLong()).minusWeeks((weeks - 1).toLong())

        return List(weeks) { w ->
            IntArray(7) { d ->
                val date = startSunday.plusDays((w * 7 + d).toLong())
                if (date.isAfter(today)) FUTURE else levels[date.toString()] ?: 0
            }
        }
    }

    /**
     * Draws the grid at exact device pixels so the launcher shows it without scaling.
     */
    fun render(columns: List<IntArray>, palette: IntArray, geometry: Geometry, density: Float, densityDpi: Int): Bitmap {
        val cellPx = max(2, (geometry.cellDp * density).roundToInt())
        val gapPx = max(1, (geometry.gapDp * density).roundToInt())
        val cornerPx = max(1f, density * 1.5f)
        val weeks = max(1, columns.size)

        val width = weeks * cellPx + (weeks - 1) * gapPx
        val height = 7 * cellPx + 6 * gapPx
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.density = densityDpi

        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF()

        columns.forEachIndexed { w, days ->
            days.forEachIndexed { d, level ->
                if (level == FUTURE) return@forEachIndexed
                paint.color = palette[level.coerceIn(0, palette.lastIndex)]
                val left = (w * (cellPx + gapPx)).toFloat()
                val top = (d * (cellPx + gapPx)).toFloat()
                rect.set(left, top, left + cellPx, top + cellPx)
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)
            }
        }
        return bitmap
    }

    fun formatAge(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val elapsed = (now - timestamp).coerceAtLeast(0)
        return when {
            elapsed < 60_000 -> "just now"
            elapsed < 3_600_000 -> "${elapsed / 60_000}m ago"
            elapsed < 86_400_000 -> "${elapsed / 3_600_000}h ago"
            else -> "${elapsed / 86_400_000}d ago"
        }
    }
}
