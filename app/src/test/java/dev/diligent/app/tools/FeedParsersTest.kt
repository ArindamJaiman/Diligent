package dev.diligent.app.tools

import dev.diligent.app.contests.ContestSources
import dev.diligent.app.contests.Platform
import dev.diligent.app.papers.ArxivFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime

/** Parsers for Codeforces, AtCoder, CodeChef and arXiv, against bodies shaped like the real ones. */
class FeedParsersTest {

    private val now = OffsetDateTime.parse("2026-10-17T00:00:00+05:30").toInstant().toEpochMilli()
    private val horizon = 14 * 86_400_000L

    @Test
    fun `codeforces keeps upcoming and running contests within the horizon`() {
        val start = now / 1000 + 3600
        val body = """
            {"status":"OK","result":[
              {"id":2272,"name":"Codeforces Round (Div. 2)","type":"CF","phase":"BEFORE","durationSeconds":8100,"startTimeSeconds":$start},
              {"id":2270,"name":"Live round","phase":"CODING","durationSeconds":7200,"startTimeSeconds":${now / 1000 - 600}},
              {"id":2999,"name":"Far future","phase":"BEFORE","durationSeconds":7200,"startTimeSeconds":${now / 1000 + 40 * 86400}},
              {"id":2000,"name":"Old","phase":"FINISHED","durationSeconds":7200,"startTimeSeconds":1700000000}
            ]}
        """.trimIndent()
        val list = ContestSources.parseCodeforcesContests(body, now, horizon)
        assertEquals(listOf("2270", "2272"), list.map { it.id })
        assertEquals("https://codeforces.com/contest/2272", list[1].url)
        assertEquals(8100L, list[1].durationSec)
    }

    @Test
    fun `codeforces rating`() {
        val r = ContestSources.parseCodeforcesRating(
            """{"status":"OK","result":[{"handle":"tourist","rating":3343,"maxRating":4009,"rank":"legendary grandmaster"}]}""", "Tourist"
        )!!
        assertEquals(3343, r.rating)
        assertEquals(4009, r.maxRating)
        assertEquals("tourist", r.handle)
    }

    @Test
    fun `atcoder upcoming table`() {
        val html = """
            <div id="contest-table-upcoming">
            <table><tbody>
            <tr>
            <td class="text-center"><a href='http://www.timeanddate.com/x'><time class='fixtime fixtime-full'>2026-10-17 21:00:00+0900</time></a></td>
            <td ><span title="Algorithm">Ⓐ</span> <a href="/contests/abc480">AtCoder Beginner Contest 480</a></td>
            <td class="text-center">01:40</td>
            <td class="text-center"> - 1999</td>
            </tr>
            <tr>
            <td class="text-center"><a href='x'><time class='fixtime fixtime-full'>2026-10-25 21:00:00+0900</time></a></td>
            <td ><a href="/contests/arc233">AtCoder Regular Contest &amp; More 233</a></td>
            <td class="text-center">02:30</td>
            <td class="text-center">1600 - 2999</td>
            </tr>
            </tbody></table>
            </div>
        """.trimIndent()
        val list = ContestSources.parseAtCoderContests(html, now, horizon)
        assertEquals(listOf("abc480", "arc233"), list.map { it.id })
        assertEquals(OffsetDateTime.parse("2026-10-17T21:00:00+09:00").toInstant().toEpochMilli(), list[0].startMillis)
        assertEquals(100 * 60L, list[0].durationSec)
        assertEquals("AtCoder Regular Contest & More 233", list[1].name)
    }

    @Test
    fun `atcoder rating from history`() {
        val r = ContestSources.parseAtCoderRating(
            """[{"IsRated":true,"NewRating":800},{"IsRated":false,"NewRating":0},{"IsRated":true,"NewRating":1250},{"IsRated":true,"NewRating":1100}]""",
            "me"
        )!!
        assertEquals(1100, r.rating)
        assertEquals(1250, r.maxRating)
    }

    @Test
    fun `codechef contests and rating`() {
        val body = """
            {"status":"success","present_contests":[],
             "future_contests":[
               {"contest_code":"START261","contest_name":"Starters 261","contest_start_date_iso":"2026-10-21T20:00:00+05:30","contest_duration":"120"},
               {"contest_code":"DSAMONDAY025","contest_name":"Monday Munch","contest_start_date_iso":"2026-10-19T19:00:00+05:30","contest_duration":"180"}
             ]}
        """.trimIndent()
        val list = ContestSources.parseCodeChefContests(body, now, horizon)
        assertEquals(listOf("DSAMONDAY025", "START261"), list.map { it.id })
        assertEquals(Platform.CODECHEF, list[0].platform)
        assertEquals(10_800L, list[0].durationSec)

        val html = """<script>var all_rating = [{"code":"A","rating":"1396"},{"code":"B","rating":"1705"},{"code":"C","rating":"1650"}];</script>"""
        val r = ContestSources.parseCodeChefRating(html, "me")!!
        assertEquals(1650, r.rating)
        assertEquals(1705, r.maxRating)
    }

    private val rss = """
        <?xml version='1.0' encoding='UTF-8'?>
        <rss xmlns:arxiv="http://arxiv.org/schemas/atom" xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0">
          <channel>
            <title>quant-ph updates on arXiv.org</title>
            <link>http://rss.arxiv.org/rss/quant-ph</link>
            <item>
              <title>Surface codes at scale</title>
              <link>https://arxiv.org/abs/2610.20654</link>
              <description>arXiv:2610.20654v1 Announce Type: new
        Abstract: We study   surface codes &amp; decoders.</description>
              <guid isPermaLink="false">oai:arXiv.org:2610.20654v1</guid>
              <category>quant-ph</category>
              <category>cs.IT</category>
              <dc:creator>Ada Lovelace, Alan Turing</dc:creator>
              <arxiv:announce_type>new</arxiv:announce_type>
            </item>
            <item>
              <title>Market making with queues</title>
              <link>https://arxiv.org/abs/2610.10001</link>
              <description>arXiv:2610.10001v2 Announce Type: replace Abstract: Old paper.</description>
              <category>q-fin.TR</category>
              <dc:creator>Someone</dc:creator>
              <arxiv:announce_type>replace</arxiv:announce_type>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun `arxiv rss parses items`() {
        val papers = ArxivFeed.parse(rss)
        assertEquals(2, papers.size)
        val p = papers[0]
        assertEquals("2610.20654", p.id)
        assertEquals("Surface codes at scale", p.title)
        assertEquals("Ada Lovelace, Alan Turing", p.authors)
        assertEquals("We study surface codes & decoders.", p.abstract)
        assertEquals(listOf("quant-ph", "cs.IT"), p.categories)
        assertEquals("https://arxiv.org/pdf/2610.20654", p.pdfUrl)
        assertEquals("replace", papers[1].announceType)
    }

    @Test
    fun `pick of the day prefers new papers and is stable for the day`() {
        val papers = ArxivFeed.parse(rss)
        val day = LocalDate.of(2026, 10, 17)
        val pick = ArxivFeed.pickOfDay(papers, day)
        assertNotNull(pick)
        assertEquals("new", pick!!.announceType)
        assertEquals(pick, ArxivFeed.pickOfDay(papers, day))
        // Excluding the only new paper falls back to the rest.
        assertEquals("2610.10001", ArxivFeed.pickOfDay(papers, day, exclude = setOf("2610.20654"))?.id)
        assertTrue(ArxivFeed.url(listOf("quant-ph", "q-fin.TR")).endsWith("/rss/quant-ph+q-fin.TR"))
    }
}
