package dev.diligent.app.tracking

import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.goals.StreakEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class TrackingTest {

    // A Saturday.
    private val today = LocalDate.of(2026, 10, 31)
    private val created = today.minusDays(120)
    private fun back(vararg days: Long) = days.map { today.minusDays(it) }.toSet()
    private fun activity(
        type: String = Schedule.DAILY,
        days: Int = Schedule.ALL_DAYS,
        count: Int = 1,
        goal: Float = 1f,
        goalType: String = Goals.AT_LEAST
    ) = Activity(id = 1, name = "Gym", unit = "hrs", dailyGoal = goal, scheduleType = type, scheduleDays = days,
        scheduleCount = count, goalType = goalType)

    private fun progress(days: Set<LocalDate>, value: Float = 1f) = days.associateWith { value }

    // ─── Schedules ──────────────────────────────────────────────

    @Test
    fun `weekday schedule rest days neither add to nor break the streak`() {
        val monWedFri = Schedule.dayBit(DayOfWeek.MONDAY) or Schedule.dayBit(DayOfWeek.WEDNESDAY) or
            Schedule.dayBit(DayOfWeek.FRIDAY)
        val gym = activity(Schedule.WEEKDAYS, days = monWedFri)
        // Fri 30, Wed 28, Mon 26, Fri 23 done; Saturday (today) is a rest day.
        val done = back(1, 3, 5, 8)
        val summary = Tracking.summarize(gym, progress(done), emptySet(), today, created)
        assertEquals(4, summary.streak.current)
        assertEquals("rest day", summary.caption)
        assertEquals("Mon · Wed · Fri", Schedule.describe(gym))
    }

    @Test
    fun `a missed scheduled day still breaks a weekday streak`() {
        val monWedFri = Schedule.dayBit(DayOfWeek.MONDAY) or Schedule.dayBit(DayOfWeek.WEDNESDAY) or
            Schedule.dayBit(DayOfWeek.FRIDAY)
        val gym = activity(Schedule.WEEKDAYS, days = monWedFri)
        val done = back(1, 5) // Wednesday 28 missed, no freeze banked
        assertEquals(1, Tracking.summarize(gym, progress(done), emptySet(), today, created).streak.current)
    }

    @Test
    fun `skipped days are neutral`() {
        val daily = activity()
        val done = back(0, 1, 3, 4)
        val summary = Tracking.summarize(daily, progress(done), back(2), today, created)
        assertEquals(4, summary.streak.current)
    }

    @Test
    fun `weekly target counts met weeks`() {
        val weekly = activity(Schedule.WEEKLY, count = 3)
        // This week (Mon 26 – Sat 31): 2 so far. Last week: 3. Week before: 3. Before that: 1.
        val done = back(0, 2) + back(6, 8, 10) + back(13, 15, 17) + back(20)
        val summary = Tracking.summarize(weekly, progress(done), emptySet(), today, created)
        assertEquals(2, summary.streak.current)
        assertEquals("weeks", summary.streakUnit)
        assertEquals("2/3 this week", summary.caption)
    }

    @Test
    fun `current week counts once its target is met`() {
        assertEquals(1, WeeklyStreak.compute(back(0, 1, 2), 3, today))
        assertEquals(0, WeeklyStreak.compute(back(0, 1), 3, today))
    }

    @Test
    fun `every other day only misses when the gap is too long`() {
        val alternate = activity(Schedule.INTERVAL, count = 2)
        val done = back(2, 4, 6, 8)
        val summary = Tracking.summarize(alternate, progress(done), emptySet(), today, created)
        assertEquals(4, summary.streak.current)
        assertNull(summary.caption) // due again today
        assertEquals("next tomorrow", Schedule.caption(alternate, back(1), emptySet(), today))
        assertEquals("next in 2 days", Schedule.caption(activity(Schedule.INTERVAL, count = 3), back(1), emptySet(), today))
        // Gap of 3 days with a 2-day interval: day 3 was due and missed.
        assertEquals(1, Tracking.summarize(alternate, progress(back(1, 4)), emptySet(), today, created).streak.current)
    }

    @Test
    fun `due days follow the schedule`() {
        val week = today.minusDays(6)
        assertEquals(7.0, Schedule.dueDays(activity(), week, today), 1e-9)
        assertEquals(3.0, Schedule.dueDays(activity(Schedule.WEEKLY, count = 3), week, today), 1e-9)
        assertEquals(5.0, Schedule.dueDays(activity(Schedule.WEEKDAYS, days = 0b0011111), week, today), 1e-9)
    }

    // ─── Limits and quitting ────────────────────────────────────

    @Test
    fun `limit days with nothing logged are good days`() {
        val youtube = activity(goal = 1f, goalType = Goals.AT_MOST)
        val logged = mapOf(today.minusDays(1) to 0.5f, today.minusDays(3) to 2f)
        // Created 5 days ago, so no freeze is banked yet when it goes over 3 days ago.
        val summary = Tracking.summarize(youtube, logged, emptySet(), today, today.minusDays(5))
        // Over the limit 3 days ago; under or empty since → today, yesterday, 2 days ago.
        assertEquals(3, summary.streak.current)
        assertTrue(Goals.isMet(youtube, 1f))
        assertFalse(Goals.isMet(youtube, 1.25f))
    }

    @Test
    fun `days since the last slip`() {
        val smoking = activity(goalType = Goals.QUIT)
        assertEquals(11L, Tracking.daysSinceSlip(smoking, emptyMap(), today, today.minusDays(10)))
        assertEquals(2L, Tracking.daysSinceSlip(smoking, mapOf(today.minusDays(2) to 1f), today, created))
        assertEquals(0L, Tracking.daysSinceSlip(smoking, mapOf(today to 1f), today, created))
    }

    @Test
    fun `completion counts only due days and treats empty limit days as met`() {
        val weekStart = today.minusDays(6)
        val (met, due) = Tracking.completion(activity(), progress(back(0, 1, 2)), emptySet(), weekStart, today, created)
        assertEquals(3.0, met, 1e-9)
        assertEquals(7.0, due, 1e-9)
        val limit = activity(goalType = Goals.AT_MOST)
        val (limitMet, limitDue) = Tracking.completion(limit, mapOf(today to 5f), emptySet(), weekStart, today, created)
        assertEquals(6.0, limitMet, 1e-9)
        assertEquals(7.0, limitDue, 1e-9)
    }

    // ─── Strength ───────────────────────────────────────────────

    @Test
    fun `strength rises with practice and dents on a slip instead of zeroing`() {
        val daily = activity()
        val sixtyDays = (1L..60L).map { today.minusDays(it) }.toSet()
        val strong = HabitStrength.score(daily, progress(sixtyDays), sixtyDays, emptySet(), today, created)
        assertTrue("60 straight days should be strong, was $strong", strong > 0.9)

        val withSlip = sixtyDays - today.minusDays(1)
        val dented = HabitStrength.score(daily, progress(withSlip), withSlip, emptySet(), today, created)
        assertTrue(dented < strong)
        assertTrue("one slip shouldn't wipe it out, was $dented", dented > 0.8)

        val halfWay = (1L..13L).map { today.minusDays(it) }.toSet()
        assertEquals(0.5, HabitStrength.score(daily, progress(halfWay), halfWay, emptySet(), today, today.minusDays(13)), 0.01)
    }

    @Test
    fun `partial progress earns partial strength and today only counts once met`() {
        val daily = activity(goal = 2f)
        val days = (0L..20L).map { today.minusDays(it) }.toSet()
        val half = HabitStrength.score(daily, progress(days, 1f), emptySet(), emptySet(), today, today.minusDays(20))
        val full = HabitStrength.score(daily, progress(days, 2f), days, emptySet(), today, today.minusDays(20))
        assertTrue(half < full)
        assertTrue(half > 0.0)
        val yesterdayOnly = HabitStrength.score(daily, mapOf(today.minusDays(1) to 2f), back(1), emptySet(), today, today.minusDays(1))
        val plusEmptyToday = HabitStrength.score(daily, mapOf(today.minusDays(1) to 2f, today to 0f), back(1), emptySet(), today, today.minusDays(1))
        assertEquals(yesterdayOnly, plusEmptyToday, 1e-12)
    }

    @Test
    fun `weekly strength ignores days off in a week that met its target`() {
        val weekly = activity(Schedule.WEEKLY, count = 2)
        val done = (0L..9L).map { Schedule.weekStart(today).minusWeeks(it + 1) }.flatMap { listOf(it, it.plusDays(2)) }.toSet()
        val score = HabitStrength.score(weekly, progress(done), done, emptySet(), today, created)
        assertTrue("ten met weeks should be solid, was $score", score > 0.6)
    }

    // ─── Day boundary ───────────────────────────────────────────

    @Test
    fun `work after midnight counts for the day before until the day starts`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val oneAm = LocalDateTime.of(2026, 10, 31, 1, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 10, 30), DayBoundary.logicalDate(oneAm, 4 * 60, zone))
        assertEquals(LocalDate.of(2026, 10, 31), DayBoundary.logicalDate(oneAm, 0, zone))
        val next = DayBoundary.nextStart(oneAm, 4 * 60, zone)
        assertEquals(LocalDateTime.of(2026, 10, 31, 4, 0).atZone(zone).toInstant().toEpochMilli(), next)
    }

    @Test
    fun `day start learnt from sleep`() {
        assertEquals(0, DayBoundary.fromSleep(listOf(-60, -30, 10)))
        assertEquals(3 * 60 + 30, DayBoundary.fromSleep(listOf(120, 150, 160, 200)))
        assertEquals(DayBoundary.MAX_START_MINUTES, DayBoundary.fromSleep(listOf(400, 420, 430)))
        assertEquals("4:00 AM", DayBoundary.label(240))
        assertEquals("midnight", DayBoundary.label(0))
    }

    @Test
    fun `neutral days keep the original streak rules`() {
        val done = back(0, 1, 3)
        assertEquals(2, StreakEngine.compute(done, today).current)
        assertEquals(3, StreakEngine.compute(done, today) { it == today.minusDays(2) }.current)
    }
}

class HeatmapTest {

    private val today = LocalDate.of(2026, 10, 31) // Saturday

    @Test
    fun `grid ends with the current week and marks each day`() {
        val activity = Activity(id = 1, name = "Reading", unit = "pages", dailyGoal = 10f,
            scheduleType = Schedule.WEEKDAYS, scheduleDays = 0b0011111)
        val progress = mapOf(today.minusDays(1) to 10f, today.minusDays(2) to 4f)
        val grid = Heatmap.build(activity, progress, setOf(today.minusDays(3)), null, today, today.minusDays(30))
        assertEquals(Heatmap.WEEKS, grid.size)
        val lastWeek = grid.last()
        assertEquals(DayOfWeek.MONDAY, lastWeek.first().date.dayOfWeek)
        assertEquals(HeatState.REST, lastWeek[5].state) // today, Saturday, rest day
        assertEquals(HeatState.FUTURE, lastWeek[6].state)
        assertEquals(HeatState.DONE, lastWeek[4].state)
        assertEquals(HeatState.PARTIAL, lastWeek[3].state)
        assertEquals(0.4f, lastWeek[3].level, 1e-6f)
        assertEquals(HeatState.SKIPPED, lastWeek[2].state)
        assertEquals(HeatState.BEFORE, grid.first().first().state)
    }

    @Test
    fun `limit days are good unless over`() {
        val activity = Activity(id = 1, name = "YouTube", unit = "hrs", dailyGoal = 1f, goalType = Goals.AT_MOST)
        val grid = Heatmap.build(activity, mapOf(today to 2f), emptySet(), null, today, today.minusDays(5))
        assertEquals(HeatState.OVER, grid.last()[5].state)
        assertEquals(HeatState.DONE, grid.last()[4].state)
    }
}

class CommitmentTest {

    private val today = LocalDate.of(2026, 10, 31)
    private val reading = Activity(id = 1, name = "Reading", unit = "hrs", dailyGoal = 2f)

    @Test
    fun `an easier goal waits a week in commitment mode`() {
        val saved = dev.diligent.app.planning.Commitment.apply(reading, reading.copy(dailyGoal = 1f), on = true, today = today)
        assertEquals(2f, saved.dailyGoal)
        assertEquals(1f, saved.pendingGoal)
        assertEquals("2026-11-07", saved.pendingGoalDate)
    }

    @Test
    fun `a harder goal applies at once and clears a waiting change`() {
        val waiting = reading.copy(pendingGoal = 1f, pendingGoalDate = "2026-11-07")
        val saved = dev.diligent.app.planning.Commitment.apply(waiting, waiting.copy(dailyGoal = 3f), on = true, today = today)
        assertEquals(3f, saved.dailyGoal)
        assertEquals("", saved.pendingGoalDate)
    }

    @Test
    fun `a higher limit is the easier direction`() {
        val limit = reading.copy(goalType = Goals.AT_MOST, dailyGoal = 1f)
        assertTrue(dev.diligent.app.planning.Commitment.isEasier(limit, 2f, Goals.AT_MOST))
        assertFalse(dev.diligent.app.planning.Commitment.isEasier(limit, 0.5f, Goals.AT_MOST))
        val off = dev.diligent.app.planning.Commitment.apply(reading, reading.copy(dailyGoal = 1f), on = false, today = today)
        assertEquals(1f, off.dailyGoal)
    }
}

class DueTodayTest {

    private val today = LocalDate.of(2026, 10, 31) // Saturday
    private fun activity(type: String, days: Int = Schedule.ALL_DAYS, count: Int = 1) =
        Activity(id = 1, name = "Gym", unit = "hrs", dailyGoal = 1f, scheduleType = type, scheduleDays = days, scheduleCount = count)

    @Test
    fun `due follows the schedule`() {
        assertTrue(Schedule.isDue(activity(Schedule.DAILY), emptySet(), emptySet(), today))
        assertFalse(Schedule.isDue(activity(Schedule.DAILY), emptySet(), setOf(today), today))
        assertFalse(Schedule.isDue(activity(Schedule.WEEKDAYS, days = 0b0011111), emptySet(), emptySet(), today))
        val weekly = activity(Schedule.WEEKLY, count = 2)
        assertFalse(Schedule.isDue(weekly, setOf(today.minusDays(1), today.minusDays(3)), emptySet(), today))
        assertTrue(Schedule.isDue(weekly, setOf(today.minusDays(1)), emptySet(), today))
        val everyThird = activity(Schedule.INTERVAL, count = 3)
        assertFalse(Schedule.isDue(everyThird, setOf(today.minusDays(2)), emptySet(), today))
        assertTrue(Schedule.isDue(everyThird, setOf(today.minusDays(3)), emptySet(), today))
    }
}

class SleepAndSourcesTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `sleep onsets are minutes after midnight, evening sleep negative`() {
        val spans = listOf(
            dev.diligent.app.health.SleepSpan(at(28, 23), at(29, 7)),
            dev.diligent.app.health.SleepSpan(at(30, 2, 30), at(30, 9)),
            dev.diligent.app.health.SleepSpan(at(30, 14), at(30, 15)) // nap, ignored
        )
        assertEquals(listOf(-60, 150), dev.diligent.app.health.SleepMath.onsets(spans, zone).sorted())
        assertEquals(390, dev.diligent.app.health.SleepMath.minutesEndingBetween(spans.take(2), at(30, 0), at(30, 23)))
    }

    @Test
    fun `codeforces counts distinct accepted problems in the window`() {
        val body = """{"status":"OK","result":[
            {"creationTimeSeconds":1000,"verdict":"OK","problem":{"contestId":1900,"index":"A"}},
            {"creationTimeSeconds":1100,"verdict":"OK","problem":{"contestId":1900,"index":"A"}},
            {"creationTimeSeconds":1200,"verdict":"WRONG_ANSWER","problem":{"contestId":1900,"index":"B"}},
            {"creationTimeSeconds":10,"verdict":"OK","problem":{"contestId":1800,"index":"C"}}]}"""
        assertEquals(setOf("cf:1900A"), dev.diligent.app.automation.AutoLogSources.codeforcesSolved(body, 500, 2000))
    }

    @Test
    fun `atcoder and wakatime parse`() {
        val ac = """[{"epoch_second":1000,"result":"AC","problem_id":"abc300_a"},{"epoch_second":1001,"result":"WA","problem_id":"abc300_b"}]"""
        assertEquals(setOf("ac:abc300_a"), dev.diligent.app.automation.AutoLogSources.atcoderSolved(ac, 0, 2000))
        val waka = """{"data":[{"grand_total":{"total_seconds":5400.5}}]}"""
        assertEquals(5400.5, dev.diligent.app.automation.AutoLogSources.wakaSeconds(waka), 1e-9)
        assertEquals(1.5f, dev.diligent.app.automation.AutoLogSources.timeAmount("hrs", 5_400_000)!!, 1e-6f)
        assertNull(dev.diligent.app.automation.AutoLogSources.timeAmount("qs", 5_400_000))
    }

    @Test
    fun `external commands parse from links and extras`() {
        val log = dev.diligent.app.automation.ExternalCommand.parse("log", mapOf("activity" to "LeetCode", "amount" to "2"))
        assertEquals(dev.diligent.app.automation.ExternalCommand.Log("LeetCode", 2f), log)
        val focus = dev.diligent.app.automation.ExternalCommand.parse("FOCUS", mapOf("activity" to "3", "mode" to "start"))
        assertEquals(dev.diligent.app.automation.ExternalCommand.Focus("3", "start", false), focus)
        assertNull(dev.diligent.app.automation.ExternalCommand.parse("set", mapOf("activity" to "Reading")))
        assertNull(dev.diligent.app.automation.ExternalCommand.parse("explode", mapOf("activity" to "Reading")))
        val activities = listOf(Activity(id = 3, name = "Quantum Computing"), Activity(id = 4, name = "LeetCode"))
        assertEquals(4L, dev.diligent.app.automation.ExternalCommand.resolve(activities, "leetcode")?.id)
        assertEquals(3L, dev.diligent.app.automation.ExternalCommand.resolve(activities, "3")?.id)
        assertEquals(3L, dev.diligent.app.automation.ExternalCommand.resolve(activities, "quantum")?.id)
    }
}
