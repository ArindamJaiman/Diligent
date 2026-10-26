package dev.diligent.app.versus

import android.content.Context
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.leetcode.domain.LeetCodeDay
import dev.diligent.app.tools.ToolButton
import dev.diligent.app.tools.ToolCard
import dev.diligent.app.tools.ToolColors
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.tools.ToolJob
import dev.diligent.app.tools.ToolJobs
import dev.diligent.app.tools.ToolNote
import dev.diligent.app.tools.ToolRoutes
import dev.diligent.app.tools.ToolScaffold
import dev.diligent.app.tools.ToolSection
import dev.diligent.app.tools.ToolTextDialog
import dev.diligent.app.tools.ToolTimeDialog
import dev.diligent.app.tools.ToolToggle
import dev.diligent.app.tools.ToolsEntryPoint
import dev.diligent.app.tools.WChip
import dev.diligent.app.tools.WDivider
import dev.diligent.app.tools.WDot
import dev.diligent.app.tools.WidgetPalette
import dev.diligent.app.tools.openToolAction
import dev.diligent.app.tools.rememberNotificationAsker
import dev.diligent.app.tools.runAction
import dev.diligent.app.tools.toolLabel
import dev.diligent.app.tools.wText
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.widget.widgetBackground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.glance.text.Text as GText

// Home-screen widget (Glance only: no Compose UI imports in this file).

class VersusWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val sync = EntryPointAccessors.fromApplication(context.applicationContext, ToolsEntryPoint::class.java).versusSync()
        val now = System.currentTimeMillis()
        val state = sync.current()
        val rows = sync.board(now)
        // Redraw at the UTC reset, when everyone's "today" goes back to zero.
        dev.diligent.app.tools.WidgetRedraw.schedule(context, ToolRoutes.VERSUS, 3510, if (rows.isEmpty()) null else LeetCodeDay.nextResetMillis(now))
        provideContent { VersusContent(state, rows) }
    }
}

class VersusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = VersusWidget()
}

class VersusSyncAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ToolJobs.runNow(context, ToolJob.VERSUS_SYNC)
    }
}

object VersusWidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun refresh(context: Context) {
        try {
            VersusWidget().updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("VersusWidget", "refresh failed", e)
        }
    }

    fun refreshAsync(context: Context) {
        scope.launch { refresh(context.applicationContext) }
    }
}

@androidx.compose.runtime.Composable
private fun VersusContent(state: VersusState, rows: List<VersusRow>) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier.fillMaxSize().background(widgetBackground(WidgetPalette.Background)).cornerRadius(18.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clickable(openToolAction(context, ToolRoutes.VERSUS))
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = androidx.glance.layout.Alignment.Vertical.CenterVertically) {
            GText("VERSUS", style = wText(9.sp, WidgetPalette.Accent, bold = true))
            Spacer(GlanceModifier.width(6.dp))
            GText("TODAY", style = wText(7.sp, WidgetPalette.Gray), modifier = GlanceModifier.defaultWeight())
            WChip(if (state.syncing) "SYNC…" else "SYNC", runAction<VersusSyncAction>(), dim = state.syncing)
        }
        Spacer(GlanceModifier.height(3.dp))
        WDivider()
        Spacer(GlanceModifier.height(4.dp))
        when {
            state.you.isBlank() -> GText("Set up LeetCode in the app", style = wText(8.sp, WidgetPalette.Gray))
            state.friends.isEmpty() -> GText("Add friends in Versus", style = wText(8.sp, WidgetPalette.Gray))
            rows.isEmpty() -> GText(state.error ?: "Tap SYNC", style = wText(8.sp, WidgetPalette.Gray))
            else -> Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                rows.take(4).forEachIndexed { i, row -> WidgetRow(i + 1, row) }
            }
        }
        GText(
            state.cache?.let { "SYNCED " + ToolFormat.clock(it.fetchedAt) } ?: "NOT SYNCED",
            style = wText(6.5.sp, WidgetPalette.Dim), maxLines = 1
        )
    }
}

@androidx.compose.runtime.Composable
private fun WidgetRow(rank: Int, row: VersusRow) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = androidx.glance.layout.Alignment.Vertical.CenterVertically
    ) {
        GText("$rank", style = wText(8.sp, WidgetPalette.Dim, bold = true))
        Spacer(GlanceModifier.width(5.dp))
        WDot(if (row.solvedToday > 0) WidgetPalette.Good else if (row.activeToday) WidgetPalette.Warn else WidgetPalette.Dim, 4)
        Spacer(GlanceModifier.width(4.dp))
        GText(
            row.username.uppercase(),
            style = wText(8.sp, if (row.isYou) WidgetPalette.Accent else WidgetPalette.White, bold = row.isYou),
            maxLines = 1, modifier = GlanceModifier.defaultWeight()
        )
        GText("🔥${row.streak}", style = wText(7.sp, WidgetPalette.Gray))
        Spacer(GlanceModifier.width(6.dp))
        GText("${row.solvedToday}", style = wText(10.sp, WidgetPalette.White, bold = true))
    }
}
