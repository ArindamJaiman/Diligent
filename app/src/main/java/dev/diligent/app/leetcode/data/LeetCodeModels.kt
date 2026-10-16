package dev.diligent.app.leetcode.data

import kotlinx.serialization.Serializable

/**
 * One fetch of a public LeetCode profile plus the global daily challenge and contest schedule.
 * Cached as JSON so the widget and reminders can work from it without a network call.
 *
 * Times are epoch seconds as LeetCode reports them; [fetchedAt] is epoch millis.
 * [calendar] maps a UTC date ("2026-10-17") to that day's submission count. LeetCode's
 * day — streaks, the calendar and the daily challenge — runs on UTC.
 */
@Serializable
data class LeetCodeSnapshot(
    val username: String,
    val fetchedAt: Long,
    val ranking: Int? = null,
    val solved: DifficultyCounts = DifficultyCounts(),
    val totals: DifficultyCounts = DifficultyCounts(),
    val calendar: Map<String, Int> = emptyMap(),
    val recentAc: List<RecentAc> = emptyList(),
    val daily: DailyQuestion? = null,
    val contest: ContestStats? = null,
    val upcoming: List<Contest> = emptyList()
)

@Serializable
data class DifficultyCounts(val all: Int = 0, val easy: Int = 0, val medium: Int = 0, val hard: Int = 0)

/** An accepted submission from the public "recent AC" list (at most the last 20). */
@Serializable
data class RecentAc(val title: String, val slug: String, val timestamp: Long)

@Serializable
data class DailyQuestion(
    val date: String,
    val title: String,
    val slug: String,
    val difficulty: String,
    val frontendId: String,
    val acRate: Double? = null
) {
    val url: String get() = LeetCodeLinks.problem(slug)
}

@Serializable
data class ContestStats(
    val rating: Double,
    val attended: Int,
    val globalRanking: Int? = null,
    val topPercentage: Double? = null
)

@Serializable
data class Contest(val title: String, val slug: String, val startTime: Long, val durationSec: Int) {
    val url: String get() = LeetCodeLinks.contest(slug)
}

object LeetCodeLinks {
    fun problem(slug: String) = "https://leetcode.com/problems/$slug/"
    fun contest(slug: String) = "https://leetcode.com/contest/$slug/"
    fun profile(username: String) = "https://leetcode.com/u/$username/"
}

/** User choices for tracking and reminders. Reminders only run once a username is set. */
data class LeetCodeConfig(
    val username: String = "",
    val dailyAlert: Boolean = true,
    val dailyHour: Int = 9,
    val dailyMinute: Int = 0,
    val streakGuard: Boolean = true,
    val guardHour: Int = 21,
    val guardMinute: Int = 0,
    val repeatGuard: Boolean = true,
    val guardDailyOnly: Boolean = false,
    val contestAlert: Boolean = true,
    val contestLeadMin: Int = 30,
    val autoLog: Boolean = true,
    val autoLogActivityId: Long = 0L
) {
    val configured: Boolean get() = username.isNotBlank()

    companion object {
        val CONTEST_LEAD_CHOICES = listOf(10, 30, 60)
    }
}

/** Last sync attempt, kept separate from the cached snapshot so a failure never erases data. */
data class LeetCodeSyncStatus(
    val syncStartedAt: Long = 0L,
    val lastAttemptAt: Long = 0L,
    val lastError: String? = null
) {
    /** Bounded, so a killed process can't leave the UI stuck on "syncing". */
    fun isSyncing(now: Long): Boolean = syncStartedAt > lastAttemptAt && now - syncStartedAt < 60_000L
}
