package dev.diligent.app.leetcode

import dev.diligent.app.leetcode.domain.StreakSchedule
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class StreakScheduleTest {

    private val ist = ZoneId.of("Asia/Kolkata")
    private val pacific = ZoneId.of("America/Los_Angeles")
    private val utc = ZoneId.of("UTC")
    private val day = LocalDate.of(2026, 10, 17)

    private fun times(zone: ZoneId, guard: String, repeat: Boolean = true) =
        StreakSchedule.checksFor(day, LocalTime.parse(guard), repeat, zone)
            .map { it.toLocalDateTime().toString() }

    @Test
    fun `india - repeats stop for the night, reset is 05 30`() {
        // Last call would be 04:30, inside quiet hours, so it is skipped.
        assertEquals(listOf("2026-10-17T21:00", "2026-10-17T23:00"), times(ist, "21:00"))
    }

    @Test
    fun `us west coast - last call an hour before the 17 00 reset`() {
        assertEquals(listOf("2026-10-17T13:00", "2026-10-17T16:00"), times(pacific, "13:00"))
    }

    @Test
    fun `us west coast evening guard covers the next day's reset`() {
        // 21:00 PDT is already the next UTC day, which resets at 17:00 PDT tomorrow.
        assertEquals(
            listOf("2026-10-17T21:00", "2026-10-17T23:00", "2026-10-18T16:00"),
            times(pacific, "21:00")
        )
    }

    @Test
    fun `utc - repeat every two hours, then last call`() {
        assertEquals(listOf("2026-10-17T18:00", "2026-10-17T20:00", "2026-10-17T23:00"), times(utc, "18:00"))
    }

    @Test
    fun `repeat off means one check a day`() {
        assertEquals(listOf("2026-10-17T21:00"), times(ist, "21:00", repeat = false))
    }

    @Test
    fun `the chosen time is honoured even inside quiet hours`() {
        assertEquals("2026-10-17T01:30", times(ist, "01:30").first())
    }

    @Test
    fun `next check is the following repeat`() {
        val now = ZonedDateTime.of(2026, 10, 17, 21, 0, 5, 0, ist)
        val next = StreakSchedule.next(now, LocalTime.of(21, 0), repeat = true, doneThrough = null)
        assertEquals(ZonedDateTime.of(2026, 10, 17, 23, 0, 0, 0, ist), next)
    }

    @Test
    fun `a solved or muted day skips its remaining checks`() {
        val now = ZonedDateTime.of(2026, 10, 17, 21, 0, 5, 0, ist)
        val next = StreakSchedule.next(now, LocalTime.of(21, 0), repeat = true, doneThrough = LocalDate.of(2026, 10, 17))
        assertEquals(ZonedDateTime.of(2026, 10, 18, 21, 0, 0, 0, ist), next)
    }

    @Test
    fun `after the last check of the day comes tomorrow's`() {
        val now = ZonedDateTime.of(2026, 10, 17, 23, 30, 0, 0, ist)
        val next = StreakSchedule.next(now, LocalTime.of(21, 0), repeat = true, doneThrough = null)
        assertEquals(ZonedDateTime.of(2026, 10, 18, 21, 0, 0, 0, ist), next)
    }

    @Test
    fun `yesterday's last call can still be next`() {
        // Pacific, guard 21:00 on the 16th: its last call is 16:00 on the 17th.
        val now = ZonedDateTime.of(2026, 10, 17, 9, 0, 0, 0, pacific)
        val next = StreakSchedule.next(now, LocalTime.of(21, 0), repeat = true, doneThrough = null)
        assertEquals(ZonedDateTime.of(2026, 10, 17, 16, 0, 0, 0, pacific), next)
    }
}
