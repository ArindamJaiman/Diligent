package dev.diligent.app.widget

import dev.diligent.app.data.repository.GithubContributionFetcher
import dev.diligent.app.data.repository.GithubContributionFetcher.ParsedContribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ContributionGridTest {

    // A Wednesday
    private val today = LocalDate.of(2026, 10, 7)

    @Test
    fun `last column is the current week and future days are blank`() {
        val levels = mapOf("2026-10-07" to 4, "2026-10-04" to 2)
        val weeks = ContributionGrid.buildWeeks(levels, weeks = 20, today = today)

        assertEquals(20, weeks.size)
        weeks.forEach { assertEquals(7, it.size) }

        val current = weeks.last()
        assertEquals(2, current[0]) // Sunday Oct 4
        assertEquals(4, current[3]) // Wednesday Oct 7 (today)
        assertEquals(ContributionGrid.FUTURE, current[4])
        assertEquals(ContributionGrid.FUTURE, current[6])
    }

    @Test
    fun `renders all seven days and every requested week`() {
        // Regression: Glance dropped children past the 10th, leaving 5 old weeks x 5 days.
        val weeks = ContributionGrid.buildWeeks(emptyMap(), weeks = 24, today = today)
        assertEquals(24, weeks.size)
        assertTrue(weeks.dropLast(1).all { column -> column.none { it == ContributionGrid.FUTURE } })
    }

    @Test
    fun `fit respects width and height limits`() {
        val g = ContributionGrid.fit(
            availableWidthDp = 298f, availableHeightDp = 130f, preferredCellDp = 14f, gapDp = 2.5f,
            minCellDp = 5.5f, maxCellDp = 16f, minWeeks = 5, maxWeeks = 24
        )
        assertTrue(g.widthDp <= 298f)
        assertTrue(g.heightDp <= 130f)
        assertEquals(18, g.weeks)
    }

    @Test
    fun `streak counts back from today or yesterday`() {
        val days = listOf(
            ParsedContribution("2026-10-04", 1),
            ParsedContribution("2026-10-05", 2),
            ParsedContribution("2026-10-06", 3),
            ParsedContribution("2026-10-07", 0)
        )
        assertEquals(3, GithubContributionFetcher.calculateStreak(days, today))
        assertEquals(0, GithubContributionFetcher.calculateStreak(days, today.plusDays(2)))
    }

    @Test
    fun `parses the real yearly contribution total`() {
        val html = """
            <h2 id="js-contribution-activity-description" class="f4 text-normal mb-2">
              1,234
              contributions
                in the last year
            </h2>
            <td data-date="2026-10-06" data-level="2" class="ContributionCalendar-day"></td>
            <td data-date="2026-10-07" data-level="0" class="ContributionCalendar-day"></td>
        """.trimIndent()
        val result = GithubContributionFetcher.parseHtml(html)
        assertEquals(1234, result.totalAnnual)
        assertEquals(2, result.contributions.size)
    }

    @Test
    fun `falls back to summing tooltips`() {
        val html = """
            <td data-date="2026-10-06" data-level="2" class="ContributionCalendar-day"></td>
            <tool-tip>5 contributions on October 6th.</tool-tip>
            <tool-tip>1 contribution on October 7th.</tool-tip>
        """.trimIndent()
        assertEquals(6, GithubContributionFetcher.parseHtml(html).totalAnnual)
    }

    @Test
    fun `cache round-trips and tolerates garbage`() {
        val list = listOf(ParsedContribution("2026-10-06", 3))
        assertEquals(list, GithubContributionFetcher.decode(GithubContributionFetcher.encode(list)))
        assertEquals(emptyList<ParsedContribution>(), GithubContributionFetcher.decode("[{\"a\":\"x\"}]"))
    }
}
