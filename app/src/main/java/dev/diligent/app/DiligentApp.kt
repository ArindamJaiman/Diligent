package dev.diligent.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    lateinit var seeder: dev.diligent.app.data.local.SampleDataSeeder

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Seed default activities on first launch
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            seeder.seedIfEmpty()
        }

        // Schedule reminders
        dev.diligent.app.notifications.ReminderWorker.scheduleDailyReminder(this)
        dev.diligent.app.notifications.ReminderWorker.scheduleMissedGoalCheck(this)

        // Restart widget slideshow scheduler if widgets are present on home screen
        val appWidgetManager = android.appwidget.AppWidgetManager.getInstance(this)
        val componentName = android.content.ComponentName(this, dev.diligent.app.widget.GithubWidgetReceiver::class.java)
        val ids = appWidgetManager.getAppWidgetIds(componentName)
        if (ids != null && ids.isNotEmpty()) {
            dev.diligent.app.widget.SlideshowScheduler.start(this)
        }
    }
}
