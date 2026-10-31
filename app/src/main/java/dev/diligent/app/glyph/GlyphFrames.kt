package dev.diligent.app.glyph

import dev.diligent.app.desk.DotFont
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Frames for the Nothing Phone (3) Glyph Matrix: a 25×25 grid, one brightness (0..1) per LED,
 * row by row. Pure, so the shapes are unit tested and previewed in the app.
 */
object GlyphFrames {

    const val SIZE = 25
    private const val CENTER = (SIZE - 1) / 2.0

    fun blank() = FloatArray(SIZE * SIZE)

    /** A progress ring (clockwise from the top) at [radius], lit up to [fraction]; the rest dim. */
    fun ring(frame: FloatArray, fraction: Float, radius: Double = 11.0, dim: Float = 0.12f): FloatArray {
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val dx = x - CENTER
            val dy = y - CENTER
            if (kotlin.math.abs(hypot(dx, dy) - radius) > 0.6) continue
            // 0 at 12 o'clock, growing clockwise
            val angle = (atan2(dx, -dy) + 2 * PI) % (2 * PI)
            frame[y * SIZE + x] = if (angle / (2 * PI) <= fraction) 1f else dim
        }
        return frame
    }

    /** [text] (digits, ':', '%', 'h', 'm') in the 5×7 dot font, centred. */
    fun text(frame: FloatArray, text: String, brightness: Float = 1f, yOffset: Int = 0): FloatArray {
        val width = DotFont.width(text)
        var left = (SIZE - width) / 2
        val top = (SIZE - DotFont.HEIGHT) / 2 + yOffset
        text.forEach { c ->
            DotFont.cells(c).forEach { (cx, cy) ->
                val x = left + cx
                val y = top + cy
                if (x in 0 until SIZE && y in 0 until SIZE) frame[y * SIZE + x] = brightness
            }
            left += DotFont.WIDTH + 1
        }
        return frame
    }

    /** Running timer: ring = progress through the session, minutes left (or so far) inside. */
    fun timer(minutes: Int, fraction: Float): FloatArray =
        text(ring(blank(), fraction), minutes.coerceIn(0, 99).toString())

    /** Today: ring = share of goals done, the count done inside. */
    fun today(done: Int, total: Int): FloatArray =
        text(ring(blank(), if (total == 0) 0f else done / total.toFloat()), done.coerceIn(0, 99).toString())

    /** A streak number with a full dim ring. */
    fun streak(days: Int): FloatArray = text(ring(blank(), 0f, dim = 0.25f), days.coerceIn(0, 999).toString())

    /** LED values the SDK takes, scaled from 0..1 to 0..[max]. */
    fun toLeds(frame: FloatArray, max: Int): IntArray = IntArray(frame.size) { (frame[it].coerceIn(0f, 1f) * max).toInt() }
}
