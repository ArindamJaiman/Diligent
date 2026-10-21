package dev.diligent.app.notifications

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.widget.WidgetUpdater
import java.util.concurrent.TimeUnit

/**
 * Background GitHub contribution sync. Contribution graphs are synced manually from
 * Settings by default; this worker only runs if explicitly scheduled.
 */
@HiltWorker
class GithubSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: DiligentRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting GitHub contribution sync...")
        val (synced, total) = repository.syncAllGithubContributions()
        Log.d(TAG, "Synced $synced/$total contribution graphs")
        val success = synced == total

        WidgetUpdater.refreshGithub(applicationContext)

        // A misspelled username fails every time; don't keep retrying it forever.
        return when {
            success -> Result.success()
            runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {
        private const val TAG = "GithubSyncWorker"
        private const val WORK_NAME = "github_sync_work"
        private const val MAX_ATTEMPTS = 3

        /**
         * Schedules the periodic GitHub sync (runs every 4 hours).
         */
        fun schedulePeriodicSync(context: Context) {
            val request = PeriodicWorkRequestBuilder<GithubSyncWorker>(
                4, TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .addTag("github_sync")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }

        /**
         * Triggers an immediate one-time sync task.
         */
        fun enqueueOneTimeSync(context: Context) {
            val request = OneTimeWorkRequestBuilder<GithubSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag("github_sync_one_time")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "github_sync_one_time_work",
                    ExistingWorkPolicy.KEEP,
                    request
                )
        }
    }
}
