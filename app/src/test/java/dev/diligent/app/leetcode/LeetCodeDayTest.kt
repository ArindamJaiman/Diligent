package dev.diligent.app.leetcode

import dev.diligent.app.leetcode.data.DailyQuestion
import dev.diligent.app.leetcode.data.LeetCodeSnapshot
import dev.diligent.app.leetcode.data.RecentAc
import dev.diligent.app.leetcode.domain.LeetCodeDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LeetCodeDayTest {

    private val ist = ZoneId.of("Asia/Kolkata")

    /** 2026-10-17 21:00 IST = 15:30 UTC, so the LeetCode day is 2026-10-17. */
    private val now = ZonedDateTime.of(2026, 10, 17, 21, 0, 0, 0, ist).toInstant().toEpochMilli()

    private fun utcSeconds(date: String, hour: Int) =
        ZonedDateTime.of(java.time.LocalDate.parse(date), java.time.LocalTime.of(hour, 0), ZoneId.of("UTC")).toEpochSecond()

    private fun snapshot(
        calendar: Map<String, Int> = emptyMap(),
        recent: List<RecentAc> = emptyList(),
        daily: DailyQuestion? = null
    ) = LeetCodeSnapshot(username = "u", fetchedAt = now, calendar = calendar, recentAc = recent, daily = daily)

    @Test
    fun `streak includes today when active`() {
        val s = snapshot(mapOf("2026-10-15" to 1, "2026-10-16" to 2, "2026-10-17" to 1))
        assertEquals(3, LeetCodeDay.currentStreak(s, now))
    }

    @Test
    fun `streak is still alive from yesterday while today is open`() {
        val s = snapshot(mapOf("2026-10-15" to 1, "2026-10-16" to 2))
        assertEquals(2, LeetCodeDay.currentStreak(s, now))
        assertFalse(LeetCodeDay.activeToday(s, now))
    }

    @Test
    fun `a missed day breaks the streak`() {
        val s = snapshot(mapOf("2026-10-14" to 1, "2026-10-15" to 2))
        assertEquals(0, LeetCodeDay.currentStreak(s, now))
    }

    @Test
    fun `a recent accept counts as activity even before the calendar shows it`() {
        val s = snapshot(
            calendar = mapOf("2026-10-16" to 1),
            recent = listOf(RecentAc("Two Sum", "two-sum", utcSeconds("2026-10-17", 9)))
        )
        assertTrue(LeetCodeDay.activeToday(s, now))
        assertEquals(2, LeetCodeDay.currentStreak(s, now))
    }

    @Test
    fun `solved today uses the UTC day, not the local one`() {
        // 02:00 IST on the 17th is still the 16th in UTC.
        val lateNight = ZonedDateTime.of(2026, 10, 17, 2, 0, 0, 0, ist).toEpochSecond()
        val s = snapshot(
            recent = listOf(
                RecentAc("A", "a", lateNight),
                RecentAc("B", "b", utcSeconds("2026-10-17", 10)),
                RecentAc("B", "b", utcSeconds("2026-10-17", 11)) // same problem twice
            )
        )
        assertEquals(1, LeetCodeDay.solvedToday(s, now))
        // ...while the local (IST) day counts both problems for the Diligent tracker.
        assertEquals(2, LeetCodeDay.solvedLocalToday(s, now, ist))
    }

    @Test
    fun `daily challenge counts only when solved since the reset`() {
        val daily = DailyQuestion("2026-10-17", "Trapping Rain Water", "trapping-rain-water", "Hard", "42")
        val yesterday = snapshot(
            recent = listOf(RecentAc("Trapping Rain Water", "trapping-rain-water", utcSeconds("2026-10-16", 20))),
            daily = daily
        )
        assertFalse(LeetCodeDay.dailySolved(yesterday, now))

        val today = snapshot(
            recent = listOf(RecentAc("Trapping Rain Water", "trapping-rain-water", utcSeconds("2026-10-17", 12))),
            daily = daily
        )
        assertTrue(LeetCodeDay.dailySolved(today, now))
    }

    @Test
    fun `daily-only guard ignores other submissions`() {
        val daily = DailyQuestion("2026-10-17", "Trapping Rain Water", "trapping-rain-water", "Hard", "42")
        val s = snapshot(calendar = mapOf("2026-10-17" to 4), daily = daily)
        assertTrue(LeetCodeDay.streakSafe(s, now, dailyOnly = false))
        assertFalse(LeetCodeDay.streakSafe(s, now, dailyOnly = true))
    }

    @Test
    fun `next reset is the coming UTC midnight`() {
        val reset = ZonedDateTime.of(2026, 10, 18, 5, 30, 0, 0, ist).toInstant().toEpochMilli()
        assertEquals(reset, LeetCodeDay.nextResetMillis(now))
        assertEquals("8h 30m", LeetCodeDay.formatLeft(reset - now))
    }
}
