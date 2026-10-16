package dev.diligent.app.leetcode.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.diligent.app.R
import dev.diligent.app.leetcode.data.LeetCodePrefs
import dev.diligent.app.leetcode.data.LeetCodeSnapshot
import dev.diligent.app.leetcode.data.LeetCodeState
import dev.diligent.app.leetcode.domain.LeetCodeDay
import dev.diligent.app.leetcode.domain.StreakSchedule
import dev.diligent.app.leetcode.sync.LeetCodeSync
import dev.diligent.app.leetcode.sync.LeetCodeSyncResult
import dev.diligent.app.notifications.ReminderScheduler
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Schedules the two clock-time LeetCode checks as self-chaining one-time jobs (same
 * approach as [ReminderScheduler]): they survive reboots and each run queues the next.
 *
 * - DAILY: at the chosen time, fetch once and announce today's daily challenge unless solved.
 * - STREAK: at the chosen time (and, with repeats, a few more times until the UTC reset),
 *   fetch once and warn if nothing was submitted this LeetCode day.
 *
 * Nothing polls in between; each check is a single small request.
 */
object LeetCodeReminders {

    private const val WORK_DAILY = "leetcode_daily"
    private const val WORK_STREAK = "leetcode_streak"
    private const val WORK_CHECK = "leetcode_check_now"
    private const val WORK_SYNC = "leetcode_sync_now"
    private const val WORK_FOLLOW_UP = "leetcode_contest_follow_up"

    /** Makes scheduled work match the settings. replace=false on app start keeps queued jobs. */
    fun sync(context: Context, state: LeetCodeState, replace: Boolean) {
        val workManager = WorkManager.getInstance(context)
        val config = state.config
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP

        if (config.configured && config.dailyAlert) {
            enqueue(workManager, WORK_DAILY, LeetCodeWorker.TYPE_DAILY, dailyDelay(state), policy)
        } else {
            workManager.cancelUniqueWork(WORK_DAILY)
        }

        if (config.configured && config.streakGuard) {
            enqueue(workManager, WORK_STREAK, LeetCodeWorker.TYPE_STREAK, streakDelay(state, System.currentTimeMillis()), policy)
        } else {
            workManager.cancelUniqueWork(WORK_STREAK)
        }

        if (!config.configured) {
            workManager.cancelUniqueWork(WORK_FOLLOW_UP)
            LeetCodeAlarms.cancelContests(context)
        }
    }

    /** Queues the next run from inside a running job (appended, so it doesn't cancel itself). */
    internal fun scheduleNext(context: Context, type: String, state: LeetCodeState) {
        val config = state.config
        if (!config.configured) return
        val workManager = WorkManager.getInstance(context)
        when (type) {
            LeetCodeWorker.TYPE_DAILY -> if (config.dailyAlert) {
                enqueue(workManager, WORK_DAILY, type, dailyDelay(state), ExistingWorkPolicy.APPEND_OR_REPLACE)
            }
            LeetCodeWorker.TYPE_STREAK -> if (config.streakGuard) {
                enqueue(
                    workManager, WORK_STREAK, type, streakDelay(state, System.currentTimeMillis()),
                    ExistingWorkPolicy.APPEND_OR_REPLACE
                )
            }
        }
    }

    /** Re-plans the streak guard after "done for today" (outside a running job). */
    fun rescheduleStreak(context: Context, state: LeetCodeState) {
        val config = state.config
        if (!config.configured || !config.streakGuard) return
        enqueue(
            WorkManager.getInstance(context), WORK_STREAK, LeetCodeWorker.TYPE_STREAK,
            streakDelay(state, System.currentTimeMillis()), ExistingWorkPolicy.REPLACE
        )
    }

    /** CHECK NOW from the streak notification: one expedited fetch, coalesced. */
    fun checkNow(context: Context) = expedited(context, WORK_CHECK, LeetCodeWorker.TYPE_CHECK)

    /** SYNC from the widget: one expedited fetch, coalesced. */
    fun syncNow(context: Context) = expedited(context, WORK_SYNC, LeetCodeWorker.TYPE_SYNC)

    private fun expedited(context: Context, name: String, type: String) {
        val request = OneTimeWorkRequestBuilder<LeetCodeWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(LeetCodeWorker.KEY_TYPE to type))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    }

    /** After a contest heads-up: refresh once the contest has started, to learn the next one. */
    fun followUpAfterContest(context: Context, startMillis: Long) {
        val delay = (startMillis + 10 * 60_000L - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<LeetCodeWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(LeetCodeWorker.KEY_TYPE to LeetCodeWorker.TYPE_SYNC))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_FOLLOW_UP, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll(context: Context) {
        val workManager = WorkManager.getInstance(context)
        listOf(WORK_DAILY, WORK_STREAK, WORK_CHECK, WORK_SYNC, WORK_FOLLOW_UP).forEach { workManager.cancelUniqueWork(it) }
        LeetCodeAlarms.cancelContests(context)
    }

    private fun dailyDelay(state: LeetCodeState): Long =
        ReminderScheduler.delayUntil(state.config.dailyHour, state.config.dailyMinute)

    internal fun streakDelay(state: LeetCodeState, now: Long): Long {
        val instant = Instant.ofEpochMilli(now)
        val next = StreakSchedule.next(
            now = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault()),
            guard = LocalTime.of(state.config.guardHour, state.config.guardMinute),
            repeat = state.config.repeatGuard,
            doneThrough = doneThrough(state, now)
        )
        return Duration.between(instant, next.toInstant()).toMillis().coerceAtLeast(0L)
    }

    /** The current UTC day counts as done when muted or when cached data already shows it safe. */
    internal fun doneThrough(state: LeetCodeState, now: Long): LocalDate? {
        val today = LeetCodeDay.utcDay(now)
        val snapshot = state.snapshot
        val safe = snapshot != null && isFromToday(snapshot, now) &&
            LeetCodeDay.streakSafe(snapshot, now, state.config.guardDailyOnly)
        return if (safe || state.mutedUtcDay == today) today else null
    }

    internal fun isFromToday(snapshot: LeetCodeSnapshot, now: Long): Boolean =
        LeetCodeDay.utcDay(snapshot.fetchedAt) == LeetCodeDay.utcDay(now)

    private fun enqueue(workManager: WorkManager, name: String, type: String, delayMs: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<LeetCodeWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(LeetCodeWorker.KEY_TYPE to type))
            .addTag(name)
            .build()
        workManager.enqueueUniqueWork(name, policy, request)
    }
}

@HiltWorker
class LeetCodeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sync: LeetCodeSync,
    private val prefs: LeetCodePrefs
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val type = inputData.getString(KEY_TYPE) ?: TYPE_SYNC
        try {
            when (type) {
                TYPE_DAILY -> daily()
                TYPE_STREAK -> streak(fromUser = false)
                TYPE_CHECK -> streak(fromUser = true)
                TYPE_SYNC -> sync.sync()
            }
        } finally {
            // Queue the next occurrence even if this run failed.
            if (type == TYPE_DAILY || type == TYPE_STREAK) {
                LeetCodeReminders.scheduleNext(applicationContext, type, prefs.current())
            }
        }
        return Result.success()
    }

    private suspend fun daily() {
        val config = prefs.current().config
        if (!config.configured || !config.dailyAlert) return
        val result = sync.sync()
        val now = System.currentTimeMillis()
        val snapshot = result.snapshotOrCached() ?: return
        val daily = snapshot.daily ?: return
        // A cached question from before today's reset isn't today's challenge.
        if (daily.date.isNotBlank() && daily.date != LeetCodeDay.utcDay(now).toString()) return
        if (LeetCodeDay.dailySolved(snapshot, now)) return
        LeetCodeNotifier.dailyChallenge(applicationContext, daily, LeetCodeDay.currentStreak(snapshot, now))
    }

    private suspend fun streak(fromUser: Boolean) {
        val before = prefs.current()
        if (!before.config.configured) return
        if (!fromUser && !before.config.streakGuard) return

        val result = sync.sync(force = fromUser)
        val now = System.currentTimeMillis()
        val state = prefs.current()
        val today = LeetCodeDay.utcDay(now)
        if (!fromUser && state.mutedUtcDay == today) return

        val snapshot = result.snapshotOrCached()
        val verified = snapshot != null && LeetCodeReminders.isFromToday(snapshot, now)
        val safe = verified && LeetCodeDay.streakSafe(snapshot!!, now, state.config.guardDailyOnly)
        val streak = snapshot?.let { LeetCodeDay.currentStreak(it, now) } ?: 0

        if (safe) {
            if (fromUser) {
                LeetCodeNotifier.streakSafe(applicationContext, streak, LeetCodeDay.solvedToday(snapshot!!, now))
            } else {
                LeetCodeNotifier.cancelStreak(applicationContext)
            }
        } else {
            LeetCodeNotifier.streakAtRisk(
                context = applicationContext,
                streak = streak,
                now = now,
                dailyOnly = state.config.guardDailyOnly,
                daily = snapshot?.daily?.takeIf { it.date.isBlank() || it.date == today.toString() },
                verified = verified,
                lastSyncAt = snapshot?.fetchedAt ?: 0L
            )
        }
    }

    private fun LeetCodeSyncResult.snapshotOrCached(): LeetCodeSnapshot? = when (this) {
        is LeetCodeSyncResult.Synced -> snapshot
        is LeetCodeSyncResult.Failed -> cached
        LeetCodeSyncResult.NotConfigured -> null
    }

    /** Only used on Android 11 and older, where expedited work runs as a short foreground job. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SYNC, "LeetCode sync", NotificationManager.IMPORTANCE_MIN)
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("LeetCode · checking")
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID_SYNC, notification)
    }

    companion object {
        const val KEY_TYPE = "leetcode_type"
        const val TYPE_DAILY = "daily"
        const val TYPE_STREAK = "streak"
        const val TYPE_CHECK = "check"
        const val TYPE_SYNC = "sync"
        private const val CHANNEL_SYNC = "leetcode_sync"
        private const val NOTIFICATION_ID_SYNC = 3105
    }
}
