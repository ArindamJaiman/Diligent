package dev.diligent.app.focus.widget

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.diligent.app.R
import dev.diligent.app.focus.data.FocusSession
import dev.diligent.app.focus.domain.FocusMath
import dev.diligent.app.focus.domain.Phase
import dev.diligent.app.focus.domain.PomodoroPosition
import dev.diligent.app.focus.focusTimer
import dev.diligent.app.focus.notify.FocusNotifier
import dev.diligent.app.ui.MainActivity
import kotlinx.coroutines.CancellationException

/** Everything one render needs, read once in provideGlance. */
private data class FocusWidgetView(
    val session: FocusSession?,
    /** Activity a start would time; null when there is no hour-based activity. */
    val idleName: String?,
    /** Running mode, or the mode the next start uses. */
    val pomodoro: Boolean,
    val now: Long,
    /** SystemClock.elapsedRealtime() at [now]: the Chronometer's timebase. */
    val realtime: Long
) {
    val position: PomodoroPosition? =
        session?.takeIf { it.pomodoro }?.let { FocusMath.pomodoroAt(it.startedAt, now) }
}

/**
 * Small start/stop focus timer. While running, the time is a RemoteViews Chronometer that the
 * launcher ticks, so the widget is redrawn only on start, stop and Pomodoro phase changes.
 */
class FocusWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, SQUARE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val timer = focusTimer(context)
        val state = timer.current()
        val view = FocusWidgetView(
            session = state.session,
            idleName = if (state.session == null) timer.selectedActivity()?.name else null,
            pomodoro = state.session?.pomodoro ?: state.pomodoro,
            now = System.currentTimeMillis(),
            realtime = SystemClock.elapsedRealtime()
        )
        provideContent { FocusContent(view) }
    }

    companion object {
        val SMALL = DpSize(110.dp, 40.dp)
        val SQUARE = DpSize(110.dp, 100.dp)
    }
}

class FocusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FocusWidget()
}

object FocusWidgetUpdater {
    suspend fun refresh(context: Context) {
        try {
            FocusWidget().updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("FocusWidget", "Widget refresh failed", e)
        }
    }
}

// ─── Actions ────────────────────────────────────────────────────

class FocusToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        focusTimer(context).toggle()
    }
}

class FocusNextActivityAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        focusTimer(context).selectNext()
    }
}

class FocusModeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        focusTimer(context).togglePomodoro()
    }
}

private fun openApp(context: Context): Action = actionStartActivity(
    Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
)

// ─── Palette (matches the other widgets) ────────────────────────

private val Background = Color(0xF20A0A0A)
private val White = Color(0xFFF2F2F2)
private val Gray = Color(0xFF8C8C8C)
private val Dim = Color(0xFF5A5A5A)
private val Accent = Color(0xFF4ADE80)
private val ButtonIdle = Color(0xFF1E1E1E)
private val ButtonStop = Color(0xFF3A1414)

private fun headline(view: FocusWidgetView): String {
    val position = view.position
    return when {
        view.session == null -> if (view.pomodoro) "FOCUS · 25/5" else "FOCUS · TIMER"
        position == null -> "● FOCUS"
        position.phase == Phase.FOCUS -> "● FOCUS ${position.block}"
        else -> "◌ BREAK"
    }
}

private fun headlineColor(view: FocusWidgetView) =
    if (view.position?.phase == Phase.BREAK) Gray else Accent

/** Clock time the running phase ends at, or the session start for a plain timer. */
private fun subline(view: FocusWidgetView): String {
    val session = view.session ?: return "TAP NAME FOR NEXT"
    val position = view.position
    return when {
        position == null -> "SINCE ${FocusNotifier.formatClock(session.startedAt)}"
        position.phase == Phase.FOCUS -> "BREAK AT ${FocusNotifier.formatClock(position.phaseEnd)}"
        else -> "BACK AT ${FocusNotifier.formatClock(position.phaseEnd)}"
    }
}

// ─── Layouts ────────────────────────────────────────────────────

@Composable
private fun FocusContent(view: FocusWidgetView) {
    val size = LocalSize.current
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Background)
            .cornerRadius(16.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        when {
            view.session == null && view.idleName == null -> EmptyLayout()
            size.height >= FocusWidget.SQUARE.height -> SquareLayout(view)
            else -> SmallLayout(view)
        }
    }
}

@Composable
private fun EmptyLayout() {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier.fillMaxSize().clickable(openApp(context)),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        Text("FOCUS", style = mono(8.sp, Accent, bold = true))
        Text("ADD AN ACTIVITY IN HRS OR MINS", style = mono(7.sp, Dim), maxLines = 2)
    }
}

/** 2×1: name or clock on the left, ▶ / ■ on the right. */
@Composable
private fun SmallLayout(view: FocusWidgetView) {
    val context = LocalContext.current
    val session = view.session
    Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            if (session == null) {
                Text(
                    headline(view),
                    style = mono(7.sp, Accent, bold = true),
                    maxLines = 1,
                    modifier = GlanceModifier.clickable(actionRunCallback<FocusModeAction>())
                )
                Text(
                    view.idleName.orEmpty().uppercase(),
                    style = mono(10.sp, White, bold = true),
                    maxLines = 1,
                    modifier = GlanceModifier.clickable(actionRunCallback<FocusNextActivityAction>())
                )
            } else {
                Text(
                    "${headline(view)} · ${session.activityName.uppercase()}",
                    style = mono(7.sp, headlineColor(view), bold = true),
                    maxLines = 1,
                    modifier = GlanceModifier.clickable(openApp(context))
                )
                AndroidRemoteViews(chronometer(context, view, textSp = 15f))
            }
        }
        Spacer(GlanceModifier.width(6.dp))
        ToggleButton(running = session != null, label = if (session != null) "■" else "▶", modifier = GlanceModifier.size(30.dp))
    }
}

/** 2×2 and larger: mode, name, clock and a full-width START / STOP. */
@Composable
private fun SquareLayout(view: FocusWidgetView) {
    val context = LocalContext.current
    val session = view.session
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(
                headline(view),
                style = mono(8.sp, headlineColor(view), bold = true),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight().let {
                    if (session == null) it.clickable(actionRunCallback<FocusModeAction>()) else it
                }
            )
        }
        Spacer(GlanceModifier.defaultWeight())
        Text(
            (session?.activityName ?: view.idleName.orEmpty()).uppercase(),
            style = mono(10.sp, White, bold = true),
            maxLines = 2,
            modifier = GlanceModifier.fillMaxWidth().clickable(
                if (session == null) actionRunCallback<FocusNextActivityAction>() else openApp(context)
            )
        )
        if (session != null) {
            AndroidRemoteViews(chronometer(context, view, textSp = 22f))
        }
        Text(subline(view), style = mono(6.5.sp, Gray), maxLines = 1)
        Spacer(GlanceModifier.defaultWeight())
        ToggleButton(
            running = session != null,
            label = if (session != null) "■ STOP" else "▶ START",
            modifier = GlanceModifier.fillMaxWidth().height(28.dp)
        )
    }
}

@Composable
private fun ToggleButton(running: Boolean, label: String, modifier: GlanceModifier) {
    Box(
        modifier = modifier
            .background(if (running) ButtonStop else ButtonIdle)
            .cornerRadius(10.dp)
            .clickable(actionRunCallback<FocusToggleAction>()),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = mono(10.sp, White, bold = true), maxLines = 1)
    }
}

/**
 * Counts up from the start (plain timer) or down to the end of the Pomodoro phase. The base is
 * on the elapsedRealtime clock, derived from the wall-clock start at render time.
 */
private fun chronometer(context: Context, view: FocusWidgetView, textSp: Float): RemoteViews {
    val session = requireNotNull(view.session)
    val position = view.position
    return RemoteViews(context.packageName, R.layout.widget_focus_chronometer).apply {
        if (position != null) {
            setChronometerCountDown(R.id.focus_chronometer, true)
            setChronometer(R.id.focus_chronometer, view.realtime + (position.phaseEnd - view.now), null, true)
        } else {
            setChronometer(R.id.focus_chronometer, view.realtime - (view.now - session.startedAt), null, true)
        }
        setTextViewTextSize(R.id.focus_chronometer, TypedValue.COMPLEX_UNIT_SP, textSp)
        if (position?.phase == Phase.BREAK) setTextColor(R.id.focus_chronometer, 0xFF8C8C8C.toInt())
    }
}

private fun mono(size: TextUnit, color: Color, bold: Boolean = false) = TextStyle(
    color = ColorProvider(color),
    fontSize = size,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    fontFamily = FontFamily.Monospace
)
