package dev.diligent.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.updateAll
import dev.diligent.app.contests.ContestWidgetUpdater
import dev.diligent.app.focus.widget.FocusWidgetUpdater
import dev.diligent.app.leetcode.widget.LeetCodeWidgetUpdater
import dev.diligent.app.now.NowWidgetUpdater
import dev.diligent.app.papers.PaperWidgetUpdater
import dev.diligent.app.quotapulse.widget.QuotaPulseWidgetUpdater
import dev.diligent.app.screentime.ScreenTimeWidgetReceiver
import dev.diligent.app.tools.ToolWidgets
import dev.diligent.app.top3.Top3WidgetReceiver
import dev.diligent.app.versus.VersusWidgetUpdater
import dev.diligent.app.tracking.DayClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Event-driven widget refreshes. Widgets are redrawn only when their data actually
 * changes (or the day rolls over) — never on a timer loop.
 */
object WidgetUpdater {

    private const val TAG = "WidgetUpdater"

    /** @param except a widget a tap already redrew */
    suspend fun refreshTracker(context: Context, except: GlanceId? = null) = safely {
        DiligentWidget().updateAllExcept(context, except)
        // Top 3, Focus-vs-Scroll and Now show tracker progress too
        ToolWidgets.refreshLocal(context, except)
        NowWidgetUpdater.refresh(context, except)
    }

    /**
     * After a +/− on a widget: the tapped widget is redrawn at once, in this process, and
     * only then do the other widgets showing tracker progress update the normal way.
     */
    suspend fun afterProgressTap(context: Context, tapped: GlanceAppWidget, glanceId: GlanceId) {
        safely { InstantWidget.redraw(context, tapped, glanceId) }
        refreshTracker(context, except = glanceId)
    }

    suspend fun refreshGithub(context: Context) = safely {
        GithubWidget().updateAll(context)
        GithubVsWidget().updateAll(context)
    }

    suspend fun refreshAll(context: Context) {
        refreshTracker(context)
        refreshGithub(context)
    }

    /** Redraws every widget the app has, e.g. after a look change in Settings. */
    suspend fun refreshEverything(context: Context) {
        refreshAll(context)
        ContestWidgetUpdater.refresh(context)
        VersusWidgetUpdater.refresh(context)
        PaperWidgetUpdater.refresh(context)
        FocusWidgetUpdater.refresh(context)
        LeetCodeWidgetUpdater.refresh(context)
        QuotaPulseWidgetUpdater.refresh(context)
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
        GithubVsWidgetReceiver::class.java,
        Top3WidgetReceiver::class.java,
        ScreenTimeWidgetReceiver::class.java
    )

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        // The tracking day may start after midnight (Settings → Day starts at).
        val nextMidnight = DayClock.nextDayStart() + 5_000
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
