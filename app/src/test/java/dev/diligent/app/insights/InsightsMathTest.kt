package dev.diligent.app.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class InsightsMathTest {

    private val start = LocalDate.of(2026, 10, 1)

    @Test
    fun `sleep splits completion`() {
        val days = (0 until 20).map { i ->
            val slept = if (i % 2 == 0) 8.0 else 5.5
            DayFacts(start.plusDays(i.toLong()), completion = if (slept >= 7) 0.9 else 0.4, hours = 3.0, sleepHours = slept)
        }
        val c = Correlations.all(days).first()
        assertEquals("sleep", c.factor)
        assertEquals(0.9, c.high, 1e-9)
        assertEquals(0.4, c.low, 1e-9)
        assertTrue(c.r > 0.9)
        assertTrue(c.sentence().contains("90%"))
    }

    @Test
    fun `too little data says nothing`() {
        val days = (0 until 5).map { DayFacts(start.plusDays(it.toLong()), 0.5, 1.0, sleepHours = 7.0 + it) }
        assertTrue(Correlations.all(days).isEmpty())
        assertNull(Correlations.split(days, "x", { it.sleepHours }, 7.0, "a", "b"))
    }

    @Test
    fun `companion activities`() {
        val days = (0 until 20).map { i ->
            val gym = i % 2 == 0
            DayFacts(start.plusDays(i.toLong()), 0.5, 1.0, done = if (gym) setOf(1L, 2L) else if (i % 5 == 0) setOf(2L) else emptySet())
        }
        val lines = Correlations.companions(days, mapOf(1L to "Gym", 2L to "Quantum"))
        assertTrue(lines.any { it.startsWith("On Gym days you also do Quantum 100%") })
    }

    @Test
    fun `readiness and burnout`() {
        val calm = (0 until 35).map { DayFacts(start.plusDays(it.toLong()), 0.8, 3.0, sleepHours = 8.0, mood = 4) }
        val r = ReadinessMath.compute(calm, sleepDebtMinutes = 0)
        assertTrue("well rested should score high, was ${r.score}", r.score >= 80)
        assertFalse(r.burnoutRisk)
        assertEquals(1.0, r.load, 1e-9)

        val surge = (0 until 28).map { DayFacts(start.plusDays(it.toLong()), 0.8, 2.0, sleepHours = 7.0) } +
            (0 until 7).map { DayFacts(start.plusDays(28L + it), 0.6, 5.0, sleepHours = 5.0, mood = 2) }
        val tired = ReadinessMath.compute(surge, sleepDebtMinutes = 8 * 60)
        assertTrue(tired.burnoutRisk)
        assertTrue(tired.score < r.score)
        assertTrue(tired.reasons.any { it.startsWith("short sleep") })
    }

    @Test
    fun `levels and mastery`() {
        assertEquals(1 to 0f, Achievements.level(50))
        assertEquals(2, Achievements.level(200).first)
        val life = Lifetime(bestStreak = 31, hoursBySkill = mapOf("Quantum" to 120.0), sessions = 12, intactInARow = 3,
            perfectWeeks = 0, earlySessions = 5, lateSessions = 0, completions = 40, daysTracked = 60)
        val list = Achievements.list(life)
        assertTrue(list.first { it.id == "streak_30" }.earned)
        assertFalse(list.first { it.id == "streak_100" }.earned)
        val mastery = list.first { it.id == "mastery_Quantum" }
        assertTrue(mastery.earned)
        assertEquals("120 of 1,000 h", mastery.detail)
        assertEquals(1200 + 200 + 24, Achievements.xp(life))
    }

    @Test
    fun `period compare`() {
        val rows = PeriodCompare.compare(mapOf(1L to 12.0, 2L to 0.0), mapOf(1L to 10.0, 2L to 4.0), mapOf(1L to ("Quantum" to "hrs"), 2L to ("Gym" to "hrs"), 3L to ("Idle" to "hrs")))
        assertEquals(listOf("Quantum", "Gym"), rows.map { it.name })
        assertNotNull(rows[0].change)
        assertEquals(0.2, rows[0].change!!, 1e-9)
    }
}
