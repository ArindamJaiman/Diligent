package dev.diligent.app.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class PlannerTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, 31, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `free slots skip meetings and slivers`() {
        val free = DayPlanner.freeSlots(at(9), at(13), listOf(Slot(at(10), at(11)), Slot(at(11, 10), at(12))))
        assertEquals(listOf(Slot(at(9), at(10)), Slot(at(12), at(13))), free) // 11:00–11:10 is too short
    }

    @Test
    fun `deep work lands in the peak, the rest fills from the start`() {
        val tasks = listOf(PlanTask(1, 60, deep = false), PlanTask(2, 90, deep = true))
        val blocks = DayPlanner.plan(at(8), at(18), listOf(Slot(at(12), at(13))), tasks, peak = Slot(at(10), at(12)))
        val deep = blocks.filter { it.activityId == 2L }
        assertEquals(at(10), deep.first().start)
        assertEquals(90, deep.sumOf { it.minutes })
        val light = blocks.filter { it.activityId == 1L }
        assertEquals(at(8), light.first().start)
        assertEquals(60, light.sumOf { it.minutes })
        // No overlaps, nothing inside the meeting
        val sorted = blocks.sortedBy { it.start }
        sorted.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start) }
        assertTrue(blocks.none { it.start < at(13) && it.end > at(12) })
    }

    @Test
    fun `work splits across gaps and stops when the day is full`() {
        val blocks = DayPlanner.plan(at(9), at(11), listOf(Slot(at(10), at(10, 30))), listOf(PlanTask(1, 180, deep = false)))
        assertEquals(listOf(60, 30), blocks.map { it.minutes })
    }

    @Test
    fun `target pace`() {
        val start = LocalDate.of(2026, 1, 1)
        val pace = TargetPace.compute(total = 100f, target = 200f, start = start, deadline = LocalDate.of(2026, 12, 31),
            today = LocalDate.of(2026, 7, 2), recentPerDay = 1f)
        assertTrue("about on the line, was ${pace.ahead}", pace.ahead in -1f..1f)
        assertEquals(LocalDate.of(2026, 10, 10), pace.projectedFinish)
        assertEquals(182L, pace.daysLeft)
        assertEquals(100f / 183, pace.neededPerDay, 1e-4f)
        assertNull(TargetPace.compute(10f, 200f, start, LocalDate.of(2026, 12, 31), LocalDate.of(2026, 7, 2), 0f).projectedFinish)
    }

    @Test
    fun `sleep debt and peak`() {
        assertEquals(120, Energy.sleepDebt(listOf(420, 420)))   // two 7 h nights
        assertEquals(90, Energy.sleepDebt(listOf(420, 420, 540))) // a 9 h night pays back 30 min
        val wake = at(7)
        assertEquals(Slot(at(9), at(12)), Energy.peakAfterWaking(wake))
    }

    @Test
    fun `reminders on allowed days and the usual time`() {
        val friday = LocalDateTime.of(2026, 10, 30, 20, 0).atZone(zone)
        val monWed = 0b0000101
        assertEquals(LocalDateTime.of(2026, 11, 2, 9, 0).atZone(zone), ReminderMath.next(friday, 9, 0, monWed))
        assertEquals(LocalDateTime.of(2026, 10, 30, 21, 0).atZone(zone), ReminderMath.next(friday, 21, 0, 127))
        assertNull(ReminderMath.next(friday, 9, 0, 0))
        val times = listOf(19 * 60 + 38, 19 * 60 + 42, 20 * 60, 19 * 60 + 40, 18 * 60 + 55).map {
            LocalDateTime.of(2026, 10, 1, it / 60, it % 60).atZone(zone).toInstant().toEpochMilli()
        }
        assertEquals(LocalTime.of(19, 40), ReminderMath.usualTime(times, zone))
        assertEquals("Mon · Wed", ReminderMath.daysLabel(monWed))
    }
}
