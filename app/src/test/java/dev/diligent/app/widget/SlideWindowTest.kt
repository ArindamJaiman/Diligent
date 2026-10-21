package dev.diligent.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideWindowTest {

    @Test
    fun `slideshow order starts at the current developer and wraps`() {
        assertEquals(listOf(3, 4, 0, 1, 2), SlideWindow.order(5, 3))
        assertEquals(listOf(0, 1, 2), SlideWindow.order(3, 0))
        assertEquals(listOf(1, 2, 0), SlideWindow.order(3, 7)) // a stale index from a longer list
        assertEquals(emptyList<Int>(), SlideWindow.order(0, 2))
    }

    @Test
    fun `budget is a quarter of the launcher's bitmap limit`() {
        // 1080 x 2400 screen: the framework allows 1.5 screens of ARGB, about 15.5 MB.
        assertEquals(3_888_000L, SlideWindow.budgetBytes(1080, 2400))
    }

    /** How many slides of [slideBytes] each go into one update. */
    private fun fitted(count: Int, slideBytes: Long, budget: Long): Int {
        var used = 0L
        var shown = 0
        for (position in 0 until count) {
            if (!SlideWindow.fits(position, used, slideBytes, budget)) break
            used += slideBytes
            shown++
        }
        return shown
    }

    @Test
    fun `a long list is cut to what fits, a short one is shown whole`() {
        val budget = SlideWindow.budgetBytes(1080, 2400)
        assertEquals(5, fitted(20, 773_000L, budget)) // 4x2 widget grids at 2.625x density
        assertEquals(3, fitted(3, 773_000L, budget))
    }

    @Test
    fun `the first slide always shows, and the window never passes the cap`() {
        assertTrue(SlideWindow.fits(0, 0L, Long.MAX_VALUE, 1L))
        assertFalse(SlideWindow.fits(1, 1L, 1L, 1L))
        assertEquals(SlideWindow.MAX_SLIDES, fitted(100, 0L, 1L)) // never-synced slides carry no bitmap
    }
}
