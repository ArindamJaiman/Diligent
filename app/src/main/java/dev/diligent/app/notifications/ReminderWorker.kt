package dev.diligent.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.diligent.app.R
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for sending daily reminder notifications.
 * Checks if goals are incomplete and sends appropriate notifications.
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: DiligentRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        createNotificationChannel()

        val type = inputData.getString(KEY_REMINDER_TYPE) ?: TYPE_DAILY
        val activityName = inputData.getString(KEY_ACTIVITY_NAME)

        when (type) {
            TYPE_DAILY -> sendDailyReminder()
            TYPE_ACTIVITY -> sendActivityReminder(activityName ?: "Activity")
            TYPE_MISSED_GOAL -> sendMissedGoalNotification()
        }

        return Result.success()
    }

    private suspend fun sendDailyReminder() {
        val activities = repository.getAllActiveSnapshot()
        val todayProgress = repository.getTodayProgressSnapshot()
        val completed = todayProgress.count { it.isCompleted }
        val total = activities.size

        if (total == 0) return
        if (completed == total) return // All done, no need to remind

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Diligent · Daily Check")
            .setContentText("$completed/$total activities completed today. Stay diligent.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$completed/$total activities completed today.\n${total - completed} remaining. Keep building consistency.")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setColor(0xFFFFFFFF.toInt())
            .build()

        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(NOTIFICATION_ID_DAILY, notification)
        } catch (_: SecurityException) {
            // Permission not granted
        }
    }

    private fun sendActivityReminder(activityName: String) {
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Diligent · Reminder")
            .setContentText("Time to work on: $activityName")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setColor(0xFFFFFFFF.toInt())
            .build()

        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(activityName.hashCode(), notification)
        } catch (_: SecurityException) { }
    }

    private suspend fun sendMissedGoalNotification() {
        val activities = repository.getAllActiveSnapshot()
        val todayProgress = repository.getTodayProgressSnapshot()
        val progressMap = todayProgress.associateBy { it.activityId }

        val missed = activities.filter { activity ->
            val progress = progressMap[activity.id]
            progress == null || !progress.isCompleted
        }

        if (missed.isEmpty()) return

        val missedNames = missed.take(3).joinToString(", ") { it.name }
        val extra = if (missed.size > 3) " +${missed.size - 3} more" else ""

        val intent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Diligent · Missed Goals")
            .setContentText("Incomplete: $missedNames$extra")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setColor(0xFFFFFFFF.toInt())
            .build()

        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(NOTIFICATION_ID_MISSED, notification)
        } catch (_: SecurityException) { }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Diligent Reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Daily activity reminders and missed goal alerts"
        }
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "diligent_reminders"
        const val NOTIFICATION_ID_DAILY = 1001
        const val NOTIFICATION_ID_MISSED = 1002
        const val KEY_REMINDER_TYPE = "reminder_type"
        const val KEY_ACTIVITY_NAME = "activity_name"
        const val TYPE_DAILY = "daily"
        const val TYPE_ACTIVITY = "activity"
        const val TYPE_MISSED_GOAL = "missed_goal"

        /**
         * Schedules the daily reminder as a periodic WorkManager task.
         */
        fun scheduleDailyReminder(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(
                1, TimeUnit.DAYS
            )
                .setInputData(
                    workDataOf(KEY_REMINDER_TYPE to TYPE_DAILY)
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .addTag("daily_reminder")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "diligent_daily_reminder",
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }

        /**
         * Schedules the missed goal check to run at end of day.
         */
        fun scheduleMissedGoalCheck(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(
                1, TimeUnit.DAYS
            )
                .setInputData(
                    workDataOf(KEY_REMINDER_TYPE to TYPE_MISSED_GOAL)
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .addTag("missed_goal_check")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "diligent_missed_goal",
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }

        /**
         * Cancels all scheduled reminders.
         */
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag("daily_reminder")
            WorkManager.getInstance(context).cancelAllWorkByTag("missed_goal_check")
        }
    }
}
