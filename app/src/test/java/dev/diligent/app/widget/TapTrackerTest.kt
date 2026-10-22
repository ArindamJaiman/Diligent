package dev.diligent.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapTrackerTest {

    @Test
    fun `two taps inside the window are a double tap`() {
        val taps = TapTracker(400)
        assertFalse(taps.tap(1_000).isDouble)
        assertTrue(taps.tap(1_350).isDouble)
    }

    @Test
    fun `two slow taps are two single taps`() {
        val taps = TapTracker(400)
        assertFalse(taps.tap(1_000).isDouble)
        assertFalse(taps.tap(1_401).isDouble)
    }

    @Test
    fun `a third quick tap starts a new pair`() {
        val taps = TapTracker(400)
        val results = listOf(1_000L, 1_200L, 1_300L, 1_450L).map { taps.tap(it).isDouble }
        assertEquals(listOf(false, true, false, true), results)
    }

    @Test
    fun `a double tap cancels the single tap's pending action`() {
        val taps = TapTracker(400)
        val first = taps.tap(1_000)
        assertTrue(taps.isLatest(first)) // nothing else yet: the app would open
        val second = taps.tap(1_200)
        assertTrue(second.isDouble)
        assertFalse(taps.isLatest(first)) // so it doesn't
    }

    @Test
    fun `the clock going backwards is never a double tap`() {
        val taps = TapTracker(400)
        taps.tap(5_000)
        assertFalse(taps.tap(4_900).isDouble)
    }
}
