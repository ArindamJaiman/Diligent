package dev.diligent.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.diligent.app.R
import dev.diligent.app.automation.automation
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.ActivityReminder
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.focus.domain.FocusMath
import dev.diligent.app.goals.GoalCoach
import dev.diligent.app.planning.ReminderMath
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Per-activity reminders (set on the activity's edit screen) as self-chaining WorkManager
 * jobs, like the daily reminder. A reminder only notifies while the activity is still open
 * today by its schedule; with "nag until done" it comes back every few minutes (Due) until
 * the activity is done, skipped or the evening is over. The notification has buttons to log,
 * start the timer, mark done or snooze.
 */
object ActivityReminders {

    data class Nag(val enabled: Boolean = false, val minutes: Int = 30, val untilHour: Int = 23)

    private const val PREFS = "activity_reminders"
    private const val KEY_SCHEDULED = "scheduled"
    private const val WORK_PREFIX = "activity_reminder_"
    private const val NAG_PREFIX = "activity_nag_"
    const val CHANNEL = "activity_reminders"

    fun nag(context: Context): Nag {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Nag(p.getBoolean("nag", false), p.getInt("nag_minutes", 30), p.getInt("nag_until", 23))
    }

    fun setNag(context: Context, nag: Nag) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("nag", nag.enabled)
            .putInt("nag_minutes", nag.minutes.coerceIn(10, 120))
            .putInt("nag_until", nag.untilHour.coerceIn(12, 23))
            .apply()
    }

    /**
     * Makes queued jobs match the stored reminders. [replace] after an edit; false on app
     * start keeps what's queued.
     */
    suspend fun syncAll(context: Context, repository: DiligentRepository, replace: Boolean) {
        val workManager = WorkManager.getInstance(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val active = repository.getAllActiveSnapshot().associateBy { it.id }
        val wanted = repository.getAllRemindersSnapshot().filter { it.isEnabled && it.activityId in active }
        val before = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
        (before - wanted.map { it.id.toString() }.toSet()).forEach { workManager.cancelUniqueWork(WORK_PREFIX + it) }
        wanted.forEach { schedule(context, it, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP) }
        prefs.edit().putStringSet(KEY_SCHEDULED, wanted.map { it.id.toString() }.toSet()).apply()
    }

    fun schedule(context: Context, reminder: ActivityReminder, policy: ExistingWorkPolicy) {
        val now = ZonedDateTime.now()
        val next = ReminderMath.next(now, reminder.hour, reminder.minute, reminder.daysOfWeek) ?: return
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    ReminderWorker.KEY_REMINDER_TYPE to ReminderWorker.TYPE_ACTIVITY,
                    ReminderWorker.KEY_REMINDER_ID to reminder.id,
                    ReminderWorker.KEY_ACTIVITY_ID to reminder.activityId
                )
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_PREFIX + reminder.id, policy, request)
    }

    /** One more nudge for [activityId] in [minutes] (nag or snooze); replaces a pending one. */
    fun scheduleNag(context: Context, activityId: Long, attempt: Int, minutes: Int) {
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(minutes.toLong(), TimeUnit.MINUTES)
            .setInputData(
                workDataOf(
                    ReminderWorker.KEY_REMINDER_TYPE to ReminderWorker.TYPE_ACTIVITY,
                    ReminderWorker.KEY_ACTIVITY_ID to activityId,
                    ReminderWorker.KEY_ATTEMPT to attempt
                )
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAG_PREFIX + activityId, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelNag(context: Context, activityId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(NAG_PREFIX + activityId)
    }

    fun notificationId(activityId: Long) = 6000 + (activityId % 1000).toInt()

    /** The reminder itself, with buttons. */
    fun post(context: Context, activity: Activity, progress: Float, attempt: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Activity reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Reminders you set on an activity, with log, start and snooze buttons"
            }
        )
        val open = PendingIntent.getActivity(
            context, notificationId(activity.id), Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val step = GoalCoach.step(activity.unit).let { if (activity.unit == "hrs") 0.5f else if (activity.unit == "mins") 15f else it }
        val left = (activity.dailyGoal - progress).coerceAtLeast(0f)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (attempt > 0) "Still open · ${activity.name}" else "Time for ${activity.name}")
            .setContentText("${ToolFormat.amount(progress, activity.unit)} of ${ToolFormat.amount(activity.dailyGoal, activity.unit)} · ${ToolFormat.amount(left, activity.unit)} to go")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setColor(0xFFFFFFFF.toInt())
            .addAction(0, "+${ToolFormat.amount(step, activity.unit)}", action(context, ReminderActionReceiver.LOG, activity.id, step))
        if (FocusMath.isTimeUnit(activity.unit)) {
            builder.addAction(0, "START TIMER", action(context, ReminderActionReceiver.FOCUS, activity.id))
        } else {
            builder.addAction(0, "DONE", action(context, ReminderActionReceiver.DONE, activity.id))
        }
        builder.addAction(0, "SNOOZE 30M", action(context, ReminderActionReceiver.SNOOZE, activity.id))
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return
        try {
            compat.notify(notificationId(activity.id), builder.build())
        } catch (_: SecurityException) {
        }
    }

    /** Whether another nag fits before the evening cut-off. */
    fun nagFits(nag: Nag, now: LocalTime = LocalTime.now()): Boolean =
        nag.enabled && now.plusMinutes(nag.minutes.toLong()).let { it.isBefore(LocalTime.of(nag.untilHour, 0)) && it.isAfter(now) }

    private fun action(context: Context, verb: String, activityId: Long, amount: Float = 0f): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (activityId * 10 + ReminderActionReceiver.VERBS.indexOf(verb)).toInt(),
            Intent(context, ReminderActionReceiver::class.java)
                .setAction(verb)
                .putExtra(ReminderActionReceiver.EXTRA_ACTIVITY, activityId)
                .putExtra(ReminderActionReceiver.EXTRA_AMOUNT, amount),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

/** Buttons on activity reminders. Not exported: only our PendingIntents reach it. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val activityId = intent.getLongExtra(EXTRA_ACTIVITY, 0L)
        if (activityId == 0L) return
        val verb = intent.action ?: return
        NotificationManagerCompat.from(app).cancel(ActivityReminders.notificationId(activityId))
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val entry = automation(app)
                when (verb) {
                    LOG -> entry.repository().addProgress(activityId, intent.getFloatExtra(EXTRA_AMOUNT, 1f))
                    FOCUS -> entry.focusTimer().start(activityId, pomodoro = false)
                    DONE -> {
                        val repository = entry.repository()
                        if (repository.getProgressOn(activityId, repository.today())?.isCompleted != true) repository.toggleComplete(activityId)
                    }
                    SNOOZE -> ActivityReminders.scheduleNag(app, activityId, attempt = 1, minutes = 30)
                }
                // Anything but snooze answers the reminder: no more nagging today.
                if (verb != SNOOZE) ActivityReminders.cancelNag(app, activityId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val LOG = "dev.diligent.app.reminder.LOG"
        const val FOCUS = "dev.diligent.app.reminder.FOCUS"
        const val DONE = "dev.diligent.app.reminder.DONE"
        const val SNOOZE = "dev.diligent.app.reminder.SNOOZE"
        val VERBS = listOf(LOG, FOCUS, DONE, SNOOZE)
        const val EXTRA_ACTIVITY = "activity"
        const val EXTRA_AMOUNT = "amount"
    }
}
