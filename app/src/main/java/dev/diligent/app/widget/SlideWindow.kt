package dev.diligent.app.widget

/**
 * Which developer slides go into one GitHub widget update.
 *
 * Android rejects a widget update whose bitmaps add up to more than 1.5 screens of ARGB
 * pixels, and Glance may send one layout per widget size (portrait and landscape) in the
 * same update. Every slide carries its own grid bitmap, so a long list can't all go in at
 * once: the widget shows the slides that fit within [budgetBytes], starting at the current
 * developer, and the header tap moves on from there.
 */
internal object SlideWindow {

    /** Cap on slides per update, also keeping PendingIntent request codes in a fixed range. */
    const val MAX_SLIDES = 24

    /** A quarter of the framework limit: room for two size layouts with headroom to spare. */
    fun budgetBytes(screenWidthPx: Int, screenHeightPx: Int): Long =
        screenWidthPx.toLong() * screenHeightPx * 4L * 3L / 2L / 4L

    /** All slide indices in slideshow order, starting at [start]. */
    fun order(total: Int, start: Int): List<Int> {
        if (total <= 0) return emptyList()
        val first = start.mod(total)
        return List(total) { (first + it) % total }
    }

    /** Whether the slide at [position] in the window still fits; the first one always does. */
    fun fits(position: Int, usedBytes: Long, slideBytes: Long, budget: Long): Boolean =
        position == 0 || (position < MAX_SLIDES && usedBytes + slideBytes <= budget)
}
