package dev.diligent.app.focus.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.diligent.app.focus.focusTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** STOP on the notification and the Pomodoro block alarm. Not exported: only our PendingIntents reach it. */
class FocusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val timer = focusTimer(context)
        val action = intent.action
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (action) {
                    ACTION_STOP -> timer.stop(announce = true)
                    ACTION_BOUNDARY -> timer.refresh()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_STOP = "dev.diligent.app.focus.STOP"
        const val ACTION_BOUNDARY = "dev.diligent.app.focus.BOUNDARY"

        private const val REQUEST_STOP = 5110

        fun broadcast(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                if (action == ACTION_STOP) REQUEST_STOP else FocusAlarms.REQUEST_BOUNDARY,
                Intent(context, FocusReceiver::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}

/**
 * One alarm at the end of the current Pomodoro phase, never a repeating one.
 *
 * Non-wakeup (RTC): on a sleeping phone it fires when the screen next turns on. Android may
 * stretch the 1-minute window (up to 10 minutes on Android 12+), which only delays the alert:
 * logged time follows the fixed 25/5 schedule, and the notification counts down to the exact end.
 */
object FocusAlarms {

    const val REQUEST_BOUNDARY = 5120
    private const val WINDOW_MS = 60_000L

    fun arm(context: Context, at: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.setWindow(AlarmManager.RTC, at, WINDOW_MS, FocusReceiver.broadcast(context, FocusReceiver.ACTION_BOUNDARY))
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(FocusReceiver.broadcast(context, FocusReceiver.ACTION_BOUNDARY))
    }
}
