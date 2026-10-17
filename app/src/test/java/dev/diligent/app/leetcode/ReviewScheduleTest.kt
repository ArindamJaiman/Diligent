package dev.diligent.app.leetcode

import dev.diligent.app.leetcode.data.DailyQuestion
import dev.diligent.app.leetcode.data.LeetCodeReview
import dev.diligent.app.leetcode.data.RecentAc
import dev.diligent.app.leetcode.domain.ReviewSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ReviewScheduleTest {

    private val ist = ZoneId.of("Asia/Kolkata")
    private val pacific = ZoneId.of("America/Los_Angeles")
    private val utc = ZoneOffset.UTC

    private fun at(zone: ZoneId, month: Int, day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun local(millis: Long, zone: ZoneId = ist) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime().toString()

    private fun ac(slug: String, millis: Long) = RecentAc(title = slug, slug = slug, timestamp = millis / 1000)

    private fun first(slug: String, millis: Long, daily: DailyQuestion? = null) =
        ReviewSchedule.apply(emptyMap(), listOf(ac(slug, millis)), daily, ist).single()

    // ─── Stage → due date ───────────────────────────────────────

    @Test
    fun `stages come back after 1, 3, 7, 21 and 60 days at local midnight`() {
        val solved = at(ist, 10, 17, 14, 30)
        assertEquals(
            listOf("2026-10-18T00:00", "2026-10-20T00:00", "2026-10-24T00:00", "2026-11-07T00:00", "2026-12-16T00:00"),
            (0 until ReviewSchedule.DONE).map { local(ReviewSchedule.dueAt(solved, it, ist)) }
        )
        assertEquals(listOf(1, 3, 7, 21, 60), (0 until ReviewSchedule.DONE).map { ReviewSchedule.intervalDays(it) })
    }

    @Test
    fun `a retired problem is never due`() {
        assertEquals(ReviewSchedule.NEVER, ReviewSchedule.dueAt(at(ist, 10, 17, 9), ReviewSchedule.DONE, ist))
        assertNull(ReviewSchedule.intervalDays(ReviewSchedule.DONE))
    }

    // ─── Local-day rounding ─────────────────────────────────────

    @Test
    fun `late-night and early-morning solves come up the same next morning`() {
        val early = ReviewSchedule.dueAt(at(ist, 10, 17, 0, 10), 0, ist)
        val late = ReviewSchedule.dueAt(at(ist, 10, 17, 23, 50), 0, ist)
        assertEquals(early, late)
        assertEquals("2026-10-18T00:00", local(late))
    }

    @Test
    fun `rounds to the user's local day, not LeetCode's UTC day`() {
        // 03:00 IST on the 18th is still the 17th in UTC.
        val solved = at(ist, 10, 18, 3)
        assertEquals("2026-10-19T00:00", local(ReviewSchedule.dueAt(solved, 0, ist), ist))
        assertEquals("2026-10-18T00:00", local(ReviewSchedule.dueAt(solved, 0, utc), utc))
    }

    @Test
    fun `across the DST change the due time stays at local midnight`() {
        // US clocks fall back on Sunday 1 Nov 2026, so those three days are 73 hours long.
        val due = ReviewSchedule.dueAt(at(pacific, 10, 31, 20), 1, pacific)
        assertEquals("2026-11-03T00:00", local(due, pacific))
        assertEquals(73, Duration.ofMillis(due - at(pacific, 10, 31, 0)).toHours())
    }

    @Test
    fun `due from the first moment of its day, late counted in local days`() {
        val review = first("two-sum", at(ist, 10, 17, 21))
        assertFalse(review.isDue(at(ist, 10, 17, 23, 59)))
        assertTrue(review.isDue(at(ist, 10, 18, 0)))
        assertEquals(0, ReviewSchedule.daysOverdue(review, at(ist, 10, 18, 23), ist))
        assertEquals(2, ReviewSchedule.daysOverdue(review, at(ist, 10, 20, 8), ist))
    }

    // ─── Applying a sync ────────────────────────────────────────

    @Test
    fun `a newly seen problem joins at stage 0`() {
        val solved = at(ist, 10, 17, 21)
        val review = first("two-sum", solved)
        assertEquals(0, review.stage)
        assertEquals(solved, review.firstSolvedAt)
        assertEquals(solved, review.lastReviewedAt)
        assertEquals("2026-10-18T00:00", local(review.dueAt))
        assertNull(review.difficulty)
    }

    @Test
    fun `re-accepting a due problem advances it, counted from the re-solve day`() {
        val solved = at(ist, 10, 17, 21)
        val start = first("two-sum", solved)
        val resolved = at(ist, 10, 18, 9)
        val next = ReviewSchedule.apply(
            mapOf("two-sum" to start), listOf(ac("two-sum", resolved), ac("two-sum", solved)), null, ist
        ).single()
        assertEquals(1, next.stage)
        assertEquals(resolved, next.lastReviewedAt)
        assertEquals(solved, next.firstSolvedAt)
        assertEquals("2026-10-21T00:00", local(next.dueAt))
    }

    @Test
    fun `re-accepting before it is due changes nothing`() {
        val start = first("two-sum", at(ist, 10, 17, 21))
        val changed = ReviewSchedule.apply(mapOf("two-sum" to start), listOf(ac("two-sum", at(ist, 10, 17, 23))), null, ist)
        assertTrue(changed.isEmpty())
    }

    @Test
    fun `reading the same recent list again is harmless`() {
        val list = listOf(ac("lru-cache", at(ist, 10, 18, 9)), ac("lru-cache", at(ist, 10, 17, 21)))
        val once = ReviewSchedule.apply(emptyMap(), list, null, ist)
        assertEquals(1, once.single().stage)
        assertTrue(ReviewSchedule.apply(once.associateBy { it.slug }, list, null, ist).isEmpty())
    }

    @Test
    fun `several accepts in one sync are replayed oldest first`() {
        // Solved on the 1st, re-solved on the 2nd (due), again on the 3rd (3-day review not due until the 5th).
        val review = ReviewSchedule.apply(
            emptyMap(),
            listOf(ac("lru-cache", at(ist, 10, 3, 10)), ac("lru-cache", at(ist, 10, 1, 20)), ac("lru-cache", at(ist, 10, 2, 8))),
            null,
            ist
        ).single()
        assertEquals(1, review.stage)
        assertEquals(at(ist, 10, 1, 20), review.firstSolvedAt)
        assertEquals(at(ist, 10, 2, 8), review.lastReviewedAt)
        assertEquals("2026-10-05T00:00", local(review.dueAt))
    }

    @Test
    fun `retires after the 60-day review and ignores later accepts`() {
        val dueAt = at(ist, 12, 1, 0)
        val last = LeetCodeReview("two-sum", "Two Sum", null, at(ist, 8, 1, 9), stage = 4, dueAt = dueAt, lastReviewedAt = at(ist, 10, 2, 9))
        val retired = ReviewSchedule.advance(last, dueAt + 3_600_000, ist)!!
        assertEquals(ReviewSchedule.DONE, retired.stage)
        assertEquals(ReviewSchedule.NEVER, retired.dueAt)
        assertTrue(retired.retired)
        assertFalse(retired.isDue(Long.MAX_VALUE - 1))
        assertNull(ReviewSchedule.advance(retired, dueAt + 90_000_000, ist))
    }

    @Test
    fun `the daily challenge supplies the difficulty`() {
        val daily = DailyQuestion("2026-10-17", "Trapping Rain Water", "trapping-rain-water", "Hard", "42")
        assertEquals("Hard", first("trapping-rain-water", at(ist, 10, 17, 21), daily).difficulty)
        assertNull(first("two-sum", at(ist, 10, 17, 21), daily).difficulty)

        // Already queued without a difficulty: filled in even without a new accept.
        val known = first("trapping-rain-water", at(ist, 10, 17, 21))
        val filled = ReviewSchedule.apply(mapOf(known.slug to known), listOf(ac(known.slug, known.firstSolvedAt)), daily, ist)
        assertEquals("Hard", filled.single().difficulty)
        assertEquals(0, filled.single().stage)
    }

    // ─── Buttons ────────────────────────────────────────────────

    @Test
    fun `too easy, skip retires the problem for good`() {
        val solved = at(ist, 10, 17, 21)
        val skipped = ReviewSchedule.skip(first("two-sum", solved), at(ist, 10, 18, 10))
        assertTrue(skipped.retired)
        assertFalse(skipped.isDue(at(ist, 12, 31, 0)))
        val later = ReviewSchedule.apply(mapOf("two-sum" to skipped), listOf(ac("two-sum", at(ist, 10, 25, 9))), null, ist)
        assertTrue(later.isEmpty())
    }

    @Test
    fun `reset starts over with a 1-day review from today`() {
        val solved = at(ist, 10, 1, 21)
        val stage3 = first("two-sum", solved).copy(stage = 3, dueAt = at(ist, 10, 15, 0), lastReviewedAt = at(ist, 9, 24, 9))
        val now = at(ist, 10, 20, 15)
        val reset = ReviewSchedule.reset(stage3, now, ist)
        assertEquals(0, reset.stage)
        assertEquals(now, reset.lastReviewedAt)
        assertEquals(solved, reset.firstSolvedAt)
        assertEquals("2026-10-21T00:00", local(reset.dueAt))
        // An accept from before the reset doesn't count as tomorrow's review.
        assertNull(ReviewSchedule.advance(reset, at(ist, 10, 20, 9), ist))
        assertEquals(1, ReviewSchedule.advance(reset, at(ist, 10, 21, 9), ist)!!.stage)
    }
}
