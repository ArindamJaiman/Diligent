package dev.diligent.app.quicklog

import dev.diligent.app.data.local.entity.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class QuickLogTest {

    private val today = LocalDate.of(2026, 10, 31)
    private val quantum = Activity(id = 1, name = "Quantum Computing", unit = "hrs", dailyGoal = 5f)
    private val leetcode = Activity(id = 2, name = "LeetCode", unit = "qs", dailyGoal = 3f)
    private val hft = Activity(id = 3, name = "HFT Preparation", unit = "hrs", dailyGoal = 2f)
    private val reading = Activity(id = 4, name = "Reading", unit = "mins", dailyGoal = 30f)
    private val gym = Activity(id = 5, name = "Gym", unit = "hrs", dailyGoal = 1f)
    private val all = listOf(quantum, leetcode, hft, reading, gym)

    private fun parse(s: String) = QuickLog.parse(s, all, today)

    @Test
    fun `durations convert to the activity's unit`() {
        assertEquals(QuickCommand.Add(quantum, 2f, today), parse("2h quantum"))
        assertEquals(QuickCommand.Add(reading, 30f, today.minusDays(1)), parse("reading 30 min yesterday"))
        assertEquals(QuickCommand.Add(hft, 0.75f, today), parse("45m hft"))
        assertEquals(QuickCommand.Add(reading, 120f, today), parse("log 2 hours of reading"))
    }

    @Test
    fun `bare numbers are in the activity's unit`() {
        assertEquals(QuickCommand.Add(leetcode, 3f, today), parse("+3 lc"))
        assertEquals(QuickCommand.Add(quantum, 1.5f, today), parse("quantum 1,5"))
        assertEquals(QuickCommand.Set(gym, 1f, today), parse("set gym 1"))
        assertEquals(QuickCommand.Add(leetcode, 1f, today), parse("leetcode"))
    }

    @Test
    fun `verbs`() {
        assertEquals(QuickCommand.Done(gym, today), parse("gym done"))
        assertEquals(QuickCommand.Skip(gym, today.minusDays(1)), parse("skip gym yesterday"))
        assertEquals(QuickCommand.Focus(quantum, 50, false), parse("focus quantum 50m"))
        assertEquals(QuickCommand.Focus(hft, 0, true), parse("pomodoro hft"))
    }

    @Test
    fun `fuzzy matching`() {
        assertEquals(hft, QuickLog.match("hft", all))
        assertEquals(leetcode, QuickLog.match("lc", all))
        assertEquals(quantum, QuickLog.match("computing", all))
        assertEquals(quantum, QuickLog.match("qc", all))
        assertNull(QuickLog.match("zzz", all))
        assertNull(parse("2h"))
    }

    @Test
    fun `confirmation text`() {
        assertEquals("+2 hrs · Quantum Computing", QuickLog.describe(QuickCommand.Add(quantum, 2f, today), today))
        assertTrue(QuickLog.describe(QuickCommand.Add(reading, 30f, today.minusDays(1)), today).endsWith("(yesterday)"))
    }
}
