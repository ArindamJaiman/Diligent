package dev.diligent.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Reschedules reminders after device reboot.
 * Registered in AndroidManifest with BOOT_COMPLETED intent filter.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ReminderWorker.scheduleDailyReminder(context)
            ReminderWorker.scheduleMissedGoalCheck(context)
            GithubSyncWorker.schedulePeriodicSync(context)
        }
    }
}
