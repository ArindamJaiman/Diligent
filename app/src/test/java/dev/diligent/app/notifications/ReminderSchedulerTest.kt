package dev.diligent.app.notifications

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class ReminderSchedulerTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    @Test
    fun `later today when the time has not passed`() {
        val now = ZonedDateTime.of(2026, 10, 9, 7, 30, 0, 0, zone)
        assertEquals(TimeUnit.MINUTES.toMillis(90), ReminderScheduler.delayUntil(9, 0, now))
    }

    @Test
    fun `tomorrow when the time already passed`() {
        val now = ZonedDateTime.of(2026, 10, 9, 22, 0, 0, 0, zone)
        assertEquals(TimeUnit.HOURS.toMillis(23), ReminderScheduler.delayUntil(21, 0, now))
    }

    @Test
    fun `never schedules for right now`() {
        val now = ZonedDateTime.of(2026, 10, 9, 9, 0, 10, 0, zone)
        assertEquals(TimeUnit.DAYS.toMillis(1) - TimeUnit.SECONDS.toMillis(10), ReminderScheduler.delayUntil(9, 0, now))
    }
}
