package dev.diligent.app.quotapulse.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.diligent.app.R

/**
 * One-shot manual sync ("SYNC NOW"). Enqueued as unique work with KEEP, so repeated
 * taps while a sync is pending or running are coalesced into a single request.
 */
@HiltWorker
class QuotaPulseSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: UsageSyncEngine
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        engine.sync(SyncTrigger.MANUAL)
        return Result.success()
    }

    /** Only used on Android 11 and older, where expedited work runs as a short foreground job. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "QuotaPulse sync", NotificationManager.IMPORTANCE_MIN)
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("QuotaPulse · syncing")
            .setOngoing(true)
            .build()
        // No service type: WorkManager's SystemForegroundService declares none in its manifest,
        // and passing one on Android 10–11 would throw. Types are only enforced from Android 14,
        // where expedited work never runs as a foreground service.
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val WORK_NAME = "quotapulse_manual_sync"
        private const val CHANNEL_ID = "quotapulse_sync"
        private const val NOTIFICATION_ID = 2101

        fun request(context: Context) {
            val request = OneTimeWorkRequestBuilder<QuotaPulseSyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
