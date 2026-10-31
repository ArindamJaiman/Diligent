package dev.diligent.app.planning

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A stretch of time, epoch millis [start, end). */
data class Slot(val start: Long, val end: Long) {
    val minutes: Int get() = ((end - start) / 60_000).toInt()
}

/** Something to fit into the day: [minutes] of [activityId], [deep] work goes in the peak first. */
data class PlanTask(val activityId: Long, val minutes: Int, val deep: Boolean, val priority: Int = 0)

/** A planned block of one activity. */
data class PlanBlock(val activityId: Long, val start: Long, val end: Long) {
    val minutes: Int get() = ((end - start) / 60_000).toInt()
}

/**
 * Fits today's remaining work into the free time around calendar events, the way Reclaim
 * and Motion auto-schedule habits: deep work into the energy peak first, then by priority,
 * in blocks of at least [MIN_BLOCK] minutes with a short gap after each.
 */
object DayPlanner {

    const val MIN_BLOCK = 15
    const val GAP = 5
    private const val MINUTE = 60_000L

    /** Free time between [from] and [to] around [busy] (merged and clipped). */
    fun freeSlots(from: Long, to: Long, busy: List<Slot>): List<Slot> {
        if (to <= from) return emptyList()
        val merged = busy.map { Slot(maxOf(it.start, from), minOf(it.end, to)) }.filter { it.end > it.start }.sortedBy { it.start }
        val free = mutableListOf<Slot>()
        var cursor = from
        for (b in merged) {
            if (b.start > cursor) free += Slot(cursor, b.start)
            cursor = maxOf(cursor, b.end)
        }
        if (cursor < to) free += Slot(cursor, to)
        return free.filter { it.minutes >= MIN_BLOCK }
    }

    /**
     * @param peak preferred stretch for deep work (from Energy), or null
     * @return blocks in time order; whatever doesn't fit is left out
     */
    fun plan(from: Long, to: Long, busy: List<Slot>, tasks: List<PlanTask>, peak: Slot? = null): List<PlanBlock> {
        val free = freeSlots(from, to, busy).toMutableList()
        val blocks = mutableListOf<PlanBlock>()
        val ordered = tasks.filter { it.minutes > 0 }
            .sortedWith(compareByDescending<PlanTask> { it.deep }.thenByDescending { it.priority })

        for (task in ordered) {
            var left = task.minutes
            while (left > 0) {
                val inPeak = if (task.deep && peak != null) {
                    free.indices.filter { overlap(free[it], peak) >= MIN_BLOCK }.maxByOrNull { overlap(free[it], peak) }
                } else null
                val index = inPeak ?: free.indices.firstOrNull { free[it].minutes >= minOf(left, MIN_BLOCK) } ?: break
                val slot = free.removeAt(index)
                val startAt = if (inPeak != null && peak != null) maxOf(slot.start, peak.start) else slot.start
                val length = minOf(left, ((slot.end - startAt) / MINUTE).toInt())
                if (length <= 0) continue
                val end = startAt + length * MINUTE
                blocks += PlanBlock(task.activityId, startAt, end)
                left -= length
                // What's left of the slot on either side stays free, after a short gap.
                Slot(slot.start, startAt).takeIf { it.minutes >= MIN_BLOCK }?.let { free += it }
                Slot(end + GAP * MINUTE, slot.end).takeIf { it.minutes >= MIN_BLOCK }?.let { free += it }
                free.sortBy { it.start }
                if (left in 1 until MIN_BLOCK) break // a sliver isn't worth its own block
            }
        }
        return blocks.sortedBy { it.start }
    }

    private fun overlap(a: Slot, b: Slot): Int = ((minOf(a.end, b.end) - maxOf(a.start, b.start)) / 60_000).toInt().coerceAtLeast(0)
}

/** Where a long-term target stands (Beeminder's road, Strides' pace line). */
data class Pace(
    val total: Float,
    val target: Float,
    /** Per day from today to the deadline to land exactly on target. */
    val neededPerDay: Float,
    /** Total a straight line from start to deadline would have reached by today. */
    val expectedByNow: Float,
    /** Positive = ahead of the line, in the activity's unit. */
    val ahead: Float,
    /** When the recent average reaches the target, or null if it never does. */
    val projectedFinish: LocalDate?,
    val daysLeft: Long
)

object TargetPace {

    /**
     * @param start when the target began (the activity's creation day)
     * @param recentPerDay average per day lately (e.g. last 14 days), for the projection
     */
    fun compute(total: Float, target: Float, start: LocalDate, deadline: LocalDate, today: LocalDate, recentPerDay: Float): Pace {
        val span = ChronoUnit.DAYS.between(start, deadline).coerceAtLeast(1)
        val elapsed = ChronoUnit.DAYS.between(start, today).coerceIn(0, span)
        val daysLeft = ChronoUnit.DAYS.between(today, deadline).coerceAtLeast(0)
        val expected = target * elapsed / span
        val remaining = (target - total).coerceAtLeast(0f)
        val needed = if (daysLeft == 0L) remaining else remaining / (daysLeft + 1)
        val projected = when {
            remaining <= 0f -> today
            recentPerDay <= 0f -> null
            else -> today.plusDays(ceil(remaining / recentPerDay).toLong())
        }
        return Pace(total, target, needed, expected, total - expected, projected, daysLeft)
    }
}

/**
 * Sleep-based energy, after Rise: sleep debt over the last two weeks, and a morning peak a
 * couple of hours after waking when focus is usually best.
 */
object Energy {

    const val NEED_MINUTES = 8 * 60

    /** Minutes of sleep owed over [nights] (only nights with data count), never negative. */
    fun sleepDebt(nights: List<Int>, need: Int = NEED_MINUTES): Int =
        nights.sumOf { (need - it).coerceAtLeast(0) - (it - need).coerceAtLeast(0) / 2 }.coerceAtLeast(0)

    /** Peak focus stretch: 2 to 5 hours after waking. */
    fun peakAfterWaking(wakeMillis: Long): Slot = Slot(wakeMillis + 2 * 3_600_000L, wakeMillis + 5 * 3_600_000L)

    /** The best focus hours (FocusStats.bestWindow) as today's slot. */
    fun peakFromHour(day: LocalDate, hour: Int, zone: ZoneId, width: Int = 2): Slot {
        val start = day.atTime(hour % 24, 0).atZone(zone).toInstant().toEpochMilli()
        return Slot(start, start + width * 3_600_000L)
    }
}

/** Per-activity reminder timing. */
object ReminderMath {

    /** Mon=1 … Sun=64, as in ActivityReminder.daysOfWeek. */
    private fun bit(day: DayOfWeek) = 1 shl (day.value - 1)

    /** Next [hour]:[minute] on an allowed weekday after [now] (30 s grace), or null if none is allowed. */
    fun next(now: ZonedDateTime, hour: Int, minute: Int, days: Int): ZonedDateTime? {
        if (days and 127 == 0) return null
        var candidate = now.withHour(hour.coerceIn(0, 23)).withMinute(minute.coerceIn(0, 59)).withSecond(0).withNano(0)
        if (!candidate.isAfter(now.plusSeconds(30))) candidate = candidate.plusDays(1)
        repeat(8) {
            if (days and bit(candidate.dayOfWeek) != 0) return candidate
            candidate = candidate.plusDays(1)
        }
        return null
    }

    /**
     * When you usually finish an activity: the median time of day of recent completions, or
     * null with fewer than [minDays] of them. Times before 4 AM count as late evening.
     */
    fun usualTime(completions: List<Long>, zone: ZoneId, minDays: Int = 5): LocalTime? {
        if (completions.size < minDays) return null
        val minutes = completions.map { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
            .map { it.hour * 60 + it.minute }
            .map { if (it < 4 * 60) it + 24 * 60 else it }
            .sorted()
        val median = minutes[minutes.size / 2] % (24 * 60)
        val rounded = (median / 5.0).roundToInt() * 5 % (24 * 60)
        return LocalTime.of(rounded / 60, rounded % 60)
    }

    /** "Mon · Wed · Fri", "every day", "weekdays". */
    fun daysLabel(days: Int): String = when (days and 127) {
        127 -> "every day"
        0b0011111 -> "weekdays"
        0b1100000 -> "weekends"
        else -> DayOfWeek.entries.filter { days and bit(it) != 0 }.joinToString(" · ") { it.name.take(1) + it.name.substring(1, 3).lowercase() }
    }
}
