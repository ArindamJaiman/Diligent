package dev.diligent.app.contests

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

enum class Platform(val label: String, val short: String, val color: Long) {
    CODEFORCES("Codeforces", "CF", 0xFF3B82F6),
    ATCODER("AtCoder", "AC", 0xFFE0E0E0),
    CODECHEF("CodeChef", "CC", 0xFFB45309),
    LEETCODE("LeetCode", "LC", 0xFFFFA116);

    companion object {
        /** Platforms Contest Radar fetches itself; LeetCode comes from the LeetCode tracker's cache. */
        val FETCHED = listOf(CODEFORCES, ATCODER, CODECHEF)
    }
}

@Serializable
data class RadarContest(
    val platform: Platform,
    val id: String,
    val name: String,
    val startMillis: Long,
    val durationSec: Long,
    val url: String
) {
    val endMillis: Long get() = startMillis + durationSec * 1000
}

@Serializable
data class RadarRating(
    val platform: Platform,
    val handle: String,
    val rating: Int?,
    val maxRating: Int?,
    val title: String? = null
)

/**
 * Public contest schedules and ratings. All parsers are pure and unit tested; the fetchers
 * are plain HTTPS GETs with no login.
 *
 * - Codeforces: official API (contest.list, user.info)
 * - AtCoder: the "Upcoming Contests" table on atcoder.jp/contests, and users/<id>/history/json
 * - CodeChef: the contest list JSON the website uses, and the rating history embedded in a profile page
 */
object ContestSources {

    private val json = Json { ignoreUnknownKeys = true }
    private val atcoderTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZ")

    // ─── Codeforces ─────────────────────────────────────────────

    fun parseCodeforcesContests(body: String, now: Long, horizonMillis: Long): List<RadarContest> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        if (root["status"].str() != "OK") return emptyList()
        return (root["result"] as? JsonArray).orEmpty().mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val phase = o["phase"].str()
            if (phase != "BEFORE" && phase != "CODING") return@mapNotNull null
            val id = o["id"].str() ?: return@mapNotNull null
            val start = (o["startTimeSeconds"].str()?.toLongOrNull() ?: return@mapNotNull null) * 1000
            val duration = o["durationSeconds"].str()?.toLongOrNull() ?: 7200
            if (start > now + horizonMillis || start + duration * 1000 < now) return@mapNotNull null
            RadarContest(Platform.CODEFORCES, id, o["name"].str() ?: "Codeforces Round", start, duration, "https://codeforces.com/contest/$id")
        }.sortedBy { it.startMillis }
    }

    fun parseCodeforcesRating(body: String, handle: String): RadarRating? {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        if (root["status"].str() != "OK") return null
        val user = (root["result"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        return RadarRating(
            Platform.CODEFORCES,
            user["handle"].str() ?: handle,
            rating = user["rating"].str()?.toIntOrNull(),
            maxRating = user["maxRating"].str()?.toIntOrNull(),
            title = user["rank"].str()
        )
    }

    // ─── AtCoder ────────────────────────────────────────────────

    private val atcoderRow = Regex(
        """<time class='fixtime[^']*'>([^<]+)</time>.*?<a href="/contests/([^"]+)">([^<]+)</a>.*?<td class="text-center">(\d+):(\d{2})</td>""",
        RegexOption.DOT_MATCHES_ALL
    )

    /** Rows of the "running" and "upcoming" tables on atcoder.jp/contests. */
    fun parseAtCoderContests(html: String, now: Long, horizonMillis: Long): List<RadarContest> {
        val sections = listOf("contest-table-action", "contest-table-upcoming").mapNotNull { id ->
            val start = html.indexOf("id=\"$id\"").takeIf { it >= 0 } ?: return@mapNotNull null
            val end = html.indexOf("</table>", start).takeIf { it >= 0 } ?: html.length
            html.substring(start, end)
        }
        return sections.flatMap { section ->
            section.split("<tr").mapNotNull { row ->
                val m = atcoderRow.find(row) ?: return@mapNotNull null
                val (time, slug, name, hours, minutes) = m.destructured
                val start = runCatching { OffsetDateTime.parse(time.trim(), atcoderTime).toInstant().toEpochMilli() }.getOrNull()
                    ?: return@mapNotNull null
                val duration = hours.toLong() * 3600 + minutes.toLong() * 60
                if (start > now + horizonMillis || start + duration * 1000 < now) return@mapNotNull null
                RadarContest(Platform.ATCODER, slug, unescape(name.trim()), start, duration, "https://atcoder.jp/contests/$slug")
            }
        }.distinctBy { it.id }.sortedBy { it.startMillis }
    }

    fun parseAtCoderRating(body: String, handle: String): RadarRating? {
        val history = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return null
        val rated = history.mapNotNull { it as? JsonObject }.filter { it["IsRated"].str() == "true" }
        if (rated.isEmpty()) return RadarRating(Platform.ATCODER, handle, null, null)
        val ratings = rated.mapNotNull { it["NewRating"].str()?.toIntOrNull() }
        return RadarRating(Platform.ATCODER, handle, ratings.lastOrNull(), ratings.maxOrNull())
    }

    // ─── CodeChef ───────────────────────────────────────────────

    fun parseCodeChefContests(body: String, now: Long, horizonMillis: Long): List<RadarContest> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        return listOf("present_contests", "future_contests").flatMap { key ->
            (root[key] as? JsonArray).orEmpty().mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val code = o["contest_code"].str() ?: return@mapNotNull null
                val start = runCatching { OffsetDateTime.parse(o["contest_start_date_iso"].str()).toInstant().toEpochMilli() }.getOrNull()
                    ?: return@mapNotNull null
                val duration = (o["contest_duration"].str()?.toLongOrNull() ?: 120) * 60
                if (start > now + horizonMillis || start + duration * 1000 < now) return@mapNotNull null
                RadarContest(Platform.CODECHEF, code, o["contest_name"].str() ?: code, start, duration, "https://www.codechef.com/$code")
            }
        }.distinctBy { it.id }.sortedBy { it.startMillis }
    }

    private val codechefHistory = Regex("""var all_rating = (\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)

    fun parseCodeChefRating(html: String, handle: String): RadarRating? {
        val raw = codechefHistory.find(html)?.groupValues?.get(1) ?: return null
        val history = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return null
        val ratings = history.mapNotNull { (it as? JsonObject)?.get("rating").str()?.toIntOrNull() }
        return RadarRating(Platform.CODECHEF, handle, ratings.lastOrNull(), ratings.maxOrNull())
    }

    // ─── Network ────────────────────────────────────────────────

    fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Diligent")
            connection.setRequestProperty("Accept-Language", "en")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    fun contestsUrl(platform: Platform): String? = when (platform) {
        Platform.CODEFORCES -> "https://codeforces.com/api/contest.list?gym=false"
        Platform.ATCODER -> "https://atcoder.jp/contests/?lang=en"
        Platform.CODECHEF -> "https://www.codechef.com/api/list/contests/all?sort_by=START&sorting_order=asc&offset=0&mode=all"
        Platform.LEETCODE -> null
    }

    fun ratingUrl(platform: Platform, handle: String): String? {
        val h = URLEncoder.encode(handle.trim(), "UTF-8")
        return when (platform) {
            Platform.CODEFORCES -> "https://codeforces.com/api/user.info?handles=$h"
            Platform.ATCODER -> "https://atcoder.jp/users/$h/history/json"
            Platform.CODECHEF -> "https://www.codechef.com/users/$h"
            Platform.LEETCODE -> null
        }
    }

    fun parseContests(platform: Platform, body: String, now: Long, horizonMillis: Long): List<RadarContest> = when (platform) {
        Platform.CODEFORCES -> parseCodeforcesContests(body, now, horizonMillis)
        Platform.ATCODER -> parseAtCoderContests(body, now, horizonMillis)
        Platform.CODECHEF -> parseCodeChefContests(body, now, horizonMillis)
        Platform.LEETCODE -> emptyList()
    }

    fun parseRating(platform: Platform, body: String, handle: String): RadarRating? = when (platform) {
        Platform.CODEFORCES -> parseCodeforcesRating(body, handle)
        Platform.ATCODER -> parseAtCoderRating(body, handle)
        Platform.CODECHEF -> parseCodeChefRating(body, handle)
        Platform.LEETCODE -> null
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">")

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
}
