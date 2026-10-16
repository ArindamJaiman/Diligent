package dev.diligent.app.leetcode

import dev.diligent.app.leetcode.data.FetchOutcome
import dev.diligent.app.leetcode.data.LeetCodeApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LeetCodeApiTest {

    private fun dayStart(date: String) = LocalDate.parse(date).toEpochDay() * 86_400L

    /** Same shape as a real response from leetcode.com/graphql (values are made up). */
    private val body = """
        {"data":{
          "matchedUser":{"username":"Coder_01","profile":{"ranking":123456},
            "submitStatsGlobal":{"acSubmissionNum":[
              {"difficulty":"All","count":300},{"difficulty":"Easy","count":120},
              {"difficulty":"Medium","count":150},{"difficulty":"Hard","count":30}]},
            "userCalendar":{"submissionCalendar":"{\"${dayStart("2026-10-17")}\": 3, \"${dayStart("2026-10-16")}\": 1, \"${dayStart("2026-10-10")}\": 0}"}},
          "allQuestionsCount":[{"difficulty":"All","count":4082},{"difficulty":"Easy","count":971},
            {"difficulty":"Medium","count":2128},{"difficulty":"Hard","count":983}],
          "recentAcSubmissionList":[
            {"title":"Two Sum","titleSlug":"two-sum","timestamp":"${dayStart("2026-10-16") + 3600}"},
            {"title":"Trapping Rain Water","titleSlug":"trapping-rain-water","timestamp":"${dayStart("2026-10-17") + 7200}"}],
          "userContestRanking":{"rating":1834.62,"attendedContestsCount":12,"globalRanking":40210,"topPercentage":8.4},
          "activeDailyCodingChallengeQuestion":{"date":"2026-10-17","link":"/problems/trapping-rain-water/",
            "question":{"title":"Trapping Rain Water","titleSlug":"trapping-rain-water","difficulty":"Hard",
              "questionFrontendId":"42","acRate":61.25}},
          "topTwoContests":[
            {"title":"Biweekly Contest 194","titleSlug":"biweekly-contest-194","startTime":1792852200,"duration":5400},
            {"title":"Weekly Contest 524","titleSlug":"weekly-contest-524","startTime":1792290600,"duration":5400}]
        }}
    """.trimIndent()

    private fun parsed() = (LeetCodeApi.parse(body, now = 1_000L) as FetchOutcome.Success).snapshot

    @Test
    fun `reads profile counts and totals`() {
        val s = parsed()
        assertEquals("Coder_01", s.username)
        assertEquals(123456, s.ranking)
        assertEquals(300, s.solved.all)
        assertEquals(120, s.solved.easy)
        assertEquals(150, s.solved.medium)
        assertEquals(30, s.solved.hard)
        assertEquals(983, s.totals.hard)
        assertEquals(1_000L, s.fetchedAt)
    }

    @Test
    fun `calendar keys become UTC dates and empty days are dropped`() {
        assertEquals(mapOf("2026-10-16" to 1, "2026-10-17" to 3), parsed().calendar)
    }

    @Test
    fun `recent accepts parse string timestamps newest first`() {
        val recent = parsed().recentAc
        assertEquals(listOf("trapping-rain-water", "two-sum"), recent.map { it.slug })
        assertEquals(dayStart("2026-10-17") + 7200, recent.first().timestamp)
    }

    @Test
    fun `daily challenge, contest rating and contests`() {
        val s = parsed()
        assertEquals("42", s.daily?.frontendId)
        assertEquals("Hard", s.daily?.difficulty)
        assertEquals("https://leetcode.com/problems/trapping-rain-water/", s.daily?.url)
        assertEquals(1834.62, s.contest!!.rating, 0.001)
        assertEquals(12, s.contest!!.attended)
        // Sorted by start time, whatever order LeetCode returns them in
        assertEquals(listOf("weekly-contest-524", "biweekly-contest-194"), s.upcoming.map { it.slug })
    }

    @Test
    fun `no contest history means no rating`() {
        val noContests = body.replace(
            """"userContestRanking":{"rating":1834.62,"attendedContestsCount":12,"globalRanking":40210,"topPercentage":8.4}""",
            """"userContestRanking":null"""
        )
        val s = (LeetCodeApi.parse(noContests, 0L) as FetchOutcome.Success).snapshot
        assertNull(s.contest)
    }

    @Test
    fun `unknown user is reported as such`() {
        val missing = """
            {"errors":[{"message":"That user does not exist.","path":["matchedUser"]}],
             "data":{"matchedUser":null,"recentAcSubmissionList":[],"userContestRanking":null}}
        """.trimIndent()
        assertEquals(FetchOutcome.UserNotFound, LeetCodeApi.parse(missing, 0L))
    }

    @Test
    fun `graphql errors and garbage are failures, not empty data`() {
        val error = LeetCodeApi.parse("""{"errors":[{"message":"Cannot query field"}]}""", 0L)
        assertEquals(FetchOutcome.Failed("Cannot query field"), error)
        assertTrue(LeetCodeApi.parse("<html>blocked</html>", 0L) is FetchOutcome.Failed)
    }

    @Test
    fun `malformed calendar string is ignored`() {
        assertEquals(emptyMap<String, Int>(), LeetCodeApi.parseCalendar("not json"))
        assertEquals(emptyMap<String, Int>(), LeetCodeApi.parseCalendar(null))
    }
}
