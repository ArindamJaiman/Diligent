package dev.diligent.app.leetcode.presentation

import dev.diligent.app.leetcode.data.Contest
import dev.diligent.app.leetcode.data.ContestStats
import dev.diligent.app.leetcode.data.DailyQuestion
import dev.diligent.app.leetcode.data.DifficultyCounts
import dev.diligent.app.leetcode.data.LeetCodeState
import dev.diligent.app.leetcode.data.RecentAc
import dev.diligent.app.leetcode.domain.LeetCodeDay

/** What we can honestly say about the current LeetCode (UTC) day. */
enum class TodayStatus(val label: String) {
    SAFE("SAFE TODAY"),
    AT_RISK("NOT YET TODAY"),
    /** The cached data predates today's reset, so it can't say anything about today. */
    UNKNOWN("SYNC TO CHECK")
}

data class LeetCodeView(
    val configured: Boolean,
    val username: String,
    val hasData: Boolean,
    val streak: Int,
    val today: TodayStatus,
    val solvedToday: Int,
    val solved: DifficultyCounts,
    val totals: DifficultyCounts,
    val ranking: Int?,
    val contest: ContestStats?,
    val daily: DailyQuestion?,
    val dailySolved: Boolean,
    val nextContest: Contest?,
    val recent: List<RecentAc>,
    val calendar: Map<String, Int>,
    val resetAt: Long,
    val fetchedAt: Long,
    val error: String?,
    val syncing: Boolean,
    val dailyOnly: Boolean
)

object LeetCodePresenter {

    fun build(state: LeetCodeState, now: Long): LeetCodeView {
        val config = state.config
        val snapshot = state.snapshot?.takeIf { it.username.equals(config.username, ignoreCase = true) }
        val today = LeetCodeDay.utcDay(now)
        val fresh = snapshot != null && LeetCodeDay.utcDay(snapshot.fetchedAt) == today

        val status = when {
            snapshot == null || !fresh -> TodayStatus.UNKNOWN
            LeetCodeDay.streakSafe(snapshot, now, config.guardDailyOnly) -> TodayStatus.SAFE
            else -> TodayStatus.AT_RISK
        }
        // Yesterday's daily challenge is not today's; hide it until the next sync.
        val daily = snapshot?.daily?.takeIf { it.date.isBlank() || it.date == today.toString() }

        return LeetCodeView(
            configured = config.configured,
            username = snapshot?.username?.ifBlank { null } ?: config.username,
            hasData = snapshot != null,
            streak = snapshot?.let { LeetCodeDay.currentStreak(it, now) } ?: 0,
            today = status,
            solvedToday = snapshot?.let { LeetCodeDay.solvedToday(it, now) } ?: 0,
            solved = snapshot?.solved ?: DifficultyCounts(),
            totals = snapshot?.totals ?: DifficultyCounts(),
            ranking = snapshot?.ranking,
            contest = snapshot?.contest,
            daily = daily,
            dailySolved = snapshot != null && daily != null && LeetCodeDay.dailySolved(snapshot, now),
            nextContest = snapshot?.upcoming?.firstOrNull { (it.startTime + it.durationSec) * 1000L > now },
            recent = snapshot?.recentAc.orEmpty(),
            calendar = snapshot?.calendar.orEmpty(),
            resetAt = LeetCodeDay.nextResetMillis(now),
            fetchedAt = snapshot?.fetchedAt ?: 0L,
            error = state.sync.lastError,
            syncing = state.sync.isSyncing(now),
            dailyOnly = config.guardDailyOnly
        )
    }

    /** Submission count → 0..4 heatmap level, like LeetCode's own calendar shading. */
    fun level(count: Int): Int = when {
        count <= 0 -> 0
        count == 1 -> 1
        count <= 3 -> 2
        count <= 6 -> 3
        else -> 4
    }

    /** The next time the widget's content changes on its own: the UTC reset or a contest edge. */
    fun nextBoundary(view: LeetCodeView, now: Long): Long {
        val contestEdges = listOfNotNull(view.nextContest).flatMap {
            listOf(it.startTime * 1000L, (it.startTime + it.durationSec) * 1000L)
        }
        return (contestEdges + view.resetAt).filter { it > now }.minOrNull() ?: view.resetAt
    }
}
