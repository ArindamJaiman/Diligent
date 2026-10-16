package dev.diligent.app.leetcode.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.diligent.app.leetcode.data.Contest
import dev.diligent.app.leetcode.data.LeetCodeConfig
import dev.diligent.app.leetcode.data.LeetCodeSnapshot

/**
 * Alarms that need no network when they fire.
 *
 * Contest heads-ups use setAndAllowWhileIdle so they still ring on a sleeping phone early
 * on a Sunday (WorkManager would wait for a Doze maintenance window). No exact-alarm
 * permission is needed; Android may shift them by a few minutes. Alarms don't survive a
 * reboot, so every sync and app start re-arms them from the cached contest list.
 */
object LeetCodeAlarms {

    private const val REQUEST_CONTEST_BASE = 3201
    private const val REQUEST_REDRAW = 3210
    private const val MAX_CONTESTS = 2

    fun armContests(context: Context, snapshot: LeetCodeSnapshot?, config: LeetCodeConfig, now: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        repeat(MAX_CONTESTS) { alarmManager.cancel(contestIntent(context, it, null)) }
        if (!config.configured || !config.contestAlert || snapshot == null) return

        snapshot.upcoming
            .filter { it.startTime * 1000 > now }
            .take(MAX_CONTESTS)
            .forEachIndexed { index, contest ->
                val at = contest.startTime * 1000 - config.contestLeadMin * 60_000L
                // Synced inside the lead window: too late for a heads-up, don't fire instantly.
                if (at > now + 5_000) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, contestIntent(context, index, contest))
                }
            }
    }

    fun cancelContests(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        repeat(MAX_CONTESTS) { alarmManager.cancel(contestIntent(context, it, null)) }
    }

    /** A non-wakeup redraw at the next moment the widget's content changes (no network). */
    fun scheduleRedraw(context: Context, at: Long?) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context,
            REQUEST_REDRAW,
            Intent(context, LeetCodeReceiver::class.java).setAction(LeetCodeReceiver.ACTION_REDRAW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (at == null) alarmManager.cancel(intent) else alarmManager.set(AlarmManager.RTC, at + 2_000, intent)
    }

    private fun contestIntent(context: Context, index: Int, contest: Contest?): PendingIntent {
        val intent = Intent(context, LeetCodeReceiver::class.java).setAction(LeetCodeReceiver.ACTION_CONTEST)
        if (contest != null) {
            intent.putExtra(LeetCodeReceiver.EXTRA_TITLE, contest.title)
                .putExtra(LeetCodeReceiver.EXTRA_SLUG, contest.slug)
                .putExtra(LeetCodeReceiver.EXTRA_START, contest.startTime)
                .putExtra(LeetCodeReceiver.EXTRA_DURATION, contest.durationSec)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CONTEST_BASE + index,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
