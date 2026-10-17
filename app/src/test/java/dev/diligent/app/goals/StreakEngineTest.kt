package dev.diligent.app.goals

import dev.diligent.app.data.local.entity.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StreakEngineTest {

    private val today = LocalDate.of(2026, 10, 17)
    private fun days(vararg back: Long) = back.map { today.minusDays(it) }
    private fun range(fromBack: Long, toBack: Long) = (toBack..fromBack).map { today.minusDays(it) }

    @Test
    fun `plain streak without freezes behaves like before`() {
        assertEquals(3, StreakEngine.compute(days(0, 1, 2), today).current)
        assertEquals(2, StreakEngine.compute(days(1, 2), today).current) // today still open
        assertEquals(0, StreakEngine.compute(days(2, 3), today).current) // missed yesterday, no freeze
    }

    @Test
    fun `one freeze covers one missed day, not two`() {
        // 7 done days earn one freeze; then two missed days: the first is covered, the second breaks it.
        val done = range(9, 3) + days(0)
        val s = StreakEngine.compute(done, today)
        assertEquals(1, s.current)
        assertEquals(0, s.freezes)
    }

    @Test
    fun `one missed day after a week keeps the streak`() {
        val done = range(9, 2) + days(0) // 8 days, miss yesterday, done today
        val s = StreakEngine.compute(done, today)
        assertEquals(9, s.current)
        assertEquals(listOf(today.minusDays(1)), s.savedDays)
        assertEquals(0, s.freezes)
    }

    @Test
    fun `freezes cap at two`() {
        val s = StreakEngine.compute(range(29, 0), today)
        assertEquals(30, s.current)
        assertEquals(StreakEngine.MAX_FREEZES, s.freezes)
        assertEquals(0, s.nextFreezeIn)
    }

    @Test
    fun `today not done yet never uses a freeze`() {
        val s = StreakEngine.compute(range(7, 1), today) // 7 days ending yesterday
        assertEquals(7, s.current)
        assertEquals(1, s.freezes)
        assertTrue(s.savedDays.isEmpty())
    }

    private fun activity(goal: Float, unit: String = "hrs") = Activity(id = 1, name = "HFT Preparation", unit = unit, dailyGoal = goal)

    @Test
    fun `coach lowers a goal that is rarely hit`() {
        val values = (1L..14L).associate { today.minusDays(it) to if (it % 2 == 0L) 1.0f else 0.5f }
        val s = GoalCoach.suggest(activity(2f), values, today, today.minusDays(60))!!
        assertFalse(s.raise)
        assertEquals(1.0f, s.suggested, 0.001f)
    }

    @Test
    fun `coach raises a goal that is always beaten`() {
        val values = (1L..14L).associate { today.minusDays(it) to 3f }
        val s = GoalCoach.suggest(activity(2f), values, today, today.minusDays(60))!!
        assertTrue(s.raise)
        assertEquals(3f, s.suggested, 0.001f)
    }

    @Test
    fun `coach stays quiet for a fair goal or too little history`() {
        val fair = (1L..14L).associate { today.minusDays(it) to if (it % 3 == 0L) 1f else 2f }
        assertNull(GoalCoach.suggest(activity(2f), fair, today, today.minusDays(60)))
        assertNull(GoalCoach.suggest(activity(2f), emptyMap(), today, today.minusDays(3)))
    }

    @Test
    fun `nothing logged is not tracking, so no suggestion`() {
        assertNull(GoalCoach.suggest(activity(2f), emptyMap(), today, today.minusDays(60)))
        val twoDays = mapOf(today.minusDays(1) to 0.5f, today.minusDays(2) to 0.5f)
        assertNull(GoalCoach.suggest(activity(2f), twoDays, today, today.minusDays(60)))
    }

    @Test
    fun `coach rounds to sensible steps`() {
        assertEquals(0.25f, GoalCoach.step("hrs"))
        assertEquals(5f, GoalCoach.step("mins"))
        assertEquals(1f, GoalCoach.step("qs"))
        assertEquals(1.25f, GoalCoach.roundTo(1.3f, 0.25f), 0.001f)
    }
}
