package dev.diligent.app.focus

import dev.diligent.app.data.local.entity.FocusRecord
import dev.diligent.app.focus.domain.FocusStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class FocusStatsTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, 30, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun record(from: Long, to: Long, minutes: Int, rating: Int = 0, broken: Boolean = false, interruptions: Int = 0) =
        FocusRecord(activityId = 1, startedAt = from, endedAt = to, focusMinutes = minutes, rating = rating, broken = broken, interruptions = interruptions)

    @Test
    fun `sessions spread over the hours they ran in`() {
        val byHour = FocusStats.minutesByHour(listOf(record(at(9, 30), at(11, 0), 90)), zone)
        assertEquals(30, byHour[9])
        assertEquals(60, byHour[10])
        assertEquals(0, byHour[11])
    }

    @Test
    fun `pomodoro breaks are scaled out`() {
        // 60 minutes on the clock, 50 logged
        val byHour = FocusStats.minutesByHour(listOf(record(at(14), at(15), 50)), zone)
        assertEquals(50, byHour[14])
    }

    @Test
    fun `best window wraps past midnight`() {
        val byHour = IntArray(24).also { it[23] = 40; it[0] = 50; it[9] = 60 }
        assertEquals(23, FocusStats.bestWindow(byHour))
        assertNull(FocusStats.bestWindow(IntArray(24)))
        assertEquals("11 PM–1 AM", FocusStats.windowLabel(23))
        assertEquals("9–11 AM", FocusStats.windowLabel(9))
    }

    @Test
    fun `summary counts quality`() {
        val s = FocusStats.summarize(listOf(
            record(at(9), at(10), 60, rating = 4),
            record(at(11), at(11, 30), 30, rating = 2, broken = true, interruptions = 3),
            record(at(20), at(20, 30), 30)
        ))
        assertEquals(3, s.sessions)
        assertEquals(120, s.minutes)
        assertEquals(40, s.averageMinutes)
        assertEquals(2 / 3f, s.intactShare, 1e-6f)
        assertEquals(3f, s.averageRating, 1e-6f)
        assertEquals(1f, s.interruptionsPerSession, 1e-6f)
    }
}
