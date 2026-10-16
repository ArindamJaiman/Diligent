package dev.diligent.app.leetcode

import dev.diligent.app.leetcode.data.Contest
import dev.diligent.app.leetcode.data.DailyQuestion
import dev.diligent.app.leetcode.data.LeetCodeConfig
import dev.diligent.app.leetcode.data.LeetCodeSnapshot
import dev.diligent.app.leetcode.data.LeetCodeState
import dev.diligent.app.leetcode.presentation.LeetCodePresenter
import dev.diligent.app.leetcode.presentation.TodayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LeetCodePresenterTest {

    private val ist = ZoneId.of("Asia/Kolkata")
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, ist).toInstant().toEpochMilli()

    private val now = at(17, 21)
    private val config = LeetCodeConfig(username = "coder")
    private val daily = DailyQuestion("2026-10-17", "Trapping Rain Water", "trapping-rain-water", "Hard", "42")

    private fun state(snapshot: LeetCodeSnapshot?) = LeetCodeState(config = config, snapshot = snapshot)

    @Test
    fun `data from before today's reset can't say anything about today`() {
        // Fetched at 04:00 IST on the 17th = the 16th in UTC.
        val stale = LeetCodeSnapshot("coder", fetchedAt = at(17, 4), calendar = mapOf("2026-10-16" to 1),
            daily = daily.copy(date = "2026-10-16"))
        val view = LeetCodePresenter.build(state(stale), now)
        assertEquals(TodayStatus.UNKNOWN, view.today)
        assertNull("yesterday's daily isn't today's", view.daily)
        assertEquals(1, view.streak)
    }

    @Test
    fun `fresh data shows safe or at risk`() {
        val active = LeetCodeSnapshot("coder", fetchedAt = at(17, 20), calendar = mapOf("2026-10-17" to 2), daily = daily)
        assertEquals(TodayStatus.SAFE, LeetCodePresenter.build(state(active), now).today)

        val idle = active.copy(calendar = mapOf("2026-10-16" to 2))
        val view = LeetCodePresenter.build(state(idle), now)
        assertEquals(TodayStatus.AT_RISK, view.today)
        assertEquals(daily, view.daily)
        assertFalse(view.dailySolved)
    }

    @Test
    fun `a cache for another username is not shown`() {
        val other = LeetCodeSnapshot("someone_else", fetchedAt = at(17, 20))
        assertFalse(LeetCodePresenter.build(state(other), now).hasData)
    }

    @Test
    fun `widget redraws at the earlier of contest start and reset`() {
        val startSec = at(18, 0, 30) / 1000 // 00:30 IST, before the 05:30 reset
        val snapshot = LeetCodeSnapshot(
            "coder", fetchedAt = at(17, 20),
            upcoming = listOf(Contest("Weekly Contest 524", "weekly-contest-524", startSec, 5400))
        )
        val view = LeetCodePresenter.build(state(snapshot), now)
        assertEquals(startSec * 1000, LeetCodePresenter.nextBoundary(view, now))
    }

    @Test
    fun `heatmap levels`() {
        assertEquals(listOf(0, 1, 2, 2, 3, 4), listOf(0, 1, 2, 3, 5, 9).map(LeetCodePresenter::level))
    }
}
