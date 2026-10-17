package dev.diligent.app.focus.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A stretch of wall-clock time, [start, end) in epoch millis. */
data class Span(val start: Long, val end: Long) {
    val millis: Long get() = (end - start).coerceAtLeast(0)
}

/** Whole minutes of focus that belong to one local day. */
data class DayMinutes(val date: LocalDate, val minutes: Int)

enum class Phase { FOCUS, BREAK }

/**
 * Where a Pomodoro session is at a moment. [block] is the focus block in progress, or the
 * one just finished during a break (both count from 1).
 */
data class PomodoroPosition(val phase: Phase, val block: Int, val phaseStart: Long, val phaseEnd: Long)

/**
 * Time arithmetic for the focus timer, kept free of Android so it can be unit tested.
 *
 * Pomodoro runs on a fixed 25/5 schedule anchored at the start tap, so the time logged
 * never depends on when the (non-wakeup, possibly late) block alarm is delivered.
 */
object FocusMath {

    const val MINUTE = 60_000L
    const val FOCUS_MS = 25 * MINUTE
    const val BREAK_MS = 5 * MINUTE
    private const val CYCLE_MS = FOCUS_MS + BREAK_MS

    /** Units the timer can log to; everything else (qs, reps, pages…) isn't time. */
    fun isTimeUnit(unit: String): Boolean = unit == "hrs" || unit == "mins"

    /** Nearest whole minute. Under 30 s rounds to 0, so a mis-tapped start logs nothing. */
    fun roundToMinutes(millis: Long): Long = (millis.coerceAtLeast(0) + MINUTE / 2) / MINUTE

    /** [minutes] in the activity's unit, or null when the unit isn't time. */
    fun amountFor(unit: String, minutes: Int): Float? = when (unit) {
        "hrs" -> minutes / 60f
        "mins" -> minutes.toFloat()
        else -> null
    }

    /** Cuts [span] at every local midnight it crosses (DST days are 23 or 25 hours long). */
    fun splitAtMidnight(span: Span, zone: ZoneId): List<Pair<LocalDate, Span>> {
        if (span.end <= span.start) return emptyList()
        val slices = mutableListOf<Pair<LocalDate, Span>>()
        var cursor = span.start
        var date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
        while (cursor < span.end) {
            val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = minOf(nextMidnight, span.end)
            slices += date to Span(cursor, end)
            cursor = end
            date = date.plusDays(1)
        }
        return slices
    }

    /**
     * Minutes per local day for [spans]. The running total is rounded rather than each day,
     * so the days always add up to the rounded session length: 23:59:40 → 00:00:20 logs one
     * minute (to the second day) instead of zero on both.
     */
    fun minutesPerDay(spans: List<Span>, zone: ZoneId): List<DayMinutes> {
        val perDay = linkedMapOf<LocalDate, Long>()
        var total = 0L
        var rounded = 0L
        spans.sortedBy { it.start }.forEach { span ->
            splitAtMidnight(span, zone).forEach { (date, slice) ->
                total += slice.millis
                val next = roundToMinutes(total)
                perDay[date] = (perDay[date] ?: 0L) + (next - rounded)
                rounded = next
            }
        }
        return perDay.filterValues { it > 0 }.map { DayMinutes(it.key, it.value.toInt()) }
    }

    fun pomodoroAt(start: Long, now: Long): PomodoroPosition {
        val elapsed = (now - start).coerceAtLeast(0)
        val cycle = elapsed / CYCLE_MS
        val cycleStart = start + cycle * CYCLE_MS
        val block = (cycle + 1).toInt()
        return if (elapsed - cycle * CYCLE_MS < FOCUS_MS) {
            PomodoroPosition(Phase.FOCUS, block, cycleStart, cycleStart + FOCUS_MS)
        } else {
            PomodoroPosition(Phase.BREAK, block, cycleStart + FOCUS_MS, cycleStart + CYCLE_MS)
        }
    }

    /** The focus parts of [from, to] on a 25/5 schedule that began at [start]; breaks are left out. */
    fun pomodoroFocusSpans(start: Long, from: Long, to: Long): List<Span> {
        if (to <= from) return emptyList()
        val spans = mutableListOf<Span>()
        var cycle = (from - start).coerceAtLeast(0) / CYCLE_MS
        while (true) {
            val focusStart = start + cycle * CYCLE_MS
            if (focusStart >= to) break
            val begin = maxOf(focusStart, from)
            val end = minOf(focusStart + FOCUS_MS, to)
            if (end > begin) spans += Span(begin, end)
            cycle++
        }
        return spans
    }

    /** Focus time not yet written to DailyProgress: everything since [loggedUntil], minus breaks. */
    fun unloggedFocus(startedAt: Long, loggedUntil: Long, pomodoro: Boolean, now: Long): List<Span> =
        if (pomodoro) {
            pomodoroFocusSpans(startedAt, loggedUntil, now)
        } else {
            listOf(Span(loggedUntil, now)).filter { it.millis > 0 }
        }

    /** Stopwatch text: "4:05", "1:02:03". */
    fun formatClock(millis: Long): String {
        val totalSeconds = millis.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    /** "25 min", "1 h", "1 h 25 min". */
    fun formatMinutes(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> "$rest min"
            rest == 0 -> "$hours h"
            else -> "$hours h $rest min"
        }
    }
}
