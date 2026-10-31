package dev.diligent.app.health

import java.time.Instant
import java.time.ZoneId

/** A sleep session, epoch millis. */
data class SleepSpan(val start: Long, val end: Long)

/** Pure sleep arithmetic for the day log and the "day starts when you sleep" setting. */
object SleepMath {

    /** Minutes asleep that ended inside [from, to), overlapping sessions merged. */
    fun minutesEndingBetween(spans: List<SleepSpan>, from: Long, to: Long): Int {
        val merged = merge(spans.filter { it.end in from until to })
        return (merged.sumOf { it.end - it.start } / 60_000).toInt()
    }

    /** Start of the longest sleep that ended inside [from, to), or null. */
    fun mainStart(spans: List<SleepSpan>, from: Long, to: Long): Long? =
        merge(spans.filter { it.end in from until to }).maxByOrNull { it.end - it.start }?.start

    /**
     * When each night's main sleep began, as minutes after local midnight (an 11 PM start is
     * −60), for [DayBoundary.fromSleep]. A night is keyed by the date the sleep ended; naps
     * under three hours are left out.
     */
    fun onsets(spans: List<SleepSpan>, zone: ZoneId): List<Int> =
        merge(spans)
            .filter { it.end - it.start >= 3 * 3_600_000L }
            .groupBy { Instant.ofEpochMilli(it.end).atZone(zone).toLocalDate() }
            .mapNotNull { (wakeDate, nights) ->
                val main = nights.maxByOrNull { it.end - it.start } ?: return@mapNotNull null
                val midnight = wakeDate.atStartOfDay(zone).toInstant().toEpochMilli()
                ((main.start - midnight) / 60_000).toInt()
            }

    /** Sessions that touch or overlap become one. */
    fun merge(spans: List<SleepSpan>): List<SleepSpan> {
        val sorted = spans.filter { it.end > it.start }.sortedBy { it.start }
        val out = mutableListOf<SleepSpan>()
        for (span in sorted) {
            val last = out.lastOrNull()
            if (last != null && span.start <= last.end) {
                out[out.size - 1] = SleepSpan(last.start, maxOf(last.end, span.end))
            } else {
                out += span
            }
        }
        return out
    }

    /** Evening before [date]'s tracking day: sleep that ends after this belongs to [date]. */
    fun nightWindowStart(dayStart: Long): Long = dayStart - 12 * 3_600_000L
}
