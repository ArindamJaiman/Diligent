package dev.diligent.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class ComplicationsTest {

    @Test
    fun `today reads what the phone publishes, streak included`() {
        val today = Today.parse("""{"done":3,"total":5,"streak":12,"streakName":"Quantum","items":[{"id":1,"name":"Gym","line":"0 / 1 hrs"}]}""")!!
        assertEquals("3/5", Complications.todayText(today))
        assertEquals("60%", Complications.todayTitle(today))
        assertEquals("🔥12", Complications.streakText(today))
        assertEquals("Quantum", Complications.streakTitle(today))
        assertEquals(1, today.items.size)
    }

    @Test
    fun `an older phone without the streak still parses`() {
        val today = Today.parse("""{"done":0,"total":0,"items":[]}""")!!
        assertEquals(0f, today.share, 0f)
        assertEquals("–", Complications.streakText(today))
        assertEquals("streak", Complications.streakTitle(today))
    }

    @Test
    fun `nothing from the phone yet`() {
        assertEquals("–", Complications.todayText(null))
        assertEquals("Diligent", Complications.todayTitle(null))
    }
}
