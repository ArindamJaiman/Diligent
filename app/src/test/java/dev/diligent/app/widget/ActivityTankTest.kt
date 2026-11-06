package dev.diligent.app.widget

import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.tracking.Goals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityTankTest {

    private fun activity(goal: Float = 3f, unit: String = "hrs", goalType: String = Goals.AT_LEAST) =
        Activity(id = 1, name = "Quantum", unit = unit, dailyGoal = goal, goalType = goalType)

    @Test
    fun `partial progress fills by share`() {
        val look = ActivityTank.look(activity(), 1.5f, skipped = false, completed = false)
        assertEquals(0.5f, look.fill, 0.001f)
        assertEquals("1.5/3 hrs", look.label)
        assertEquals("50%", look.badge)
        assertFalse(look.over)
    }

    @Test
    fun `a met or ticked goal is full`() {
        assertEquals("done", ActivityTank.look(activity(), 3f, skipped = false, completed = false).badge)
        val ticked = ActivityTank.look(activity(), 1f, skipped = false, completed = true)
        assertEquals(1f, ticked.fill, 0f)
        assertEquals("done", ticked.badge)
    }

    @Test
    fun `a limit fills towards its cap and turns red past it`() {
        val under = ActivityTank.look(activity(60f, "mins", Goals.AT_MOST), 45f, skipped = false, completed = false)
        assertEquals(0.75f, under.fill, 0.001f)
        assertEquals("45/60 mins max", under.label)
        assertFalse(under.over)
        val over = ActivityTank.look(activity(60f, "mins", Goals.AT_MOST), 90f, skipped = false, completed = false)
        assertEquals(1f, over.fill, 0f)
        assertTrue(over.over)
        assertEquals("150%", over.badge)
    }

    @Test
    fun `quit is empty while clean and red after a slip`() {
        val clean = ActivityTank.look(activity(0f, "times", Goals.QUIT), 0f, skipped = false, completed = false)
        assertEquals(0f, clean.fill, 0f)
        assertEquals("clean", clean.label)
        val slip = ActivityTank.look(activity(0f, "times", Goals.QUIT), 2f, skipped = false, completed = false)
        assertTrue(slip.over)
        assertEquals("slip · 2 times", slip.label)
    }

    @Test
    fun `a skipped day is empty`() {
        val look = ActivityTank.look(activity(), 2f, skipped = true, completed = false)
        assertEquals(0f, look.fill, 0f)
        assertEquals("skipped", look.label)
    }
}
