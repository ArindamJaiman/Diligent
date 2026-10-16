package dev.diligent.app.leetcode.domain

import dev.diligent.app.leetcode.data.LeetCodeSnapshot
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * LeetCode's day runs on UTC: the daily challenge, the submission calendar and the streak
 * all roll over at 00:00 UTC (05:30 in India, 17:00/16:00 on the US west coast).
 */
object LeetCodeDay {

    fun utcDay(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC).toLocalDate()

    fun dayStartMillis(day: LocalDate): Long = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** The next UTC midnight strictly after [now]. */
    fun nextResetMillis(now: Long): Long = dayStartMillis(utcDay(now).plusDays(1))

    /** UTC days with at least one submission, from the calendar plus the recent accepted list. */
    fun activeDays(snapshot: LeetCodeSnapshot): Set<LocalDate> {
        val days = HashSet<LocalDate>()
        snapshot.calendar.forEach { (date, count) ->
            if (count > 0) runCatching { LocalDate.parse(date) }.getOrNull()?.let(days::add)
        }
        snapshot.recentAc.forEach { days.add(utcDay(it.timestamp * 1000)) }
        return days
    }

    /**
     * Days in a row with a submission, ending today — or yesterday while today is still open.
     * Same rule as the streak on LeetCode's profile calendar.
     */
    fun currentStreak(snapshot: LeetCodeSnapshot, now: Long): Int {
        val active = activeDays(snapshot)
        var day = utcDay(now)
        if (day !in active) day = day.minusDays(1)
        var streak = 0
        while (day in active) {
            streak++
            day = day.minusDays(1)
        }
        return streak
    }

    /** Distinct problems accepted since [fromMillis] (only the last 20 accepts are public). */
    fun solvedSince(snapshot: LeetCodeSnapshot, fromMillis: Long): Int =
        snapshot.recentAc.filter { it.timestamp * 1000 >= fromMillis }.map { it.slug }.distinct().size

    fun solvedToday(snapshot: LeetCodeSnapshot, now: Long): Int =
        solvedSince(snapshot, dayStartMillis(utcDay(now)))

    /** Problems accepted during the local calendar day — what the Diligent tracker counts. */
    fun solvedLocalToday(snapshot: LeetCodeSnapshot, now: Long, zone: ZoneId): Int {
        val start = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        return solvedSince(snapshot, start)
    }

    fun activeToday(snapshot: LeetCodeSnapshot, now: Long): Boolean = utcDay(now) in activeDays(snapshot)

    /** True when today's daily challenge appears in the recent accepted list since the reset. */
    fun dailySolved(snapshot: LeetCodeSnapshot, now: Long): Boolean {
        val daily = snapshot.daily ?: return false
        if (daily.date.isNotBlank() && daily.date != utcDay(now).toString()) return false
        val start = dayStartMillis(utcDay(now))
        return snapshot.recentAc.any { it.slug == daily.slug && it.timestamp * 1000 >= start }
    }

    /**
     * Whether the streak guard is satisfied for the current UTC day. The snapshot must come
     * from today's LeetCode day; an older snapshot can't prove anything about today.
     */
    fun streakSafe(snapshot: LeetCodeSnapshot, now: Long, dailyOnly: Boolean): Boolean =
        if (dailyOnly) dailySolved(snapshot, now) else activeToday(snapshot, now)

    fun formatLeft(millis: Long): String {
        val minutes = (millis / 60_000).coerceAtLeast(0)
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }
}

/**
 * When the streak guard checks LeetCode. Pure, so it is unit tested across time zones.
 *
 * Each local day gets a check at the user's chosen time. With repeats on, it re-checks every
 * [REPEAT_EVERY] (for up to [REPEAT_WINDOW]) while the day is unsolved, plus a last call
 * [LAST_CALL_BEFORE] the UTC reset — but repeats never ring during [QUIET_START]–[QUIET_END]
 * local time. The chosen time itself is always honoured.
 */
object StreakSchedule {

    val REPEAT_EVERY: Duration = Duration.ofHours(2)
    val REPEAT_WINDOW: Duration = Duration.ofHours(6)
    val LAST_CALL_BEFORE: Duration = Duration.ofMinutes(60)
    private val REPEAT_MARGIN: Duration = Duration.ofMinutes(90)
    val QUIET_START: LocalTime = LocalTime.MIDNIGHT
    val QUIET_END: LocalTime = LocalTime.of(7, 0)

    fun checksFor(localDate: LocalDate, guard: LocalTime, repeat: Boolean, zone: ZoneId): List<ZonedDateTime> {
        val first = localDate.atTime(guard).atZone(zone)
        if (!repeat) return listOf(first)

        val reset = Instant.ofEpochMilli(LeetCodeDay.nextResetMillis(first.toInstant().toEpochMilli())).atZone(zone)
        val lastCall = reset.minus(LAST_CALL_BEFORE)
        val times = mutableListOf(first)

        // Repeats stay within REPEAT_WINDOW of the chosen time and stop well before the
        // last call, so two alerts never land minutes apart.
        val windowEnd = first.plus(REPEAT_WINDOW)
        val marginEnd = lastCall.minus(REPEAT_MARGIN)
        val repeatUntil = if (windowEnd.isBefore(marginEnd)) windowEnd else marginEnd
        var next = first.plus(REPEAT_EVERY)
        while (!next.isAfter(repeatUntil)) {
            if (!isQuiet(next)) times += next
            next = next.plus(REPEAT_EVERY)
        }
        if (lastCall.isAfter(first.plusMinutes(30)) && !isQuiet(lastCall)) times += lastCall
        return times
    }

    /**
     * The next check strictly after [now], skipping UTC days at or before [doneThrough]
     * (already solved, or muted with "done for today").
     */
    fun next(
        now: ZonedDateTime,
        guard: LocalTime,
        repeat: Boolean,
        doneThrough: LocalDate?
    ): ZonedDateTime {
        val zone = now.zone
        val today = now.toLocalDate()
        for (offset in -1L..3L) {
            val candidate = checksFor(today.plusDays(offset), guard, repeat, zone)
                .filter { it.isAfter(now) }
                .firstOrNull { check ->
                    val day = LeetCodeDay.utcDay(check.toInstant().toEpochMilli())
                    doneThrough == null || day.isAfter(doneThrough)
                }
            if (candidate != null) return candidate
        }
        // Unreachable for real zones; fall back to tomorrow's chosen time.
        return today.plusDays(1).atTime(guard).atZone(zone)
    }

    private fun isQuiet(time: ZonedDateTime): Boolean {
        val t = time.toLocalTime()
        return !t.isBefore(QUIET_START) && t.isBefore(QUIET_END)
    }
}
