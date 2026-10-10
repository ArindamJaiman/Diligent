package dev.diligent.app.quotapulse.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dev.diligent.app.R
import dev.diligent.app.di.WidgetEntryPoint
import dev.diligent.app.quotapulse.data.LiveMode
import dev.diligent.app.quotapulse.data.QuotaPulsePrefs
import dev.diligent.app.quotapulse.sync.SyncOutcome
import dev.diligent.app.quotapulse.sync.SyncTrigger
import dev.diligent.app.quotapulse.sync.UsageSyncEngine
import dev.diligent.app.quotapulse.widget.QuotaPulseWidgetUpdater
import dev.diligent.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Explicit, user-started live monitoring.
 *
 * - Started only from a user tap (widget, app, or notification); never on boot or app start.
 * - Runs one controlled loop: sync → wait interval (doubled in battery saver) → repeat.
 *   No wake locks; while the phone sleeps the loop simply pauses.
 * - START_NOT_STICKY: if Android kills it, it is NOT silently restarted. The widget then
 *   shows INTERRUPTED with the reason.
 */
@AndroidEntryPoint
class LiveMonitorService : Service() {

    @Inject lateinit var engine: UsageSyncEngine
    @Inject lateinit var prefs: QuotaPulsePrefs

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.markManual()
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        // startForegroundService() requires startForeground() within a few seconds.
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Starting…"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when not started from a user action
            prefs.markInterrupted("Android did not allow live mode to start (${e.javaClass.simpleName})")
            refreshWidget()
            stopSelf()
            return START_NOT_STICKY
        }

        if (loop?.isActive == true) return START_NOT_STICKY

        if (engine.configuredKinds().isEmpty()) {
            prefs.markInterrupted("Connect a source before starting live mode")
            refreshWidget()
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        prefs.markLiveStarted(System.currentTimeMillis())
        refreshWidget()

        val powerManager = getSystemService(PowerManager::class.java)
        loop = scope.launch {
            while (isActive) {
                val outcome = engine.sync(SyncTrigger.LIVE)
                val saver = powerManager?.isPowerSaveMode == true
                prefs.setPowerSaveSlowdown(saver)
                val intervalMs = prefs.current().liveIntervalSec * 1000L * (if (saver) 2 else 1)
                updateNotification(outcome, intervalMs, saver)
                delay(intervalMs)
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+: dataSync services are limited to 6 hours per 24 hours. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        prefs.markInterrupted("Android's 6-hour daily limit for data-sync services was reached")
        stopForegroundAndSelf()
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        isRunning = false
        if (prefs.current().liveMode == LiveMode.LIVE) {
            prefs.markInterrupted("Live monitoring was stopped by the system")
        }
        refreshWidget()
        super.onDestroy()
    }

    private fun stopForegroundAndSelf() {
        loop?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun refreshWidget() = QuotaPulseWidgetUpdater.refreshAsync(applicationContext)

    private fun updateNotification(outcome: SyncOutcome, intervalMs: Long, saver: Boolean) {
        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.now())
        val cadence = "every ${intervalMs / 1000}s" + if (saver) " (battery saver)" else ""
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification("${outcome.summary} · $time · $cadence"))
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "QuotaPulse live monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while QuotaPulse live mode is running"
            }
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setData(Uri.parse(MainActivity.ROUTE_URI_QUOTAPULSE))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("QuotaPulse · LIVE")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "STOP LIVE", QuotaPulseControlReceiver.stopPendingIntent(this))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_START = "dev.diligent.app.quotapulse.action.START_LIVE"
        const val ACTION_STOP = "dev.diligent.app.quotapulse.action.STOP_LIVE"
        private const val CHANNEL_ID = "quotapulse_live"
        private const val NOTIFICATION_ID = 2102

        /**
         * True only while the service is actually running in this process. A saved
         * LIVE preference alone is never treated as proof that monitoring is active.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun startIntent(context: Context): Intent =
            Intent(context, LiveMonitorService::class.java).setAction(ACTION_START)
    }
}

/**
 * Stops live mode from the widget or the notification. A broadcast works whether or
 * not the service is still alive, and stopService is allowed from the background.
 */
class QuotaPulseControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            LiveMonitorService.ACTION_STOP -> {
                val prefs = EntryPointAccessors.fromApplication(appContext, WidgetEntryPoint::class.java).quotaPulsePrefs()
                // Mark MANUAL first so the service's onDestroy doesn't report an interruption.
                prefs.markManual()
                appContext.stopService(Intent(appContext, LiveMonitorService::class.java))
            }
            ACTION_REFRESH -> Unit
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                QuotaPulseWidgetUpdater.refresh(appContext)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Redraw only (stale boundary / quota reset); never triggers network requests. */
        const val ACTION_REFRESH = "dev.diligent.app.quotapulse.action.REFRESH_WIDGET"

        fun stopIntent(context: Context): Intent =
            Intent(context, QuotaPulseControlReceiver::class.java).setAction(LiveMonitorService.ACTION_STOP)

        fun stopPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 2103, stopIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
