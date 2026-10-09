package dev.diligent.app.notifications

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.diligent.app.data.local.entity.githubSlots
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.widget.WidgetUpdater
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val users = repository.getSettingsSnapshot().githubSlots().map { it.username }

        var success = true
        if (users.isNotEmpty()) {
            val results = withContext(Dispatchers.IO) {
                users.map { username ->
                    async {
                        val res = repository.syncGithubContributions(username)
                        Log.d(TAG, "Sync user $username result: $res")
                        res
                    }
                }.awaitAll()
            }
            if (results.any { !it }) {
                success = false
            }
        }

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
