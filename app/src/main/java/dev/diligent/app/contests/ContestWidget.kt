package dev.diligent.app.contests

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
import dev.diligent.app.tools.ToolButton
import dev.diligent.app.tools.ToolCard
import dev.diligent.app.tools.ToolChip
import dev.diligent.app.tools.ToolColors
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.tools.ToolJob
import dev.diligent.app.tools.ToolJobs
import dev.diligent.app.tools.ToolNote
import dev.diligent.app.tools.ToolRoutes
import dev.diligent.app.tools.ToolScaffold
import dev.diligent.app.tools.ToolSection
import dev.diligent.app.tools.ToolTextDialog
import dev.diligent.app.tools.ToolToggle
import dev.diligent.app.tools.ToolsEntryPoint
import dev.diligent.app.tools.WChip
import dev.diligent.app.tools.WDivider
import dev.diligent.app.tools.WidgetPalette
import dev.diligent.app.tools.WidgetRedraw
import dev.diligent.app.tools.openLinkAction
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import androidx.glance.text.Text as GText

// Home-screen widget (Glance only: no Compose UI imports in this file).

class ContestWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val radar = EntryPointAccessors.fromApplication(context.applicationContext, ToolsEntryPoint::class.java).contestRadar()
        val now = System.currentTimeMillis()
        val contests = radar.contests(now)
        val ratings = radar.ratings()
        val next = contests.take(4).flatMap { listOf(it.startMillis, it.endMillis) }.filter { it > now }.minOrNull()
        WidgetRedraw.schedule(context, ToolRoutes.CONTESTS, 3610, next)
        provideContent { ContestContent(contests.take(4), ratings, radar.current().syncing, now) }
    }
}

class ContestWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ContestWidget()
}

class ContestSyncAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ToolJobs.runNow(context, ToolJob.CONTESTS_SYNC)
    }
}

object ContestWidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun refresh(context: Context) {
        try {
            ContestWidget().updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ContestWidget", "refresh failed", e)
        }
    }

    fun refreshAsync(context: Context) {
        scope.launch { refresh(context.applicationContext) }
    }
}

@androidx.compose.runtime.Composable
private fun ContestContent(contests: List<RadarContest>, ratings: List<RadarRating>, syncing: Boolean, now: Long) {
    val context = LocalContext.current
    val day = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())
    Column(
        modifier = GlanceModifier.fillMaxSize().background(widgetBackground(WidgetPalette.Background)).cornerRadius(18.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clickable(openToolAction(context, ToolRoutes.CONTESTS))
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = androidx.glance.layout.Alignment.Vertical.CenterVertically) {
            GText("CONTEST RADAR", style = wText(9.sp, WidgetPalette.White, bold = true), modifier = GlanceModifier.defaultWeight())
            WChip(if (syncing) "SYNC…" else "SYNC", runAction<ContestSyncAction>(), dim = syncing)
        }
        Spacer(GlanceModifier.height(3.dp))
        WDivider()
        Spacer(GlanceModifier.height(4.dp))
        if (contests.isEmpty()) {
            GText("No upcoming contests cached — tap SYNC", style = wText(8.sp, WidgetPalette.Gray))
        }
        Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            contests.forEach { c ->
                val live = c.startMillis <= now
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).clickable(openLinkAction(context, c.url)),
                    verticalAlignment = androidx.glance.layout.Alignment.Vertical.CenterVertically
                ) {
                    GText(c.platform.short, style = wText(7.5.sp, androidx.compose.ui.graphics.Color(c.platform.color.toInt()), bold = true))
                    Spacer(GlanceModifier.width(6.dp))
                    GText(c.name, style = wText(7.5.sp, WidgetPalette.White), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(4.dp))
                    GText(
                        if (live) "LIVE" else Instant.ofEpochMilli(c.startMillis).atZone(ZoneId.systemDefault()).format(day).uppercase(),
                        style = wText(7.5.sp, if (live) WidgetPalette.Alert else WidgetPalette.Gray, bold = live)
                    )
                }
            }
        }
        if (ratings.isNotEmpty()) {
            GText(
                ratings.joinToString("  ") { "${it.platform.short} ${it.rating ?: "—"}" },
                style = wText(7.sp, WidgetPalette.Accent), maxLines = 1
            )
        }
    }
}
