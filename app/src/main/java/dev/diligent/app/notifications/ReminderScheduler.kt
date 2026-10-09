package dev.diligent.app.notifications

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.diligent.app.data.local.entity.Settings
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Schedules the daily reminder and the missed-goal check at real clock times.
 *
 * Each run is a one-time job delayed until the next occurrence; when it fires it
 * queues the following day's run. Unlike a 24h periodic job, this doesn't drift
 * later every day and it honours the user's on/off toggles and reminder time.
 */
object ReminderScheduler {

    /** Local time of the end-of-day missed goal check. */
    const val MISSED_GOAL_HOUR = 21
    const val MISSED_GOAL_MINUTE = 0

    private const val DAILY_WORK = "diligent_daily_reminder_v2"
    private const val MISSED_GOAL_WORK = "diligent_missed_goal_v2"

    /** 24h periodic jobs from earlier versions that fired at arbitrary times. */
    private val LEGACY_WORK = listOf("diligent_daily_reminder", "diligent_missed_goal")

    /**
     * Makes scheduled work match [settings].
     *
     * @param replaceExisting true after the user changes a reminder toggle or time,
     * so pending jobs are rescheduled; false on app start to keep existing jobs.
     */
    fun sync(context: Context, settings: Settings, replaceExisting: Boolean) {
        val workManager = WorkManager.getInstance(context)
        LEGACY_WORK.forEach { workManager.cancelUniqueWork(it) }

        val policy = if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP

        if (settings.dailyReminderEnabled) {
            enqueue(
                workManager, DAILY_WORK, ReminderWorker.TYPE_DAILY,
                settings.dailyReminderHour, settings.dailyReminderMinute, policy
            )
        } else {
            workManager.cancelUniqueWork(DAILY_WORK)
        }

        if (settings.missedGoalNotification) {
            enqueue(
                workManager, MISSED_GOAL_WORK, ReminderWorker.TYPE_MISSED_GOAL,
                MISSED_GOAL_HOUR, MISSED_GOAL_MINUTE, policy
            )
        } else {
            workManager.cancelUniqueWork(MISSED_GOAL_WORK)
        }
    }

    /**
     * Queues the next occurrence from inside a running reminder job. APPEND_OR_REPLACE
     * chains it behind the current job instead of cancelling it.
     */
    internal fun scheduleNext(context: Context, type: String, settings: Settings) {
        val workManager = WorkManager.getInstance(context)
        when (type) {
            ReminderWorker.TYPE_DAILY -> if (settings.dailyReminderEnabled) {
                enqueue(
                    workManager, DAILY_WORK, type,
                    settings.dailyReminderHour, settings.dailyReminderMinute,
                    ExistingWorkPolicy.APPEND_OR_REPLACE
                )
            }
            ReminderWorker.TYPE_MISSED_GOAL -> if (settings.missedGoalNotification) {
                enqueue(
                    workManager, MISSED_GOAL_WORK, type,
                    MISSED_GOAL_HOUR, MISSED_GOAL_MINUTE,
                    ExistingWorkPolicy.APPEND_OR_REPLACE
                )
            }
        }
    }

    fun cancelAll(context: Context) {
        val workManager = WorkManager.getInstance(context)
        (LEGACY_WORK + DAILY_WORK + MISSED_GOAL_WORK).forEach { workManager.cancelUniqueWork(it) }
    }

    /**
     * Milliseconds from [now] until the next [hour]:[minute] in the local time zone
     * (always in the future, DST-aware).
     */
    fun delayUntil(hour: Int, minute: Int, now: ZonedDateTime = ZonedDateTime.now()): Long {
        var next = now.withHour(hour.coerceIn(0, 23)).withMinute(minute.coerceIn(0, 59))
            .withSecond(0).withNano(0)
        if (!next.isAfter(now.plusSeconds(30))) {
            next = next.plusDays(1)
        }
        return Duration.between(now, next).toMillis()
    }

    private fun enqueue(
        workManager: WorkManager,
        uniqueName: String,
        type: String,
        hour: Int,
        minute: Int,
        policy: ExistingWorkPolicy
    ) {
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayUntil(hour, minute), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ReminderWorker.KEY_REMINDER_TYPE to type))
            .addTag(uniqueName)
            .build()
        workManager.enqueueUniqueWork(uniqueName, policy, request)
    }
}
