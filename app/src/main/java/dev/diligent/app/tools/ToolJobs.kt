package dev.diligent.app.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.EntryPointAccessors
import dev.diligent.app.R
import dev.diligent.app.contests.ContestRadar
import dev.diligent.app.health.HealthSync
import dev.diligent.app.papers.PaperService
import dev.diligent.app.review.WeeklyReviewService
import dev.diligent.app.screentime.ScreenTimeService
import dev.diligent.app.top3.Top3Service
import dev.diligent.app.versus.VersusSync
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every background job the tools run. Recurring jobs are self-chaining one-time WorkManager
 * jobs at a clock time (same approach as the app's reminders): they survive reboots, each run
 * queues the next, and nothing polls in between. One-shot jobs are expedited "sync now" taps.
 */
enum class ToolJob(val recurring: Boolean, val needsNetwork: Boolean) {
    WEEKLY_REVIEW(true, false),
    CONTESTS_DAILY(true, true),
    PAPERS_DAILY(true, true),
    TOP3_PROMPT(true, false),
    SCREEN_NUDGE(true, false),
    HEALTH_EVENING(true, false),
    VERSUS_EVENING(true, true),
    CONTESTS_SYNC(false, true),
    PAPERS_SYNC(false, true),
    VERSUS_SYNC(false, true);

    val workName: String get() = "tool_" + name.lowercase()
}

/** The tools' singletons, in one place for the worker and the scheduler. */
@Singleton
class ToolServices @Inject constructor(
    val weekly: WeeklyReviewService,
    val contests: ContestRadar,
    val papers: PaperService,
    val top3: Top3Service,
    val screenTime: ScreenTimeService,
    val health: HealthSync,
    val versus: VersusSync
) {
    suspend fun run(job: ToolJob) {
        when (job) {
            ToolJob.WEEKLY_REVIEW -> weekly.runScheduled()
            ToolJob.CONTESTS_DAILY -> contests.sync(force = true, ratings = false)
            ToolJob.CONTESTS_SYNC -> contests.sync(force = true, ratings = true)
            ToolJob.PAPERS_DAILY -> papers.runDaily()
            ToolJob.PAPERS_SYNC -> papers.sync(force = true)
            ToolJob.TOP3_PROMPT -> top3.runPrompt()
            ToolJob.SCREEN_NUDGE -> screenTime.runNudge()
            ToolJob.HEALTH_EVENING -> health.runEvening()
            ToolJob.VERSUS_EVENING -> versus.runEvening()
            ToolJob.VERSUS_SYNC -> versus.sync(force = true)
        }
    }

    /** Next clock time for a recurring job, or null when it is switched off. */
    fun nextRun(job: ToolJob, now: ZonedDateTime): ZonedDateTime? = when (job) {
        ToolJob.WEEKLY_REVIEW -> weekly.nextRun(now)
        ToolJob.CONTESTS_DAILY -> contests.nextRun(now)
        ToolJob.PAPERS_DAILY -> papers.nextRun(now)
        ToolJob.TOP3_PROMPT -> top3.nextRun(now)
        ToolJob.SCREEN_NUDGE -> screenTime.nextRun(now)
        ToolJob.HEALTH_EVENING -> health.nextRun(now)
        ToolJob.VERSUS_EVENING -> versus.nextRun(now)
        else -> null
    }
}

object ToolJobs {

    private fun services(context: Context) =
        EntryPointAccessors.fromApplication(context.applicationContext, ToolsEntryPoint::class.java).toolServices()

    /** Makes every recurring job match its settings. replace=false on app start keeps queued jobs. */
    fun scheduleAll(context: Context, replace: Boolean) {
        ToolJob.entries.filter { it.recurring }.forEach { schedule(context, it, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP) }
    }

    /** After a settings change (outside a running job). */
    fun reschedule(context: Context, job: ToolJob) = schedule(context, job, ExistingWorkPolicy.REPLACE)

    /** From inside a running job: appended, so it doesn't cancel itself. */
    internal fun chain(context: Context, job: ToolJob) = schedule(context, job, ExistingWorkPolicy.APPEND_OR_REPLACE)

    private fun schedule(context: Context, job: ToolJob, policy: ExistingWorkPolicy) {
        val workManager = WorkManager.getInstance(context)
        val now = ZonedDateTime.now()
        val next = services(context).nextRun(job, now)
        if (next == null) {
            if (policy != ExistingWorkPolicy.APPEND_OR_REPLACE) workManager.cancelUniqueWork(job.workName)
            return
        }
        val delay = Duration.between(now, next).toMillis().coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ToolsWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ToolsWorker.KEY_JOB to job.name))
            .addTag(job.workName)
            .build()
        workManager.enqueueUniqueWork(job.workName, policy, request)
    }

    /** "Sync now" from a widget: one expedited run, repeated taps coalesced. */
    fun runNow(context: Context, job: ToolJob) {
        val request = OneTimeWorkRequestBuilder<ToolsWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .apply { if (job.needsNetwork) setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()) }
            .setInputData(workDataOf(ToolsWorker.KEY_JOB to job.name))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(job.workName, ExistingWorkPolicy.KEEP, request)
    }
}

@HiltWorker
class ToolsWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val services: ToolServices
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val job = ToolJob.entries.firstOrNull { it.name == inputData.getString(KEY_JOB) } ?: return Result.success()
        try {
            services.run(job)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ToolsWorker", "$job failed", e)
        } finally {
            if (job.recurring) ToolJobs.chain(applicationContext, job)
        }
        return Result.success()
    }

    /** Only used on Android 11 and older, where expedited work runs as a short foreground job. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Tool sync", NotificationManager.IMPORTANCE_MIN))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Diligent · syncing")
            .setOngoing(true)
            .build()
        return ForegroundInfo(4101, notification)
    }

    companion object {
        const val KEY_JOB = "tool_job"
        private const val CHANNEL = "tool_sync"
    }
}
