package dev.diligent.app.leetcode.domain

import dev.diligent.app.leetcode.data.DailyQuestion
import dev.diligent.app.leetcode.data.LeetCodeReview
import dev.diligent.app.leetcode.data.RecentAc
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Spaced repetition for solved problems: each one comes back for a re-solve 1, 3, 7, 21 and
 * 60 days after the previous solve, then retires.
 *
 * Due dates are rounded to the start of a local day, so a problem solved at 23:50 comes up
 * the next morning just like one solved at 08:00. Reviews follow the user's own day, not
 * LeetCode's UTC reset. Pure, so it is unit tested across time zones and DST changes.
 */
object ReviewSchedule {

    val INTERVAL_DAYS: List<Int> = listOf(1, 3, 7, 21, 60)

    /** The stage after the last review: retired, never due again. */
    const val DONE = 5

    const val NEVER = Long.MAX_VALUE

    fun localDay(epochMillis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    fun dayStart(day: LocalDate, zone: ZoneId): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

    /** When a problem last solved at [solvedAt] is next due, once [stage] reviews are done. */
    fun dueAt(solvedAt: Long, stage: Int, zone: ZoneId): Long {
        if (stage >= DONE) return NEVER
        return dayStart(localDay(solvedAt, zone).plusDays(INTERVAL_DAYS[stage.coerceAtLeast(0)].toLong()), zone)
    }

    /** Days until the next review once [stage] reviews are done, or null when retired. */
    fun intervalDays(stage: Int): Int? = INTERVAL_DAYS.getOrNull(stage)

    /**
     * Applies one sync's accepted submissions to the queue and returns only the rows that
     * changed. A slug seen for the first time joins at stage 0; an accept of a problem that is
     * due advances it. Accepts already counted (at or before lastReviewedAt) and accepts made
     * before the problem is due change nothing, so re-reading the same recent list each sync
     * is harmless.
     */
    fun apply(
        existing: Map<String, LeetCodeReview>,
        accepts: List<RecentAc>,
        daily: DailyQuestion?,
        zone: ZoneId
    ): List<LeetCodeReview> {
        val rows = existing.toMutableMap()
        val changed = LinkedHashSet<String>()
        val dailyDifficulty = daily?.difficulty?.ifBlank { null }

        for (ac in accepts.sortedBy { it.timestamp }) {
            val at = ac.timestamp * 1000
            val current = rows[ac.slug]
            val next = if (current == null) {
                LeetCodeReview(
                    slug = ac.slug,
                    title = ac.title,
                    difficulty = dailyDifficulty?.takeIf { daily?.slug == ac.slug },
                    firstSolvedAt = at,
                    stage = 0,
                    dueAt = dueAt(at, 0, zone),
                    lastReviewedAt = at
                )
            } else {
                advance(current, at, zone) ?: continue
            }
            rows[ac.slug] = next
            changed += ac.slug
        }

        // A problem that turns out to be today's daily learns its difficulty.
        if (daily != null && dailyDifficulty != null) {
            val row = rows[daily.slug]
            if (row != null && row.difficulty == null) {
                rows[daily.slug] = row.copy(difficulty = dailyDifficulty)
                changed += daily.slug
            }
        }
        return changed.map { rows.getValue(it) }
    }

    /** The row after a re-accept at [at], or null when that accept doesn't count. */
    fun advance(review: LeetCodeReview, at: Long, zone: ZoneId): LeetCodeReview? {
        if (review.retired || at <= review.lastReviewedAt || at < review.dueAt) return null
        val stage = review.stage + 1
        return review.copy(stage = stage, dueAt = dueAt(at, stage, zone), lastReviewedAt = at)
    }

    /** "Too easy, skip": retired without a re-solve. */
    fun skip(review: LeetCodeReview, now: Long): LeetCodeReview =
        review.copy(stage = DONE, dueAt = NEVER, lastReviewedAt = now)

    /** "Reset": back to stage 0, due again tomorrow. */
    fun reset(review: LeetCodeReview, now: Long, zone: ZoneId): LeetCodeReview =
        review.copy(stage = 0, dueAt = dueAt(now, 0, zone), lastReviewedAt = now)

    /** Whole local days a review is overdue: 0 on the day it falls due. */
    fun daysOverdue(review: LeetCodeReview, now: Long, zone: ZoneId): Long =
        (localDay(now, zone).toEpochDay() - localDay(review.dueAt, zone).toEpochDay()).coerceAtLeast(0)
}
