package dev.diligent.app.focus.notify

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import dev.diligent.app.R
import dev.diligent.app.focus.data.FocusSession
import dev.diligent.app.focus.domain.FocusMath
import dev.diligent.app.focus.domain.Phase

/**
 * The running timer as an Android 16 Live Update: a progress-centric notification that shows
 * as a chip in the status bar and stands out on the lock screen (like iOS Live Activities).
 *
 * Progress is the Pomodoro phase, or the session's planned length; an open-ended timer shows
 * an hour per lap. Posted once per state change, like the classic notification.
 */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
object LiveFocus {

    private const val GREEN = 0xFF4ADE80.toInt()
    private const val GRAY = 0xFF5A5A5A.toInt()

    fun build(context: Context, session: FocusSession, now: Long, title: String, text: String, locked: Boolean): Notification? =
        runCatching {
            val (start, end) = span(session, now)
            val length = ((end - start) / 1000).toInt().coerceAtLeast(1)
            val done = ((now - start) / 1000).toInt().coerceIn(0, length)
            val pomodoroBreak = session.pomodoro && FocusMath.pomodoroAt(session.startedAt, now).phase == Phase.BREAK

            val style = Notification.ProgressStyle()
                .setStyledByProgress(true)
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(length).setColor(if (pomodoroBreak) GRAY else GREEN)))
                .setProgress(done)
                .setProgressTrackerIcon(Icon.createWithResource(context, R.drawable.ic_focus_timer))

            val builder = Notification.Builder(context, FocusNotifier.CHANNEL_TIMER)
                .setSmallIcon(R.drawable.ic_focus_timer)
                .setColor(GREEN)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(style)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STOPWATCH)
                .setUsesChronometer(true)
                .setShowWhen(true)
                .setWhen(if (session.pomodoro || session.targetMinutes > 0) end else session.startedAt)
                .setChronometerCountDown(session.pomodoro || session.targetMinutes > 0)
                .setShortCriticalText(chip(session, now))
                .setContentIntent(FocusNotifier.openApp(context))
                .addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            if (!locked) {
                builder.addAction(
                    Notification.Action.Builder(null, "STOP", FocusReceiver.broadcast(context, FocusReceiver.ACTION_STOP)).build()
                )
            }
            builder.addAction(
                Notification.Action.Builder(null, "INTERRUPTED", FocusReceiver.broadcast(context, FocusReceiver.ACTION_INTERRUPT)).build()
            )
            builder.build()
        }.getOrNull()

    /** The stretch the progress bar covers. */
    private fun span(session: FocusSession, now: Long): Pair<Long, Long> = when {
        session.pomodoro -> FocusMath.pomodoroAt(session.startedAt, now).let { it.phaseStart to it.phaseEnd }
        session.targetMinutes > 0 -> session.startedAt to session.startedAt + session.targetMinutes * FocusMath.MINUTE
        else -> {
            val lap = 60 * FocusMath.MINUTE
            val start = session.startedAt + (now - session.startedAt) / lap * lap
            start to start + lap
        }
    }

    /** Status-bar chip text: minutes left, or minutes so far. */
    private fun chip(session: FocusSession, now: Long): String {
        val (_, end) = span(session, now)
        return if (session.pomodoro || session.targetMinutes > 0) {
            "${((end - now) / FocusMath.MINUTE).coerceAtLeast(0)}m"
        } else {
            "${(now - session.startedAt) / FocusMath.MINUTE}m"
        }
    }

    /** Notification.EXTRA_REQUEST_PROMOTED_ONGOING, spelled out so older SDK stubs still compile. */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
}
