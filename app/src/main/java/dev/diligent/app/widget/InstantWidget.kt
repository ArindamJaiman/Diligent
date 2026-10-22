package dev.diligent.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.compose
import kotlinx.coroutines.CancellationException

/**
 * Redraws for taps that should feel instant (+, next graph, next quote).
 *
 * A normal Glance update runs in a session worker. Sessions close about 45 s after they
 * start, so the first tap after that waits for WorkManager to start a new one before
 * anything changes on screen. [redraw] composes the tapped widget in this process and hands
 * the RemoteViews straight to the launcher instead. It does no extra work in the background:
 * one composition, only on a tap.
 */
object InstantWidget {

    private const val TAG = "InstantWidget"

    @OptIn(ExperimentalGlanceApi::class)
    suspend fun redraw(context: Context, widget: GlanceAppWidget, glanceId: GlanceId) {
        // Before Android 12, Glance lists are served by a RemoteViewsService that only a
        // session fills, so older versions keep the normal path.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
                val views = widget.compose(context, glanceId, manager.getAppWidgetOptions(appWidgetId))
                manager.updateAppWidget(appWidgetId, views)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Instant redraw failed; using a normal update", e)
            }
        }
        widget.update(context, glanceId)
    }

    /** [redraw] for every placed instance of [widget]. */
    suspend fun redrawAll(context: Context, widget: GlanceAppWidget) {
        GlanceAppWidgetManager(context).getGlanceIds(widget.javaClass).forEach { redraw(context, widget, it) }
    }
}

/** A normal update of every placed instance except [except], which a tap already redrew. */
suspend fun GlanceAppWidget.updateAllExcept(context: Context, except: GlanceId?) {
    GlanceAppWidgetManager(context).getGlanceIds(javaClass)
        .filter { it != except }
        .forEach { update(context, it) }
}

/**
 * Tells a double tap from a single one by when the taps arrive: widgets only report single
 * clicks, so the app times them itself. Pure, so it is unit tested.
 */
class TapTracker(private val windowMs: Long = DOUBLE_TAP_MS) {

    data class Tap(val token: Long, val isDouble: Boolean)

    private var lastSingleAt: Long? = null
    private var token = 0L

    /** Registers a tap at [now] (a monotonic clock); a third quick tap starts a new pair. */
    @Synchronized
    fun tap(now: Long): Tap {
        val last = lastSingleAt
        val double = last != null && now - last in 0..windowMs
        token++
        lastSingleAt = if (double) null else now
        return Tap(token, double)
    }

    /** True while no later tap has arrived, so a single tap's delayed action should still run. */
    @Synchronized
    fun isLatest(tap: Tap): Boolean = tap.token == token

    companion object {
        /** A little longer than the system's 300 ms, since taps arrive as broadcasts. */
        const val DOUBLE_TAP_MS = 400L
    }
}
