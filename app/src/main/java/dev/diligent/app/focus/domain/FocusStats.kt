package dev.diligent.app.focus.domain

import dev.diligent.app.data.local.entity.FocusRecord
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Totals over a set of finished sessions. */
data class FocusSummary(
    val sessions: Int,
    val minutes: Int,
    val averageMinutes: Int,
    /** Share of sessions whose stake held (no blocked app opened), 0..1. */
    val intactShare: Float,
    /** Mean self-rating of rated sessions, 0 when none were rated. */
    val averageRating: Float,
    val interruptionsPerSession: Float
)

/** Pure focus-session analysis (time of day, quality), unit tested on the JVM. */
object FocusStats {

    fun summarize(records: List<FocusRecord>): FocusSummary {
        if (records.isEmpty()) return FocusSummary(0, 0, 0, 0f, 0f, 0f)
        val minutes = records.sumOf { it.focusMinutes }
        val rated = records.filter { it.rating > 0 }
        return FocusSummary(
            sessions = records.size,
            minutes = minutes,
            averageMinutes = (minutes.toFloat() / records.size).roundToInt(),
            intactShare = records.count { !it.broken } / records.size.toFloat(),
            averageRating = if (rated.isEmpty()) 0f else rated.sumOf { it.rating } / rated.size.toFloat(),
            interruptionsPerSession = records.sumOf { it.interruptions } / records.size.toFloat()
        )
    }

    /**
     * Minutes of focus in each hour of the day (0–23), spreading each session over the hours
     * it ran in. Pomodoro sessions are scaled to their logged minutes, so breaks don't count.
     */
    fun minutesByHour(records: List<FocusRecord>, zone: ZoneId): IntArray {
        val millis = LongArray(24)
        records.forEach { record ->
            val span = record.endedAt - record.startedAt
            if (span <= 0) return@forEach
            val scale = (record.focusMinutes * 60_000.0 / span).coerceAtMost(1.0)
            var cursor = record.startedAt
            while (cursor < record.endedAt) {
                val time = Instant.ofEpochMilli(cursor).atZone(zone)
                val nextHour = time.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli()
                val end = minOf(nextHour, record.endedAt)
                millis[time.hour] += ((end - cursor) * scale).toLong()
                cursor = end
            }
        }
        return IntArray(24) { (millis[it] / 60_000).toInt() }
    }

    /**
     * The [width]-hour window with the most focus, as its first hour, or null without data.
     * Windows wrap past midnight (23:00–01:00 is one window).
     */
    fun bestWindow(byHour: IntArray, width: Int = 2): Int? {
        if (byHour.all { it == 0 }) return null
        return (0 until 24).maxByOrNull { start -> (0 until width).sumOf { byHour[(start + it) % 24] } }
    }

    /**
     * Mean self-rating by the hour sessions started, for "when do you focus best". Hours with
     * fewer than [minSessions] rated sessions are left out.
     */
    fun ratingByHour(records: List<FocusRecord>, zone: ZoneId, minSessions: Int = 2): Map<Int, Float> =
        records.filter { it.rating > 0 }
            .groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).hour }
            .filterValues { it.size >= minSessions }
            .mapValues { (_, list) -> list.sumOf { it.rating } / list.size.toFloat() }

    /** "9–11 AM", "11 PM–1 AM". */
    fun windowLabel(start: Int, width: Int = 2): String {
        fun h(hour: Int): Pair<Int, String> {
            val h = hour % 24
            return (if (h % 12 == 0) 12 else h % 12) to (if (h < 12) "AM" else "PM")
        }
        val (a, am) = h(start)
        val (b, bm) = h(start + width)
        return if (am == bm) "$a–$b $bm" else "$a $am–$b $bm"
    }
}
