package dev.diligent.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * Event-driven widget refreshes. Widgets are redrawn only when their data actually
 * changes (or the day rolls over) — never on a timer loop.
 */
object WidgetUpdater {

    private const val TAG = "WidgetUpdater"

    suspend fun refreshTracker(context: Context) = safely { DiligentWidget().updateAll(context) }

    suspend fun refreshGithub(context: Context) = safely {
        GithubWidget().updateAll(context)
        GithubVsWidget().updateAll(context)
    }

    suspend fun refreshAll(context: Context) {
        refreshTracker(context)
        refreshGithub(context)
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Widget refresh failed", e)
        }
    }
}

/**
 * Redraws every widget once the date changes so progress resets and contribution
 * grids shift without frequent periodic updates.
 *
 * Uses a non-wakeup RTC alarm: if the phone is asleep at midnight, the refresh is
 * delivered the next time the device wakes up anyway, costing no extra wakeups.
 */
object DayChangeRefresh {

    private const val REQUEST_CODE = 4201

    private val receivers = listOf(
        DiligentWidgetReceiver::class.java,
        GithubWidgetReceiver::class.java,
        GithubVsWidgetReceiver::class.java
    )

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val nextMidnight = LocalDate.now()
            .plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli() + 5_000
        alarmManager.set(AlarmManager.RTC, nextMidnight, pendingIntent(context))
    }

    /** Arms the alarm while at least one widget is placed; cancels it otherwise. */
    fun scheduleIfWidgetsExist(context: Context) {
        if (hasAnyWidget(context)) {
            schedule(context)
        } else {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
        }
    }

    fun hasAnyWidget(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        return receivers.any { receiver ->
            manager.getAppWidgetIds(ComponentName(context, receiver))?.isNotEmpty() == true
        }
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, DayChangeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

class DayChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                WidgetUpdater.refreshAll(appContext)
                DayChangeRefresh.scheduleIfWidgetsExist(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}
