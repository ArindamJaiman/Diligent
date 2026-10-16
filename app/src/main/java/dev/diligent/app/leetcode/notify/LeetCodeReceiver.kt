package dev.diligent.app.leetcode.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import dev.diligent.app.di.WidgetEntryPoint
import dev.diligent.app.leetcode.data.Contest
import dev.diligent.app.leetcode.domain.LeetCodeDay
import dev.diligent.app.leetcode.widget.LeetCodeWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Contest heads-up alarms, notification buttons (CHECK NOW, DONE TODAY) and the widget's
 * reset-time redraw. Not exported: only this app's PendingIntents can reach it.
 */
class LeetCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            ACTION_CONTEST -> {
                val contest = Contest(
                    title = intent.getStringExtra(EXTRA_TITLE) ?: return,
                    slug = intent.getStringExtra(EXTRA_SLUG) ?: return,
                    startTime = intent.getLongExtra(EXTRA_START, 0L),
                    durationSec = intent.getIntExtra(EXTRA_DURATION, 5400)
                )
                val prefs = entryPoint(appContext).leetCodePrefs()
                val config = prefs.current().config
                if (!config.configured || !config.contestAlert) return
                LeetCodeNotifier.contest(appContext, contest, System.currentTimeMillis())
                LeetCodeReminders.followUpAfterContest(appContext, contest.startTime * 1000)
            }
            ACTION_CHECK_NOW -> LeetCodeReminders.checkNow(appContext)
            ACTION_DONE_TODAY -> {
                val prefs = entryPoint(appContext).leetCodePrefs()
                prefs.muteGuard(LeetCodeDay.utcDay(System.currentTimeMillis()))
                LeetCodeNotifier.cancelStreak(appContext)
                LeetCodeReminders.rescheduleStreak(appContext, prefs.current())
            }
            ACTION_REDRAW -> {
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                    try {
                        LeetCodeWidgetUpdater.refresh(appContext)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private fun entryPoint(context: Context) =
        EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)

    companion object {
        const val ACTION_CONTEST = "dev.diligent.app.leetcode.CONTEST"
        const val ACTION_CHECK_NOW = "dev.diligent.app.leetcode.CHECK_NOW"
        const val ACTION_DONE_TODAY = "dev.diligent.app.leetcode.DONE_TODAY"
        const val ACTION_REDRAW = "dev.diligent.app.leetcode.REDRAW"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SLUG = "slug"
        const val EXTRA_START = "start"
        const val EXTRA_DURATION = "duration"

        fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, LeetCodeReceiver::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
