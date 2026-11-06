package dev.diligent.app.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import dev.diligent.app.R
import dev.diligent.app.tools.ToolRoutes
import dev.diligent.app.tools.openToolIntent
import java.time.ZonedDateTime

/**
 * "Copy on the laptop → notification on the phone" (Hub → Clipboard, opt-in): about every 15
 * minutes, while it's on, the phone checks your own clips and notifies you of new ones from
 * your other devices, with COPY. A WorkManager job rather than a live Realtime socket, which
 * would keep the phone awake all day; Android may stretch the interval in Doze.
 */
object ClipWatch {
    private const val PREFS = "clipboard"
    private const val KEY_ON = "watch_on"
    private const val KEY_LAST = "watch_last"
    private const val CHANNEL = "clipboard_new"
    private const val NOTIFICATION_ID = 4310
    const val INTERVAL_MINUTES = 15L

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    /** Turning it on starts from now: clips already there don't notify. */
    fun setEnabled(context: Context, on: Boolean) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on)
        if (on) edit.putLong(KEY_LAST, System.currentTimeMillis())
        edit.apply()
    }

    fun nextRun(context: Context, ready: Boolean, now: ZonedDateTime): ZonedDateTime? =
        if (enabled(context) && ready) now.plusMinutes(INTERVAL_MINUTES) else null

    /** Clips newer than [since] that another device sent, oldest first. Pure, so it's unit-tested. */
    fun fromOthers(clips: List<Clip>, since: Long, thisDevice: String): List<Clip> =
        clips.filter { it.createdAt > since && !it.device.equals(thisDevice, ignoreCase = true) }.sortedBy { it.createdAt }

    /** What the notification says about a clip. */
    fun preview(clip: Clip): String = when {
        clip.locked -> "🔒 Encrypted clip · set the passphrase in Hub → Clipboard"
        clip.isImage -> "🖼 Image · open the web app to see it"
        clip.isFile -> "📎 " + clip.fileName
        else -> clip.text.take(300)
    }

    suspend fun check(context: Context, service: ClipboardService) {
        if (!enabled(context) || !service.ready) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getLong(KEY_LAST, System.currentTimeMillis())
        val clips = service.list()
        val fresh = fromOthers(clips, since, service.deviceName)
        clips.maxOfOrNull { it.createdAt }?.let { newest -> if (newest > since) prefs.edit().putLong(KEY_LAST, newest).apply() }
        val newest = fresh.lastOrNull() ?: return
        notify(context, newest, fresh.size)
    }

    private fun notify(context: Context, clip: Clip, count: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "New clips from your devices", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID, openToolIntent(context, ToolRoutes.CLIPBOARD),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (count > 1) "$count clips from your devices · newest from ${clip.device}" else "Clip from ${clip.device}")
            .setContentText(preview(clip))
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview(clip)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        if (!clip.locked && !clip.isImage && !clip.isFile) {
            val copy = PendingIntent.getBroadcast(
                context, NOTIFICATION_ID + 1,
                Intent(context, ClipCopyReceiver::class.java).putExtra(ClipCopyReceiver.EXTRA_TEXT, clip.text.take(100_000)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, "COPY", copy)
        }
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    internal fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}

/** COPY on the notification: onto the phone's clipboard (labelled so auto-send skips it). */
class ClipCopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(AutoSendClip.OWN_LABEL, text))
        ClipWatch.cancel(context)
        // Android 13+ shows its own "Copied" confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_TEXT = "text"
    }
}
