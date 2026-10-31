package dev.diligent.app.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphFramesTest {

    private fun at(frame: FloatArray, x: Int, y: Int) = frame[y * GlyphFrames.SIZE + x]

    @Test
    fun `ring fills clockwise from the top`() {
        val quarter = GlyphFrames.ring(GlyphFrames.blank(), 0.25f)
        assertEquals(1f, at(quarter, 12, 1), 0f)   // 12 o'clock
        assertEquals(1f, at(quarter, 23, 12), 0f)  // 3 o'clock, end of the quarter
        assertEquals(0.12f, at(quarter, 12, 23), 1e-6f) // 6 o'clock still dim
        assertEquals(0f, at(quarter, 12, 12), 0f)  // centre untouched
    }

    @Test
    fun `digits are centred and scaled for the SDK`() {
        val frame = GlyphFrames.text(GlyphFrames.blank(), "7")
        val lit = frame.indices.filter { frame[it] > 0f }.map { it % GlyphFrames.SIZE }
        assertEquals(10, lit.minOrNull())
        assertEquals(14, lit.maxOrNull())
        val leds = GlyphFrames.toLeds(GlyphFrames.today(3, 6), GlyphBridge.MAX_BRIGHTNESS)
        assertEquals(625, leds.size)
        assertTrue(leds.any { it == GlyphBridge.MAX_BRIGHTNESS })
    }
}
