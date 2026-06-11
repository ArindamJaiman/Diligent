package dev.diligent.app.notifications

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.diligent.app.data.repository.DiligentRepository
import androidx.glance.appwidget.updateAll
import dev.diligent.app.widget.GithubWidget
import dev.diligent.app.widget.GithubVsWidget
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class GithubSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: DiligentRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting GitHub contribution sync...")
        val settings = repository.getSettingsSnapshot()
        
        val user1 = settings.githubUser1.ifBlank { "LennyDany-03" }
        val user2 = settings.githubUser2.ifBlank { "Quadr1on" }
        val user3 = settings.githubUser3.ifBlank { "SidhanthBibi" }
        val users = listOf(user1, user2, user3).filter { it.isNotBlank() }

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

        try {
            GithubWidget().updateAll(applicationContext)
            GithubVsWidget().updateAll(applicationContext)
            Log.d(TAG, "Widgets triggered for recomposition")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating widgets after sync", e)
        }

        return if (success) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "GithubSyncWorker"
        private const val WORK_NAME = "github_sync_work"

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
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }
    }
}
