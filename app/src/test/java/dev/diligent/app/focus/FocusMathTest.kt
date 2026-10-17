package dev.diligent.app.focus

import dev.diligent.app.focus.domain.DayMinutes
import dev.diligent.app.focus.domain.FocusMath
import dev.diligent.app.focus.domain.FocusMath.MINUTE
import dev.diligent.app.focus.domain.Phase
import dev.diligent.app.focus.domain.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class FocusMathTest {

    private val india = ZoneId.of("Asia/Kolkata")
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(dateTime: String, zone: ZoneId = india): Long =
        LocalDateTime.parse(dateTime).atZone(zone).toInstant().toEpochMilli()

    private fun day(date: String) = LocalDate.parse(date)

    // ─── Midnight split ─────────────────────────────────────────

    @Test
    fun `span within one day stays whole`() {
        val span = Span(at("2026-10-17T09:00:00"), at("2026-10-17T11:30:00"))
        val slices = FocusMath.splitAtMidnight(span, india)
        assertEquals(listOf(day("2026-10-17") to span), slices)
    }

    @Test
    fun `span is cut at local midnight`() {
        val slices = FocusMath.splitAtMidnight(Span(at("2026-10-17T23:30:00"), at("2026-10-18T00:45:00")), india)
        assertEquals(listOf(day("2026-10-17"), day("2026-10-18")), slices.map { it.first })
        assertEquals(listOf(30 * MINUTE, 45 * MINUTE), slices.map { it.second.millis })
        assertEquals(at("2026-10-18T00:00:00"), slices[0].second.end)
    }

    @Test
    fun `split follows a 23-hour DST day`() {
        // Berlin springs forward on 2026-03-29: that day has 23 hours.
        val span = Span(at("2026-03-28T23:00:00", berlin), at("2026-03-30T01:00:00", berlin))
        val slices = FocusMath.splitAtMidnight(span, berlin)
        assertEquals(listOf(day("2026-03-28"), day("2026-03-29"), day("2026-03-30")), slices.map { it.first })
        assertEquals(listOf(60 * MINUTE, 23 * 60 * MINUTE, 60 * MINUTE), slices.map { it.second.millis })
    }

    @Test
    fun `empty or reversed span has no slices`() {
        val t = at("2026-10-17T10:00:00")
        assertTrue(FocusMath.splitAtMidnight(Span(t, t), india).isEmpty())
        assertTrue(FocusMath.splitAtMidnight(Span(t, t - 1), india).isEmpty())
    }

    @Test
    fun `session crossing midnight is logged to both days`() {
        val spans = listOf(Span(at("2026-10-17T22:30:00"), at("2026-10-18T00:45:00")))
        assertEquals(
            listOf(DayMinutes(day("2026-10-17"), 90), DayMinutes(day("2026-10-18"), 45)),
            FocusMath.minutesPerDay(spans, india)
        )
    }

    @Test
    fun `rounding uses the running total so midnight loses nothing`() {
        // 20 s before + 20 s after midnight: each half rounds to 0, the 40 s total to 1 minute.
        val spans = listOf(Span(at("2026-10-17T23:59:40"), at("2026-10-18T00:00:20")))
        assertEquals(listOf(DayMinutes(day("2026-10-18"), 1)), FocusMath.minutesPerDay(spans, india))
    }

    @Test
    fun `mis-tapped start under 30 seconds logs nothing`() {
        val start = at("2026-10-17T10:00:00")
        assertTrue(FocusMath.minutesPerDay(listOf(Span(start, start + 29_000)), india).isEmpty())
    }

    @Test
    fun `pomodoro session across midnight skips the break and splits the days`() {
        val start = at("2026-10-17T23:50:00")
        val spans = FocusMath.unloggedFocus(start, start, pomodoro = true, now = at("2026-10-18T00:40:00"))
        // Focus 23:50–00:15 (10 + 15 min), break 00:15–00:20, focus 00:20–00:40 (20 min).
        assertEquals(
            listOf(DayMinutes(day("2026-10-17"), 10), DayMinutes(day("2026-10-18"), 35)),
            FocusMath.minutesPerDay(spans, india)
        )
    }

    // ─── Elapsed → amount ───────────────────────────────────────

    @Test
    fun `elapsed time rounds to the nearest minute`() {
        assertEquals(0L, FocusMath.roundToMinutes(29_999))
        assertEquals(1L, FocusMath.roundToMinutes(30_000))
        assertEquals(1L, FocusMath.roundToMinutes(89_999))
        assertEquals(2L, FocusMath.roundToMinutes(90_000))
        assertEquals(25L, FocusMath.roundToMinutes(25 * MINUTE))
        assertEquals(0L, FocusMath.roundToMinutes(-5_000))
    }

    @Test
    fun `minutes convert to the activity unit`() {
        assertEquals(25 / 60f, FocusMath.amountFor("hrs", 25)!!, 1e-6f)
        assertEquals(1.5f, FocusMath.amountFor("hrs", 90)!!, 1e-6f)
        assertEquals(25f, FocusMath.amountFor("mins", 25)!!, 0f)
        assertNull(FocusMath.amountFor("qs", 25))
    }

    @Test
    fun `only hrs and mins activities can be timed`() {
        assertTrue(FocusMath.isTimeUnit("hrs"))
        assertTrue(FocusMath.isTimeUnit("mins"))
        assertFalse(FocusMath.isTimeUnit("qs"))
        assertFalse(FocusMath.isTimeUnit("pages"))
    }

    // ─── Pomodoro schedule ──────────────────────────────────────

    @Test
    fun `pomodoro phases follow the 25-5 schedule`() {
        val start = 1_000_000L
        fun phaseAt(minutes: Long) = FocusMath.pomodoroAt(start, start + minutes * MINUTE)

        assertEquals(Phase.FOCUS, phaseAt(0).phase)
        assertEquals(1, phaseAt(0).block)
        assertEquals(start + 25 * MINUTE, phaseAt(0).phaseEnd)

        assertEquals(Phase.BREAK, phaseAt(25).phase)
        assertEquals(1, phaseAt(25).block)
        assertEquals(start + 25 * MINUTE, phaseAt(25).phaseStart)
        assertEquals(start + 30 * MINUTE, phaseAt(25).phaseEnd)

        assertEquals(Phase.FOCUS, phaseAt(30).phase)
        assertEquals(2, phaseAt(30).block)
        assertEquals(Phase.FOCUS, phaseAt(61).phase)
        assertEquals(3, phaseAt(61).block)
        assertEquals(start + 85 * MINUTE, phaseAt(61).phaseEnd)
    }

    @Test
    fun `pomodoro focus spans leave out breaks`() {
        val start = 0L
        assertEquals(
            listOf(Span(0, 25 * MINUTE), Span(30 * MINUTE, 55 * MINUTE), Span(60 * MINUTE, 70 * MINUTE)),
            FocusMath.pomodoroFocusSpans(start, from = 0, to = 70 * MINUTE)
        )
        // Picking up mid-break (after block 1 was logged) only counts the next block.
        assertEquals(
            listOf(Span(30 * MINUTE, 40 * MINUTE)),
            FocusMath.pomodoroFocusSpans(start, from = 27 * MINUTE, to = 40 * MINUTE)
        )
        // Stopped during a break: nothing new.
        assertTrue(FocusMath.pomodoroFocusSpans(start, from = 25 * MINUTE, to = 29 * MINUTE).isEmpty())
    }

    @Test
    fun `plain timer logs everything since the last log`() {
        assertEquals(listOf(Span(100, 500)), FocusMath.unloggedFocus(100, 100, pomodoro = false, now = 500))
        assertTrue(FocusMath.unloggedFocus(100, 100, pomodoro = false, now = 100).isEmpty())
    }

    // ─── Formatting ─────────────────────────────────────────────

    @Test
    fun `clock and duration text`() {
        assertEquals("0:00", FocusMath.formatClock(0))
        assertEquals("1:05", FocusMath.formatClock(65_000))
        assertEquals("1:02:03", FocusMath.formatClock(3_723_000))
        assertEquals("0:00", FocusMath.formatClock(-1_000))

        assertEquals("25 min", FocusMath.formatMinutes(25))
        assertEquals("1 h", FocusMath.formatMinutes(60))
        assertEquals("1 h 25 min", FocusMath.formatMinutes(85))
    }
}
