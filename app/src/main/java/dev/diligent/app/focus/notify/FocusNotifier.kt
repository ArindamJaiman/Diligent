package dev.diligent.app.focus.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.diligent.app.R
import dev.diligent.app.focus.data.FocusSession
import dev.diligent.app.focus.domain.FocusMath
import dev.diligent.app.focus.domain.Phase
import dev.diligent.app.focus.domain.PomodoroPosition
import dev.diligent.app.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The ongoing timer notification and the Pomodoro phase alerts.
 *
 * The running notification uses the system chronometer (counting up, or down to the end of
 * the Pomodoro phase), which System UI ticks by itself — it is posted once per state change,
 * never refreshed on a timer.
 */
object FocusNotifier {

    const val CHANNEL_TIMER = "focus_timer"
    const val CHANNEL_POMODORO = "focus_pomodoro"

    private const val ID_TIMER = 5101
    private const val ID_PHASE = 5102
    private const val REQUEST_OPEN = 5111

    private const val ACCENT = 0xFF4ADE80.toInt()
    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TIMER, "Focus timer", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The running focus timer, with a STOP button"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_POMODORO, "Pomodoro", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alert at the end of each 25-minute focus block and 5-minute break"
            }
        )
    }

    fun running(context: Context, session: FocusSession, now: Long) {
        val locked = session.lockedUntil > now
        val builder = base(context, CHANNEL_TIMER)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setContentIntent(openApp(context))
        if (!locked) builder.addAction(0, "STOP", FocusReceiver.broadcast(context, FocusReceiver.ACTION_STOP))
        builder.addAction(0, "INTERRUPTED", FocusReceiver.broadcast(context, FocusReceiver.ACTION_INTERRUPT))

        val (title, text) = lines(session, now)
        builder.setContentTitle(title).setContentText(text)
        if (session.pomodoro) {
            builder.setWhen(FocusMath.pomodoroAt(session.startedAt, now).phaseEnd).setChronometerCountDown(true)
        } else {
            builder.setWhen(session.startedAt)
        }

        // Android 16+: a Live Update (status-bar chip, prominent on the lock screen).
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            LiveFocus.build(context, session, now, title, text, locked) ?: builder.build()
        } else {
            builder.build()
        }
        post(context, ID_TIMER, notification)
    }

    /** Title and text for the running notification (shared by the classic and Live Update forms). */
    internal fun lines(session: FocusSession, now: Long): Pair<String, String> {
        val locked = session.lockedUntil > now
        val extras = listOfNotNull(
            session.intention.takeIf { it.isNotBlank() }?.let { "“$it”" },
            if (locked) "hard mode · locked until ${formatClock(session.lockedUntil)}" else null,
            if (session.broken) "✕ stake broken" else null,
            session.interruptions.takeIf { it > 0 }?.let { "⚡ $it" }
        )
        return if (session.pomodoro) {
            val position = FocusMath.pomodoroAt(session.startedAt, now)
            if (position.phase == Phase.FOCUS) {
                "Focus ${position.block} · ${session.activityName}" to
                    (listOf("break at ${formatClock(position.phaseEnd)}") + extras).joinToString(" · ")
            } else {
                "Break · ${session.activityName}" to
                    (listOf("block ${position.block} done · back at ${formatClock(position.phaseEnd)}") + extras).joinToString(" · ")
            }
        } else {
            "Focus · ${session.activityName}" to
                (listOf("since ${formatClock(session.startedAt)}") + extras + if (locked) emptyList() else listOf("STOP logs the time"))
                    .joinToString(" · ")
        }
    }

    /** STOP was refused by hard mode. */
    fun locked(context: Context, session: FocusSession) {
        val notification = base(context, CHANNEL_POMODORO)
            .setContentTitle("Hard mode · keep going")
            .setContentText("${session.activityName} is locked until ${formatClock(session.lockedUntil)}")
            .setAutoCancel(true)
            .setTimeoutAfter(10_000L)
            .setContentIntent(openApp(context))
            .build()
        post(context, ID_PHASE, notification)
    }

    /** Pomodoro boundary. Fades out when the phase it announces is over. */
    fun phaseAlert(context: Context, activityName: String, position: PomodoroPosition, now: Long) {
        val (title, text) = if (position.phase == Phase.BREAK) {
            "Break · 5 min" to "Block ${position.block} logged to $activityName · back at ${formatClock(position.phaseEnd)}"
        } else {
            "Focus · block ${position.block}" to "$activityName · break at ${formatClock(position.phaseEnd)}"
        }
        val notification = base(context, CHANNEL_POMODORO)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .setTimeoutAfter((position.phaseEnd - now).coerceAtLeast(60_000L))
            .build()
        post(context, ID_PHASE, notification)
    }

    /**
     * Confirms a stop made from the notification, widget or tile.
     * @param minutes null when the activity was deleted while the timer ran.
     */
    fun stopped(context: Context, activityName: String, minutes: Int?) {
        val text = when {
            minutes == null -> "$activityName was deleted · nothing logged"
            minutes == 0 -> "Under 30 seconds · nothing logged"
            else -> "Logged ${FocusMath.formatMinutes(minutes)} to $activityName"
        }
        val notification = base(context, CHANNEL_TIMER)
            .setContentTitle("Focus stopped")
            .setContentText(text)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .setTimeoutAfter(15_000L)
            .build()
        post(context, ID_TIMER, notification)
    }

    fun cancel(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(ID_TIMER)
        manager.cancel(ID_PHASE)
    }

    fun cancelPhaseAlert(context: Context) = NotificationManagerCompat.from(context).cancel(ID_PHASE)

    fun formatClock(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(clock)

    private fun base(context: Context, channel: String): NotificationCompat.Builder {
        ensureChannels(context)
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_focus_timer)
            .setColor(ACCENT)
    }

    /** Brings the app forward as it was, like tapping its launcher icon. */
    internal fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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
