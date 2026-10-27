package dev.diligent.app.quotapulse.widget

import android.app.AlarmManager
import dev.diligent.app.widget.WidgetStyle
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
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
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.action.ActionParameters
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
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
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.android.EntryPointAccessors
import dev.diligent.app.di.WidgetEntryPoint
import dev.diligent.app.quotapulse.data.Provider
import dev.diligent.app.quotapulse.live.LiveMonitorService
import dev.diligent.app.quotapulse.live.QuotaPulseControlReceiver
import dev.diligent.app.quotapulse.presentation.OverallState
import dev.diligent.app.quotapulse.presentation.ProviderCell
import dev.diligent.app.quotapulse.presentation.QuotaPulsePresenter
import dev.diligent.app.quotapulse.presentation.QuotaPulseView
import dev.diligent.app.quotapulse.presentation.Tone
import dev.diligent.app.quotapulse.sync.QuotaPulseSyncWorker
import dev.diligent.app.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * QuotaPulse — a Nothing-inspired AI usage instrument for the home screen.
 *
 * Renders only the last persisted snapshot: creating, resizing, or redrawing the widget
 * never calls a provider. Network work happens only on SYNC (or in user-started live mode).
 * Periodic widget updates are disabled (updatePeriodMillis = 0).
 */
class QuotaPulseWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, STANDARD, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        val dao = entryPoint.quotaPulseDao()
        val now = System.currentTimeMillis()
        val snapshots = dao.snapshots()
        val view = QuotaPulsePresenter.build(
            snapshots = snapshots,
            statuses = dao.statuses(),
            configured = entryPoint.usageSyncEngine().configuredKinds(),
            runtime = entryPoint.quotaPulsePrefs().current(),
            liveRunning = LiveMonitorService.isRunning,
            now = now,
            absoluteTimes = true
        )
        scheduleNextRedraw(context, QuotaPulsePresenter.nextBoundary(snapshots, now))

        provideContent { QuotaPulseContent(view) }
    }

    companion object {
        val COMPACT = DpSize(110.dp, 110.dp)
        val STANDARD = DpSize(250.dp, 110.dp)
        val LARGE = DpSize(250.dp, 200.dp)

        /**
         * One non-wakeup alarm at the next moment the display would change (data turning
         * stale or a quota window resetting). It only redraws — no network.
         */
        private fun scheduleNextRedraw(context: Context, at: Long?) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = PendingIntent.getBroadcast(
                context, 2104,
                Intent(context, QuotaPulseControlReceiver::class.java).setAction(QuotaPulseControlReceiver.ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (at == null) alarmManager.cancel(intent) else alarmManager.set(AlarmManager.RTC, at + 1_000, intent)
        }
    }
}

class QuotaPulseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuotaPulseWidget()
}

/** Redraws all QuotaPulse widget instances. */
object QuotaPulseWidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun refresh(context: Context) {
        try {
            QuotaPulseWidget().updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("QuotaPulse", "Widget refresh failed", e)
        }
    }

    fun refreshAsync(context: Context) {
        scope.launch { refresh(context.applicationContext) }
    }
}

/**
 * SYNC NOW from the widget: shows SYNCING immediately and enqueues one coalesced
 * manual sync. Repeated taps while it is pending don't add requests.
 */
class QuotaPulseSyncAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val prefs = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            .quotaPulsePrefs()
        if (!prefs.current().isSyncing(System.currentTimeMillis())) {
            prefs.markSyncStarted(System.currentTimeMillis())
        }
        QuotaPulseSyncWorker.request(context)
        QuotaPulseWidgetUpdater.refresh(context)
    }
}

// ─── Palette (monochrome + one restrained red) ──────────────────

private val Background = WidgetStyle.Background
private val White = Color(0xFFF2F2F2)
private val Gray = Color(0xFF8C8C8C)
private val Dim = Color(0xFF5A5A5A)
private val Line = Color(0xFF1E1E1E)
private val ButtonFill = Color(0xFF1A1A1A)
private val NothingRed = Color(0xFFD71920)

private fun toneColor(tone: Tone) = when (tone) {
    Tone.NORMAL -> White
    Tone.DIM -> Gray
    Tone.ALERT -> NothingRed
}

private fun overallDot(state: OverallState) = when (state) {
    OverallState.LIVE, OverallState.INTERRUPTED -> NothingRed
    OverallState.SYNCED -> White
    OverallState.SYNCING -> Gray
    else -> Dim
}

// ─── Actions ────────────────────────────────────────────────────

private fun openRoute(context: Context, uri: String): Action = actionStartActivity(
    Intent(context, MainActivity::class.java)
        .setData(Uri.parse(uri))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
)

private fun openMain(context: Context) = openRoute(context, MainActivity.ROUTE_URI_QUOTAPULSE)

private fun openProvider(context: Context, provider: Provider) =
    openRoute(context, MainActivity.ROUTE_URI_QUOTAPULSE + "/provider/" + provider.name)

// ─── Layouts ────────────────────────────────────────────────────

@Composable
private fun QuotaPulseContent(view: QuotaPulseView) {
    val size = LocalSize.current
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Background)
            .cornerRadius(18.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        when {
            size.width >= QuotaPulseWidget.LARGE.width && size.height >= QuotaPulseWidget.LARGE.height -> LargeLayout(view)
            size.width >= QuotaPulseWidget.STANDARD.width -> StandardLayout(view)
            else -> CompactLayout(view)
        }
    }
}

@Composable
private fun Header(view: QuotaPulseView, showSync: Boolean = true) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        Text(
            text = "QUOTAPULSE",
            style = mono(9.sp, White, bold = true),
            modifier = GlanceModifier.clickable(openMain(context))
        )
        Spacer(GlanceModifier.width(6.dp))
        Dot(overallDot(view.overall))
        Spacer(GlanceModifier.width(4.dp))
        Text(text = view.overall.label, style = mono(7.sp, Gray), maxLines = 1)
        Spacer(GlanceModifier.defaultWeight())
        if (showSync) SyncChip(view)
    }
}

@Composable
private fun SyncChip(view: QuotaPulseView) {
    Box(
        modifier = GlanceModifier
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .clickable(actionRunCallback<QuotaPulseSyncAction>()),
        contentAlignment = Alignment.Center
    ) {
        Text(text = if (view.syncing) "SYNC…" else "SYNC", style = mono(8.sp, if (view.syncing) Dim else White, bold = true))
    }
}

@Composable
private fun Dot(color: Color, size: Int = 5) {
    Box(modifier = GlanceModifier.size(size.dp).cornerRadius((size / 2f).dp).background(color)) {}
}

@Composable
private fun Divider() {
    Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(Line)) {}
}

/** ~2×2: one most-useful metric, overall state, and a sync target. */
@Composable
private fun CompactLayout(view: QuotaPulseView) {
    val context = LocalContext.current
    val best = view.best
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text("QUOTAPULSE", style = mono(8.sp, White, bold = true), modifier = GlanceModifier.clickable(openMain(context)))
            Spacer(GlanceModifier.defaultWeight())
            Dot(overallDot(view.overall))
        }
        Text(view.modeLabel.takeIf { view.liveActive || view.interruptReason != null } ?: view.overall.label,
            style = mono(7.sp, if (view.interruptReason != null || view.liveActive) NothingRed else Gray), maxLines = 1)
        Spacer(GlanceModifier.defaultWeight())
        if (best != null) {
            Column(modifier = GlanceModifier.fillMaxWidth().clickable(openProvider(context, best.provider))) {
                Text(best.provider.label, style = mono(7.sp, Gray), maxLines = 1)
                Row(verticalAlignment = Alignment.Vertical.Bottom) {
                    Text(best.headline, style = mono(22.sp, toneColor(best.tone), bold = true), maxLines = 1)
                    Spacer(GlanceModifier.width(3.dp))
                    Text(best.unit, style = mono(7.sp, Gray), maxLines = 1)
                }
                Text(best.window, style = mono(6.5.sp, Dim), maxLines = 1)
            }
        } else {
            Text("NO DATA", style = mono(13.sp, Gray, bold = true), modifier = GlanceModifier.clickable(openMain(context)))
            Text("tap to connect", style = mono(7.sp, Dim))
        }
        Spacer(GlanceModifier.defaultWeight())
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(view.updatedLabel.removePrefix("UPDATED "), style = mono(7.sp, Dim), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            SyncChip(view)
        }
    }
}

/** ~4×2 primary design: 2×2 providers between a header and footer. */
@Composable
private fun StandardLayout(view: QuotaPulseView) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Header(view)
        Spacer(GlanceModifier.height(4.dp))
        Divider()
        Spacer(GlanceModifier.height(6.dp))
        Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            CellRow(view.cells[0], view.cells[1])
            Spacer(GlanceModifier.height(6.dp))
            CellRow(view.cells[2], view.cells[3])
        }
        Footer(view)
    }
}

@Composable
private fun CellRow(left: ProviderCell, right: ProviderCell) {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        CompactCell(left, GlanceModifier.defaultWeight())
        Spacer(GlanceModifier.width(10.dp))
        CompactCell(right, GlanceModifier.defaultWeight())
    }
}

@Composable
private fun CompactCell(cell: ProviderCell, modifier: GlanceModifier) {
    val context = LocalContext.current
    Column(modifier = modifier.clickable(openProvider(context, cell.provider))) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(cell.provider.label, style = mono(7.sp, Gray), maxLines = 1)
            Spacer(GlanceModifier.defaultWeight())
            Dot(toneColor(cell.tone), size = 4)
        }
        Row(verticalAlignment = Alignment.Vertical.Bottom) {
            Text(cell.headline, style = mono(16.sp, toneColor(cell.tone), bold = true), maxLines = 1)
            Spacer(GlanceModifier.width(3.dp))
            Text(cell.unit, style = mono(6.5.sp, Gray), maxLines = 1)
        }
        Text(
            if (cell.tone == Tone.NORMAL) cell.window else cell.status,
            style = mono(6.5.sp, if (cell.tone == Tone.ALERT) NothingRed else Dim),
            maxLines = 1
        )
    }
}

@Composable
private fun Footer(view: QuotaPulseView) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Text(view.updatedLabel, style = mono(7.sp, Dim), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        if (view.liveActive || view.interruptReason != null) {
            Dot(NothingRed, size = 4)
            Spacer(GlanceModifier.width(4.dp))
        }
        Text(
            view.modeLabel,
            style = mono(7.sp, if (view.liveActive || view.interruptReason != null) NothingRed else Gray, bold = view.liveActive),
            maxLines = 1
        )
    }
}

/** ~4×3+: detailed rows plus SYNC NOW and START/STOP LIVE. */
@Composable
private fun LargeLayout(view: QuotaPulseView) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Header(view, showSync = false)
        Spacer(GlanceModifier.height(4.dp))
        Divider()
        Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            DetailRow(view.cells[0])
            Divider()
            DetailRow(view.cells[1])
            Divider()
            DetailRow(view.cells[2])
            Divider()
            DetailRow(view.cells[3])
        }
        Divider()
        Spacer(GlanceModifier.height(6.dp))
        LargeFooter(view)
    }
}

@Composable
private fun ColumnScope.DetailRow(cell: ProviderCell) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxWidth().defaultWeight().padding(vertical = 3.dp)
            .clickable(openProvider(context, cell.provider)),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text(cell.provider.label, style = mono(8.sp, White, bold = true), maxLines = 1)
                Spacer(GlanceModifier.width(5.dp))
                Dot(toneColor(cell.tone), size = 4)
            }
            Text(cell.window, style = mono(6.5.sp, Gray), maxLines = 1)
            Text(
                listOfNotNull(cell.sourceLabel, cell.status).joinToString(" · "),
                style = mono(6.5.sp, if (cell.tone == Tone.ALERT) NothingRed else Dim),
                maxLines = 1
            )
        }
        Column(horizontalAlignment = Alignment.Horizontal.End) {
            Text(cell.headline, style = mono(17.sp, toneColor(cell.tone), bold = true), maxLines = 1)
            Text(cell.unit, style = mono(6.5.sp, Gray), maxLines = 1)
        }
    }
}

@Composable
private fun LargeFooter(view: QuotaPulseView) {
    val context = LocalContext.current
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(view.updatedLabel, style = mono(7.sp, Dim), maxLines = 1)
            Text(
                view.interruptReason ?: view.modeLabel,
                style = mono(7.sp, if (view.liveActive || view.interruptReason != null) NothingRed else Gray),
                maxLines = 1
            )
        }
        Button(
            label = if (view.syncing) "SYNCING…" else "SYNC NOW",
            fill = ButtonFill,
            text = if (view.syncing) Dim else White,
            action = actionRunCallback<QuotaPulseSyncAction>()
        )
        Spacer(GlanceModifier.width(6.dp))
        if (view.liveActive) {
            Button("STOP LIVE", NothingRed, White, actionSendBroadcast(QuotaPulseControlReceiver.stopIntent(context)))
        } else {
            Button(
                "START LIVE", ButtonFill, Gray,
                actionStartService(LiveMonitorService.startIntent(context), isForegroundService = true)
            )
        }
    }
}

@Composable
private fun Button(label: String, fill: Color, text: Color, action: Action) {
    Box(
        modifier = GlanceModifier
            .background(fill)
            .cornerRadius(10.dp)
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .clickable(action),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = mono(7.5.sp, text, bold = true, align = TextAlign.Center), maxLines = 1)
    }
}

private fun mono(size: TextUnit, color: Color, bold: Boolean = false, align: TextAlign? = null) = TextStyle(
    color = ColorProvider(color),
    fontSize = size,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    fontFamily = FontFamily.Monospace,
    textAlign = align
)
