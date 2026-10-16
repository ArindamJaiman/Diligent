package dev.diligent.app.leetcode.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.time.LocalDate

sealed interface FetchOutcome {
    data class Success(val snapshot: LeetCodeSnapshot) : FetchOutcome
    data object UserNotFound : FetchOutcome
    data class Failed(val message: String) : FetchOutcome
}

/**
 * Reads LeetCode's public GraphQL endpoint — the same one leetcode.com's profile page uses.
 * No login or cookies: only data anyone can see on the public profile.
 *
 * Everything the app needs comes back in ONE request (profile, calendar, recent accepted
 * submissions, contest rating, today's daily challenge, next contests), so a reminder
 * check costs a single small HTTPS call.
 */
object LeetCodeApi {

    private const val ENDPOINT = "https://leetcode.com/graphql"

    internal val QUERY = """
        query diligent(${'$'}username: String!) {
          matchedUser(username: ${'$'}username) {
            username
            profile { ranking }
            submitStatsGlobal { acSubmissionNum { difficulty count } }
            userCalendar { submissionCalendar }
          }
          allQuestionsCount { difficulty count }
          recentAcSubmissionList(username: ${'$'}username, limit: 20) { title titleSlug timestamp }
          userContestRanking(username: ${'$'}username) { rating attendedContestsCount globalRanking topPercentage }
          activeDailyCodingChallengeQuestion {
            date
            question { title titleSlug difficulty questionFrontendId acRate }
          }
          topTwoContests { title titleSlug startTime duration }
        }
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    fun fetch(username: String, now: Long = System.currentTimeMillis()): FetchOutcome {
        val body = buildJsonObject {
            put("query", QUERY)
            putJsonObject("variables") { put("username", username.trim()) }
        }.toString()

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Referer", "https://leetcode.com")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Diligent")
            }
            connection.outputStream.use { it.write(body.toByteArray()) }
            when (val code = connection.responseCode) {
                in 200..299 -> parse(connection.inputStream.bufferedReader().use { it.readText() }, now)
                429 -> FetchOutcome.Failed("Rate limited by LeetCode — try again in a few minutes")
                403 -> FetchOutcome.Failed("LeetCode refused the request (HTTP 403)")
                else -> FetchOutcome.Failed("LeetCode returned HTTP $code")
            }
        } catch (e: UnknownHostException) {
            FetchOutcome.Failed("Offline — couldn't reach leetcode.com")
        } catch (e: SocketTimeoutException) {
            FetchOutcome.Failed("LeetCode didn't answer in time")
        } catch (e: IOException) {
            FetchOutcome.Failed("Network error: ${e.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }

    /** Pure parser for a GraphQL response body. */
    fun parse(body: String, now: Long): FetchOutcome {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return FetchOutcome.Failed("Unexpected response from LeetCode")

        val data = root["data"] as? JsonObject
        val user = data?.get("matchedUser") as? JsonObject
        if (user == null) {
            val errors = (root["errors"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.get("message").str() }
            return if (errors.any { it.contains("does not exist", ignoreCase = true) }) {
                FetchOutcome.UserNotFound
            } else {
                FetchOutcome.Failed(errors.firstOrNull() ?: "Unexpected response from LeetCode")
            }
        }

        val acCounts = ((user["submitStatsGlobal"] as? JsonObject)?.get("acSubmissionNum") as? JsonArray)
        val calendarRaw = (user["userCalendar"] as? JsonObject)?.get("submissionCalendar").str()

        val snapshot = LeetCodeSnapshot(
            username = user["username"].str() ?: "",
            fetchedAt = now,
            ranking = (user["profile"] as? JsonObject)?.get("ranking").int(),
            solved = difficultyCounts(acCounts),
            totals = difficultyCounts(data["allQuestionsCount"] as? JsonArray),
            calendar = parseCalendar(calendarRaw),
            recentAc = (data["recentAcSubmissionList"] as? JsonArray).orEmpty().mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val slug = o["titleSlug"].str() ?: return@mapNotNull null
                val ts = o["timestamp"].long() ?: return@mapNotNull null
                RecentAc(title = o["title"].str() ?: slug, slug = slug, timestamp = ts)
            }.sortedByDescending { it.timestamp },
            daily = parseDaily(data["activeDailyCodingChallengeQuestion"] as? JsonObject),
            contest = (data["userContestRanking"] as? JsonObject)?.let { c ->
                val rating = c["rating"].double() ?: return@let null
                ContestStats(
                    rating = rating,
                    attended = c["attendedContestsCount"].int() ?: 0,
                    globalRanking = c["globalRanking"].int(),
                    topPercentage = c["topPercentage"].double()
                )
            },
            upcoming = (data["topTwoContests"] as? JsonArray).orEmpty().mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                Contest(
                    title = o["title"].str() ?: return@mapNotNull null,
                    slug = o["titleSlug"].str() ?: return@mapNotNull null,
                    startTime = o["startTime"].long() ?: return@mapNotNull null,
                    durationSec = o["duration"].int() ?: 5400
                )
            }.sortedBy { it.startTime }
        )
        return FetchOutcome.Success(snapshot)
    }

    /** submissionCalendar is a JSON object inside a string: {"<UTC midnight epoch s>": count}. */
    internal fun parseCalendar(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        val obj = try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return emptyMap()
        val result = sortedMapOf<String, Int>()
        for ((key, value) in obj) {
            val seconds = key.toLongOrNull() ?: continue
            val count = value.int() ?: continue
            if (count <= 0) continue
            val date = LocalDate.ofEpochDay(Math.floorDiv(seconds, 86_400L)).toString()
            result[date] = (result[date] ?: 0) + count
        }
        return result
    }

    private fun parseDaily(obj: JsonObject?): DailyQuestion? {
        val q = obj?.get("question") as? JsonObject ?: return null
        return DailyQuestion(
            date = obj["date"].str() ?: "",
            title = q["title"].str() ?: return null,
            slug = q["titleSlug"].str() ?: return null,
            difficulty = q["difficulty"].str() ?: "",
            frontendId = q["questionFrontendId"].str() ?: "",
            acRate = q["acRate"].double()
        )
    }

    private fun difficultyCounts(array: JsonArray?): DifficultyCounts {
        val byName = array.orEmpty().mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val name = o["difficulty"].str()?.lowercase() ?: return@mapNotNull null
            name to (o["count"].int() ?: 0)
        }.toMap()
        return DifficultyCounts(
            all = byName["all"] ?: 0,
            easy = byName["easy"] ?: 0,
            medium = byName["medium"] ?: 0,
            hard = byName["hard"] ?: 0
        )
    }

    private fun JsonElement?.str(): String? =
        (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonElement?.long(): Long? = str()?.toDoubleOrNull()?.toLong()

    private fun JsonElement?.int(): Int? = long()?.toInt()

    private fun JsonElement?.double(): Double? = str()?.toDoubleOrNull()
}
