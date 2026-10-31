package dev.diligent.app.tools

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.glance.GlanceId
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.diligent.app.automation.AutoLogService
import dev.diligent.app.automation.PlaceTriggers
import dev.diligent.app.contests.ContestRadar
import dev.diligent.app.contests.ContestWidgetUpdater
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.health.HealthSync
import dev.diligent.app.papers.PaperService
import dev.diligent.app.papers.PaperWidgetUpdater
import dev.diligent.app.screentime.ScreenTimeService
import dev.diligent.app.screentime.ScreenTimeWidgetUpdater
import dev.diligent.app.top3.Top3Service
import dev.diligent.app.top3.Top3WidgetUpdater
import dev.diligent.app.ui.MainActivity
import dev.diligent.app.versus.VersusSync
import dev.diligent.app.versus.VersusWidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hilt access for widgets, receivers and plain objects in the tools packages. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ToolsEntryPoint {
    fun repository(): DiligentRepository
    fun toolServices(): ToolServices
    fun contestRadar(): ContestRadar
    fun paperService(): PaperService
    fun top3Service(): Top3Service
    fun screenTimeService(): ScreenTimeService
    fun healthSync(): HealthSync
    fun versusSync(): VersusSync
    fun autoLog(): AutoLogService
}

/**
 * The tools' single hook into app start (called from DiligentApp):
 * - keeps their recurring jobs queued (reboot-safe via WorkManager),
 * - re-arms contest alarms, which a reboot clears,
 * - refreshes local data when the app comes to the foreground (throttled, mostly offline).
 */
object ToolsBootstrap {

    private const val FOREGROUND_GAP_MS = 10 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastForeground = 0L

    fun onAppStart(app: Application) {
        val entry = EntryPointAccessors.fromApplication(app, ToolsEntryPoint::class.java)
        ToolJobs.scheduleAll(app, replace = false)
        entry.contestRadar().armAlarms()
        // Geofences don't survive a reboot or an app update; register them again.
        PlaceTriggers.registerAll(app)

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) onForeground(app)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun onForeground(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastForeground < FOREGROUND_GAP_MS) return
        lastForeground = now
        val entry = EntryPointAccessors.fromApplication(context, ToolsEntryPoint::class.java)
        scope.launch {
            safely { entry.healthSync().sync() }          // local Health Connect read
            safely { entry.healthSync().syncWellbeing() } // sleep, steps, mindfulness → day log
            safely { entry.contestRadar().refreshIfStale() } // network only if data is over 6 h old
            safely { entry.paperService().sync() }          // network only once a day
            safely { entry.autoLog().sync() }               // WakaTime, GitHub, CF/AtCoder, app time
            safely { ToolWidgets.refreshLocal(context) }     // Top 3, screen time: no network
        }
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ToolsBootstrap", "foreground refresh failed", e)
        }
    }
}

object ToolWidgets {
    /** Widgets whose content depends only on local data (tracker progress, usage stats, calendar). */
    /** @param except a widget a tap already redrew */
    suspend fun refreshLocal(context: Context, except: GlanceId? = null) {
        Top3WidgetUpdater.refresh(context, except)
        ScreenTimeWidgetUpdater.refresh(context, except)
    }

    suspend fun refresh(context: Context, target: String?) {
        when (target) {
            ToolRoutes.CONTESTS -> ContestWidgetUpdater.refresh(context)
            ToolRoutes.VERSUS -> VersusWidgetUpdater.refresh(context)
            ToolRoutes.PAPERS -> PaperWidgetUpdater.refresh(context)
            ToolRoutes.TOP3 -> Top3WidgetUpdater.refresh(context)
            ToolRoutes.SCREEN_TIME -> ScreenTimeWidgetUpdater.refresh(context)
            else -> {
                refreshLocal(context)
                ContestWidgetUpdater.refresh(context)
                VersusWidgetUpdater.refresh(context)
                PaperWidgetUpdater.refresh(context)
            }
        }
    }
}

/** Contest heads-up alarms and widget redraws. Not exported. */
class ToolsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_CONTEST_ALERT -> {
                val entry = EntryPointAccessors.fromApplication(app, ToolsEntryPoint::class.java)
                entry.contestRadar().onAlarm(intent.getStringExtra(EXTRA_PAYLOAD))
            }
            ACTION_REDRAW -> {
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                    try {
                        ToolWidgets.refresh(app, intent.getStringExtra(EXTRA_TARGET))
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_CONTEST_ALERT = "dev.diligent.app.tools.CONTEST_ALERT"
        const val ACTION_REDRAW = "dev.diligent.app.tools.REDRAW"
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_TARGET = "target"
    }
}
