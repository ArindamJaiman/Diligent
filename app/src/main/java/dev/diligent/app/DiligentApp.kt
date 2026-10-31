package dev.diligent.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.diligent.app.data.local.SampleDataSeeder
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.focus.FocusTimer
import dev.diligent.app.leetcode.data.LeetCodePrefs
import dev.diligent.app.leetcode.notify.LeetCodeAlarms
import dev.diligent.app.leetcode.notify.LeetCodeReminders
import dev.diligent.app.notifications.ActivityReminders
import dev.diligent.app.notifications.ReminderScheduler
import dev.diligent.app.quotapulse.data.LiveMode
import dev.diligent.app.quotapulse.data.QuotaPulsePrefs
import dev.diligent.app.quotapulse.live.LiveMonitorService
import dev.diligent.app.tools.ToolsBootstrap
import dev.diligent.app.tracking.DayClock
import dev.diligent.app.widget.DayChangeRefresh
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Diligent Application class.
 * Initializes Hilt DI and custom WorkManager configuration.
 */
@HiltAndroidApp
class DiligentApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var seeder: SampleDataSeeder

    @Inject
    lateinit var repository: DiligentRepository

    @Inject
    lateinit var quotaPulsePrefs: QuotaPulsePrefs

    @Inject
    lateinit var leetCodePrefs: LeetCodePrefs

    @Inject
    lateinit var focusTimer: FocusTimer

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        // "Today" for every screen, widget and job; read before any of them run.
        DayClock.init(this)

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Seed default activities on first launch
            seeder.seedIfEmpty()

            // Commitment mode: lowered goals apply once their waiting week is over
            repository.applyDueGoalChanges()

            // Make reminder jobs match the user's settings (keeps already-queued jobs)
            ReminderScheduler.sync(this@DiligentApp, repository.getSettingsSnapshot(), replaceExisting = false)
            ActivityReminders.syncAll(this@DiligentApp, repository, replace = false)

            // LeetCode checks (kept if already queued) and contest alarms, which a reboot clears
            val leetCode = leetCodePrefs.current()
            LeetCodeReminders.sync(this@DiligentApp, leetCode, replace = false)
            LeetCodeAlarms.armContests(this@DiligentApp, leetCode.snapshot, leetCode.config, System.currentTimeMillis())
        }

        // A focus timer outlives its process (it's only a start time in prefs). Re-post its
        // notification, re-arm the Pomodoro alarm and log blocks that ended meanwhile.
        if (focusTimer.current().session != null) focusTimer.launch { refresh() }

        // Re-arm the midnight widget refresh if any widget is on the home screen
        DayChangeRefresh.scheduleIfWidgetsExist(this)

        // Hub tools: keep their scheduled jobs, re-arm contest alarms, refresh on app open
        ToolsBootstrap.onAppStart(this)

        // A fresh process can't have a running live service. If live mode was marked on,
        // report the interruption truthfully instead of claiming LIVE or restarting it.
        if (quotaPulsePrefs.current().liveMode == LiveMode.LIVE && !LiveMonitorService.isRunning) {
            quotaPulsePrefs.markInterrupted("Live monitoring ended when the app was closed or the phone restarted")
        }
    }
}
