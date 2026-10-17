package dev.diligent.app.tools

import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.health.HealthMath
import dev.diligent.app.health.WorkoutSpan
import dev.diligent.app.leetcode.data.DifficultyCounts
import dev.diligent.app.leetcode.data.LeetCodeSnapshot
import dev.diligent.app.leetcode.data.RecentAc
import dev.diligent.app.review.WeeklyReviewBuilder
import dev.diligent.app.screentime.UsageCounter
import dev.diligent.app.screentime.UsageEvent
import dev.diligent.app.top3.CalendarBusy
import dev.diligent.app.top3.CalendarEvent
import dev.diligent.app.versus.VersusApi
import dev.diligent.app.versus.VersusBoard
import androidx.health.connect.client.records.ExerciseSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Weekly review, Versus, calendar busy time, screen time and Health maths. */
class ToolsLogicTest {

    private val ist = ZoneId.of("Asia/Kolkata")
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, ist).toInstant().toEpochMilli()

    // ─── Weekly review ──────────────────────────────────────────

    @Test
    fun `weekly review totals goals, hours and best day`() {
        val gym = Activity(id = 1, name = "Gym", unit = "hrs", dailyGoal = 1f)
        val lc = Activity(id = 2, name = "LeetCode", unit = "qs", dailyGoal = 3f)
        val monday = LocalDate.of(2026, 10, 12)
        val rows = listOf(
            DailyProgress(activityId = 1, date = "2026-10-12", progress = 1f, isCompleted = true),
            DailyProgress(activityId = 1, date = "2026-10-13", progress = 1.5f, isCompleted = true),
            DailyProgress(activityId = 2, date = "2026-10-13", progress = 3f, isCompleted = true),
            DailyProgress(activityId = 2, date = "2026-10-14", progress = 1f, isCompleted = false),
            DailyProgress(activityId = 1, date = "2026-10-05", progress = 9f, isCompleted = true) // last week
        )
        val leet = LeetCodeSnapshot("me", 0L, calendar = mapOf("2026-10-11" to 9, "2026-10-12" to 2, "2026-10-14" to 3))
        val r = WeeklyReviewBuilder.build(
            activities = listOf(gym, lc), progress = rows, weekStart = monday, today = LocalDate.of(2026, 10, 18),
            streaks = mapOf(1L to 2), leetcode = leet,
            github = listOf("dev" to setOf(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 1))), suggestion = null
        )
        assertEquals(7, r.days)
        assertEquals(3f / 14f, r.completion, 0.001f)
        assertEquals(2.5f, r.hours, 0.001f) // hours only from time units
        assertEquals(LocalDate.of(2026, 10, 13), r.bestDay)
        assertEquals(5, r.leetcodeSubmissions)
        assertEquals(1, r.github.single().second)
        assertEquals(WeeklyReviewBuilder.weekStart(LocalDate.of(2026, 10, 18)), monday)
    }

    // ─── Versus ─────────────────────────────────────────────────

    @Test
    fun `versus query aliases every player`() {
        val q = VersusApi.query(2)
        assertTrue(q.contains("\$u0: String!, \$u1: String!"))
        assertTrue(q.contains("p1: matchedUser(username: \$u1)"))
        assertTrue(q.contains("r1: recentAcSubmissionList(username: \$u1, limit: 20)"))
    }

    @Test
    fun `versus parse keeps order and marks unknown users`() {
        val body = """
            {"errors":[{"message":"That user does not exist.","path":["p1"]}],
             "data":{
               "p0":{"username":"Me","submitStatsGlobal":{"acSubmissionNum":[{"difficulty":"All","count":10}]},"userCalendar":{"submissionCalendar":"{}"}},
               "r0":[{"title":"Two Sum","titleSlug":"two-sum","timestamp":"1792195300"}],
               "p1":null,"r1":[]
             }}
        """.trimIndent()
        val out = VersusApi.parse(body, 2, 5L) as VersusApi.Outcome.Success
        assertEquals("Me", out.players[0]?.username)
        assertEquals(10, out.players[0]?.solved?.all)
        assertNull(out.players[1])
    }

    @Test
    fun `leaderboard ranks today's solves, then streak`() {
        val now = at(17, 21) // UTC day 2026-10-17
        val todaySec = ZonedDateTime.of(2026, 10, 17, 10, 0, 0, 0, ZoneId.of("UTC")).toEpochSecond()
        fun player(name: String, solvesToday: Int, streakDays: Int, total: Int) = LeetCodeSnapshot(
            username = name, fetchedAt = now, solved = DifficultyCounts(all = total),
            calendar = (1..streakDays).associate { LocalDate.of(2026, 10, 17).minusDays(it.toLong()).toString() to 1 },
            recentAc = (0 until solvesToday).map { RecentAc("p$it", "p$it", todaySec + it) }
        )
        val rows = VersusBoard.rows(
            "me",
            listOf("me" to player("me", 0, 5, 300), "alex" to player("alex", 2, 1, 100), "sam" to player("sam", 0, 9, 50), "ghost" to null),
            now
        )
        assertEquals(listOf("alex", "sam", "me", "ghost"), rows.map { it.username })
        assertEquals("alex", VersusBoard.rivalAhead(rows)?.username)
    }

    // ─── Calendar busy time ─────────────────────────────────────

    @Test
    fun `busy time merges overlaps and clips to the window`() {
        val events = listOf(
            CalendarEvent("A", at(17, 9), at(17, 10)),
            CalendarEvent("B", at(17, 9, 30), at(17, 11)),   // overlaps A
            CalendarEvent("C", at(17, 23), at(18, 1))        // crosses midnight
        )
        assertEquals(2 * 3_600_000L, CalendarBusy.busyBetween(events, at(17, 0), at(17, 12)))
        val s = CalendarBusy.summarize(events, at(17, 8), LocalDate.of(2026, 10, 17), ist)
        assertEquals(3 * 3_600_000L, s.busyMillis)               // 9–11 plus 23–24
        assertEquals(12 * 3_600_000L, s.freeLeftMillis)          // 08–22 minus 2 h
        assertEquals("A", s.next?.title)
    }

    // ─── Screen time ────────────────────────────────────────────

    @Test
    fun `foreground time pairs resumes and pauses`() {
        val from = at(17, 0)
        val to = at(17, 12)
        val events = listOf(
            UsageEvent("insta", UsageCounter.PAUSED, at(17, 0, 10)),          // open since midnight
            UsageEvent("yt", UsageCounter.RESUMED, at(17, 9)),
            UsageEvent("yt", UsageCounter.PAUSED, at(17, 9, 30)),
            UsageEvent("insta", UsageCounter.RESUMED, at(17, 10)),
            UsageEvent("x", UsageCounter.RESUMED, at(17, 10, 20)),           // replaces insta
            UsageEvent("insta", UsageCounter.PAUSED, at(17, 10, 21)),
            UsageEvent("x", UsageCounter.SCREEN_OFF, at(17, 10, 50)),
            UsageEvent("yt", UsageCounter.RESUMED, at(17, 11, 45))           // still open at "now"
        )
        val t = UsageCounter.foregroundTime(events, from, to)
        assertEquals((10 + 20) * 60_000L, t["insta"])
        assertEquals((30 + 15) * 60_000L, t["yt"])
        assertEquals(30 * 60_000L, t["x"])
    }

    @Test
    fun `focus counts only time units`() {
        val acts = listOf(
            Activity(id = 1, name = "Q", unit = "hrs", dailyGoal = 5f),
            Activity(id = 2, name = "LC", unit = "qs", dailyGoal = 3f),
            Activity(id = 3, name = "Read", unit = "mins", dailyGoal = 30f)
        )
        val rows = listOf(
            DailyProgress(activityId = 1, date = "d", progress = 1.5f),
            DailyProgress(activityId = 2, date = "d", progress = 4f),
            DailyProgress(activityId = 3, date = "d", progress = 30f)
        )
        assertEquals(2 * 3_600_000L, UsageCounter.focusMillis(acts, rows))
    }

    // ─── Health ─────────────────────────────────────────────────

    @Test
    fun `workout minutes merge overlaps and respect the gym filter`() {
        val strength = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        val walk = ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        val spans = listOf(
            WorkoutSpan(strength, at(17, 7), at(17, 8)),
            WorkoutSpan(strength, at(17, 7, 30), at(17, 8, 15)),  // phone + watch duplicate
            WorkoutSpan(walk, at(17, 18), at(17, 18, 40)),
            WorkoutSpan(strength, at(16, 23), at(17, 0, 30))      // started yesterday
        )
        val from = at(17, 0)
        val to = at(17, 21)
        assertEquals(30L + 75L, HealthMath.minutes(spans, from, to, gymOnly = true))
        assertEquals(30L + 75L + 40L, HealthMath.minutes(spans, from, to, gymOnly = false))
        assertEquals(3, HealthMath.sessions(spans, from, to, gymOnly = true))
        assertEquals(1.75f, HealthMath.progress("hrs", 105, 3), 0.001f)
        assertEquals(3f, HealthMath.progress("sessions", 105, 3), 0.001f)
    }
}
