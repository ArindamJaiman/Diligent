package dev.diligent.app.leetcode.sync

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.leetcode.data.FetchOutcome
import dev.diligent.app.leetcode.data.LeetCodeApi
import dev.diligent.app.leetcode.data.LeetCodePrefs
import dev.diligent.app.leetcode.data.LeetCodeSnapshot
import dev.diligent.app.leetcode.domain.LeetCodeDay
import dev.diligent.app.leetcode.notify.LeetCodeAlarms
import dev.diligent.app.leetcode.notify.LeetCodeNotifier
import dev.diligent.app.leetcode.widget.LeetCodeWidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

sealed interface LeetCodeSyncResult {
    data class Synced(val snapshot: LeetCodeSnapshot) : LeetCodeSyncResult
    data object NotConfigured : LeetCodeSyncResult
    data class Failed(val message: String, val cached: LeetCodeSnapshot?) : LeetCodeSyncResult
}

/**
 * The only place that talks to LeetCode. Called by the SYNC buttons, by opening the LeetCode
 * screen, and by reminder checks — never on a timer. Concurrent callers share one request,
 * and a fresh result (under [MIN_GAP_MS] old) is reused instead of fetching again.
 */
@Singleton
class LeetCodeSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: LeetCodePrefs,
    private val repository: DiligentRepository
) {
    private val mutex = Mutex()

    suspend fun sync(force: Boolean = false): LeetCodeSyncResult = mutex.withLock {
        val state = prefs.current()
        val username = state.config.username
        if (username.isBlank()) return@withLock LeetCodeSyncResult.NotConfigured

        val now = System.currentTimeMillis()
        val cached = state.snapshot?.takeIf { it.username.equals(username, ignoreCase = true) }
        if (!force && cached != null && now - cached.fetchedAt < MIN_GAP_MS) {
            return@withLock LeetCodeSyncResult.Synced(cached)
        }

        prefs.markSyncStarted(now)
        LeetCodeWidgetUpdater.refresh(context)

        val outcome = withContext(Dispatchers.IO) { LeetCodeApi.fetch(username) }
        val finished = System.currentTimeMillis()
        val result = when (outcome) {
            is FetchOutcome.Success -> {
                prefs.saveSnapshot(outcome.snapshot, finished)
                onFreshSnapshot(outcome.snapshot, finished)
                LeetCodeSyncResult.Synced(outcome.snapshot)
            }
            FetchOutcome.UserNotFound -> {
                val message = "No LeetCode user named \"$username\""
                prefs.markSyncFailed(finished, message)
                LeetCodeSyncResult.Failed(message, cached)
            }
            is FetchOutcome.Failed -> {
                prefs.markSyncFailed(finished, outcome.message)
                LeetCodeSyncResult.Failed(outcome.message, cached)
            }
        }
        LeetCodeWidgetUpdater.refresh(context)
        result
    }

    private suspend fun onFreshSnapshot(snapshot: LeetCodeSnapshot, now: Long) {
        val config = prefs.current().config
        LeetCodeAlarms.armContests(context, snapshot, config, now)
        if (LeetCodeDay.streakSafe(snapshot, now, config.guardDailyOnly)) {
            LeetCodeNotifier.cancelStreak(context)
        }
        if (config.autoLog) autoLog(snapshot, config.autoLogActivityId, now)
    }

    /**
     * Raises today's progress on the linked tracker activity to the number of problems
     * accepted today (local day). Never lowers it, so manual logging still adds on top.
     */
    private suspend fun autoLog(snapshot: LeetCodeSnapshot, activityId: Long, now: Long) {
        try {
            val solved = LeetCodeDay.solvedLocalToday(snapshot, now, ZoneId.systemDefault())
            if (solved <= 0) return
            val activity = resolveActivity(activityId) ?: return
            repository.raiseProgressTo(activity, solved.toFloat())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Auto-log failed", e)
        }
    }

    /** The chosen activity, or the first active one with "leetcode" in its name. */
    suspend fun resolveActivity(activityId: Long) = repository.getAllActiveSnapshot().let { active ->
        active.firstOrNull { it.id == activityId }
            ?: active.firstOrNull { it.name.contains("leetcode", ignoreCase = true) }
    }

    companion object {
        private const val TAG = "LeetCodeSync"
        const val MIN_GAP_MS = 30_000L
    }
}
