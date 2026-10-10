package dev.diligent.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.diligent.app.data.local.SampleDataSeeder
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.notifications.ReminderScheduler
import dev.diligent.app.quotapulse.data.LiveMode
import dev.diligent.app.quotapulse.data.QuotaPulsePrefs
import dev.diligent.app.quotapulse.live.LiveMonitorService
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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Seed default activities on first launch
            seeder.seedIfEmpty()

            // Make reminder jobs match the user's settings (keeps already-queued jobs)
            ReminderScheduler.sync(this@DiligentApp, repository.getSettingsSnapshot(), replaceExisting = false)
        }

        // Re-arm the midnight widget refresh if any widget is on the home screen
        DayChangeRefresh.scheduleIfWidgetsExist(this)

        // A fresh process can't have a running live service. If live mode was marked on,
        // report the interruption truthfully instead of claiming LIVE or restarting it.
        if (quotaPulsePrefs.current().liveMode == LiveMode.LIVE && !LiveMonitorService.isRunning) {
            quotaPulsePrefs.markInterrupted("Live monitoring ended when the app was closed or the phone restarted")
        }
    }
}
