package dev.diligent.app.data.repository

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.regex.Pattern

object GithubContributionFetcher {

    private const val TAG = "GithubContributionFetcher"
    private const val BASE_URL = "https://github.com/users/%s/contributions"

    // Regex to match a day element (either td or rect) containing ContributionCalendar-day
    private val DAY_ELEMENT_PATTERN = Pattern.compile("<(rect|td)[^>]*class=\"[^\"]*ContributionCalendar-day[^\"]*\"[^>]*>")
    private val DATE_PATTERN = Pattern.compile("data-date=\"([^\"]+)\"")
    private val LEVEL_PATTERN = Pattern.compile("data-level=\"([^\"]+)\"")

    data class ParsedContribution(
        val date: String,
        val level: Int
    )

    data class FetchResult(
        val contributions: List<ParsedContribution>,
        val totalAnnual: Int,
        val currentStreak: Int
    )

    fun fetchContributions(username: String): FetchResult? {
        if (username.isBlank()) return null
        val urlString = String.format(BASE_URL, username)
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

            if (connection.responseCode != 200) {
                Log.w(TAG, "Failed to fetch contributions for $username: ${connection.responseCode}")
                return null
            }

            val html = connection.inputStream.bufferedReader().use { it.readText() }
            return parseHtml(html)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching contributions for $username", e)
            return null
        } finally {
            connection?.disconnect()
        }
    }

    fun parseHtml(html: String): FetchResult {
        val contributions = mutableListOf<ParsedContribution>()
        val matcher = DAY_ELEMENT_PATTERN.matcher(html)

        while (matcher.find()) {
            val element = matcher.group()
            val dateMatcher = DATE_PATTERN.matcher(element)
            val levelMatcher = LEVEL_PATTERN.matcher(element)

            if (dateMatcher.find() && levelMatcher.find()) {
                val date = dateMatcher.group(1)
                val levelStr = levelMatcher.group(1)
                if (date != null && levelStr != null) {
                    val level = levelStr.toIntOrNull() ?: 0
                    contributions.add(ParsedContribution(date, level))
                }
            }
        }

        // Sort chronologically by date
        contributions.sortBy { it.date }

        val totalAnnual = contributions.count { it.level > 0 }
        val currentStreak = calculateStreak(contributions)

        return FetchResult(contributions, totalAnnual, currentStreak)
    }

    private fun calculateStreak(contributions: List<ParsedContribution>): Int {
        if (contributions.isEmpty()) return 0

        val activeDates = contributions
            .filter { it.level > 0 }
            .mapNotNull {
                try {
                    LocalDate.parse(it.date)
                } catch (e: Exception) {
                    null
                }
            }
            .toSet()

        if (activeDates.isEmpty()) return 0

        var streak = 0
        var checkDate = LocalDate.now()

        // If not active today, check if active yesterday (meaning the streak is still alive)
        if (!activeDates.contains(checkDate)) {
            checkDate = checkDate.minusDays(1)
        }

        while (activeDates.contains(checkDate)) {
            streak++
            checkDate = checkDate.minusDays(1)
        }

        return streak
    }
}
