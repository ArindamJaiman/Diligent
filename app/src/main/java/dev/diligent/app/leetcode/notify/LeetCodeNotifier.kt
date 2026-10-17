package dev.diligent.app.leetcode.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.diligent.app.R
import dev.diligent.app.leetcode.data.Contest
import dev.diligent.app.leetcode.data.DailyQuestion
import dev.diligent.app.leetcode.domain.LeetCodeDay
import dev.diligent.app.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Posts and cancels every LeetCode notification. */
object LeetCodeNotifier {

    const val CHANNEL_REMINDERS = "leetcode_reminders"
    const val CHANNEL_CONTESTS = "leetcode_contests"

    private const val ID_DAILY = 3101
    private const val ID_STREAK = 3102
    private const val ID_CONTEST_BASE = 3110

    private const val PROBLEMSET_URL = "https://leetcode.com/problemset/"

    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "LeetCode reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Daily challenge and streak-at-risk alerts"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CONTESTS, "LeetCode contests", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Heads-up before weekly and biweekly contests start"
            }
        )
    }

    /** @param reviewsDue problems in the review queue due today, mentioned when there are any. */
    fun dailyChallenge(context: Context, daily: DailyQuestion, streak: Int, reviewsDue: Int = 0) {
        val details = listOfNotNull(
            daily.difficulty.uppercase().takeIf { it.isNotBlank() },
            daily.acRate?.let { "%.0f%% acceptance".format(it) },
            streak.takeIf { it > 0 }?.let { "$it-day streak" },
            reviewsDue.takeIf { it > 0 }?.let { if (it == 1) "1 review due" else "$it reviews due" }
        ).joinToString(" · ")
        val notification = base(context, CHANNEL_REMINDERS)
            .setContentTitle("LeetCode · Daily #${daily.frontendId}".trimEnd('#', ' '))
            .setContentText(daily.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${daily.title}\n$details"))
            .setSubText(details.takeIf { it.isNotBlank() })
            .setContentIntent(openUrl(context, daily.url, ID_DAILY))
            .build()
        post(context, ID_DAILY, notification)
    }

    /**
     * @param verified false when LeetCode couldn't be reached and the cached data is older
     * than today's reset, so "nothing yet" is a guess and the text says so.
     */
    fun streakAtRisk(
        context: Context,
        streak: Int,
        now: Long,
        dailyOnly: Boolean,
        daily: DailyQuestion?,
        verified: Boolean,
        lastSyncAt: Long
    ) {
        val reset = LeetCodeDay.nextResetMillis(now)
        val resetText = "resets ${formatClock(reset)} (${LeetCodeDay.formatLeft(reset - now)} left)"
        val title = if (streak > 0) "LeetCode · $streak-day streak at risk" else "LeetCode · nothing solved today"
        val what = if (dailyOnly) "Daily challenge not solved yet" else "No submission yet today"
        val body = buildString {
            append(what).append(" · ").append(resetText)
            if (!verified) {
                append("\nCouldn't reach LeetCode")
                if (lastSyncAt > 0) append(" — last synced ").append(formatClock(lastSyncAt))
            }
            if (daily != null) append("\nDaily: ").append(daily.title)
        }
        val solveUrl = daily?.url ?: PROBLEMSET_URL
        val notification = base(context, CHANNEL_REMINDERS)
            .setContentTitle(title)
            .setContentText("$what · $resetText")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openUrl(context, solveUrl, ID_STREAK))
            .addAction(0, if (daily != null) "SOLVE DAILY" else "OPEN LEETCODE", openUrl(context, solveUrl, ID_STREAK + 1))
            .addAction(0, "CHECK NOW", LeetCodeReceiver.broadcast(context, LeetCodeReceiver.ACTION_CHECK_NOW, ID_STREAK + 2))
            .addAction(0, "DONE TODAY", LeetCodeReceiver.broadcast(context, LeetCodeReceiver.ACTION_DONE_TODAY, ID_STREAK + 3))
            // Ends with the LeetCode day it is about.
            .setTimeoutAfter((reset - now).coerceAtLeast(60_000L))
            .build()
        post(context, ID_STREAK, notification)
    }

    /** Answer to CHECK NOW when today is covered; replaces the at-risk alert and fades away. */
    fun streakSafe(context: Context, streak: Int, solvedToday: Int) {
        val text = listOfNotNull(
            solvedToday.takeIf { it > 0 }?.let { "$it solved today" },
            streak.takeIf { it > 0 }?.let { "$it-day streak" }
        ).joinToString(" · ").ifBlank { "Submission found for today" }
        val notification = base(context, CHANNEL_REMINDERS)
            .setContentTitle("LeetCode · streak safe")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .setTimeoutAfter(5 * 60_000L)
            .build()
        post(context, ID_STREAK, notification)
    }

    fun contest(context: Context, contest: Contest, now: Long) {
        val startMillis = contest.startTime * 1000
        val minutes = ((startMillis - now) / 60_000).coerceAtLeast(0)
        val notification = base(context, CHANNEL_CONTESTS)
            .setContentTitle("LeetCode · ${contest.title}")
            .setContentText("Starts ${formatClock(startMillis)} (in $minutes min) · ${contest.durationSec / 60} min")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openUrl(context, contest.url, ID_CONTEST_BASE + contestSlot(contest)))
            .setTimeoutAfter((startMillis + contest.durationSec * 1000L - now).coerceAtLeast(60_000L))
            .build()
        post(context, ID_CONTEST_BASE + contestSlot(contest), notification)
    }

    fun cancelStreak(context: Context) = NotificationManagerCompat.from(context).cancel(ID_STREAK)

    fun cancelAll(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(ID_DAILY)
        manager.cancel(ID_STREAK)
        manager.cancel(ID_CONTEST_BASE)
        manager.cancel(ID_CONTEST_BASE + 1)
    }

    fun formatClock(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(clock)

    private fun contestSlot(contest: Contest) = if (contest.slug.startsWith("biweekly")) 1 else 0

    private fun base(context: Context, channel: String): NotificationCompat.Builder {
        ensureChannels(context)
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFFFA116.toInt())
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
    }

    private fun openUrl(context: Context, url: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            ID_STREAK + 9,
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse(MainActivity.ROUTE_URI_LEETCODE))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    @SuppressLint("MissingPermission") // Checked by areNotificationsEnabled(); Android 13+ also needs the runtime grant.
    private fun post(context: Context, id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted
        }
    }
}
