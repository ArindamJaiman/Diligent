package dev.diligent.app.top3

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import dev.diligent.app.widget.WidgetUpdater
import dev.diligent.app.widget.updateAllExcept
import dev.diligent.app.widget.widgetBackground
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
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
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.data.repository.DiligentRepository
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
import dev.diligent.app.tools.ToolTimeDialog
import dev.diligent.app.tools.ToolToggle
import dev.diligent.app.tools.ToolsEntryPoint
import dev.diligent.app.tools.WChip
import dev.diligent.app.tools.WDivider
import dev.diligent.app.tools.WDot
import dev.diligent.app.tools.WidgetPalette
import dev.diligent.app.tools.openToolAction
import dev.diligent.app.tools.rememberNotificationAsker
import dev.diligent.app.tools.toolLabel
import dev.diligent.app.tools.wText
import dev.diligent.app.ui.theme.DiligentColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import androidx.glance.text.Text as GText

// Home-screen widget (Glance only: no Compose UI imports in this file).

private val ActivityKey = ActionParameters.Key<Long>("top3_activity")

class Top3Widget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val service = EntryPointAccessors.fromApplication(context.applicationContext, ToolsEntryPoint::class.java).top3Service()
        val picks = service.picks()
        val today = LocalDate.now()
        val busy = CalendarBusy.read(context, today)?.let { CalendarBusy.summarize(it, System.currentTimeMillis(), today, ZoneId.systemDefault()) }
        provideContent { Top3Content(picks, busy) }
    }
}

class Top3WidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = Top3Widget()
}

class Top3StepAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[ActivityKey] ?: return
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, ToolsEntryPoint::class.java)
        val activity = entry.repository().getActivityById(id) ?: return
        entry.repository().incrementProgress(id, Top3Service.step(activity.unit), refreshWidgets = false)
        // This widget first and once; the tracker widgets follow.
        WidgetUpdater.afterProgressTap(context, Top3Widget(), glanceId)
    }
}

object Top3WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun refresh(context: Context, except: GlanceId? = null) {
        try {
            Top3Widget().updateAllExcept(context, except)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Top3Widget", "refresh failed", e)
        }
    }

    fun refreshAsync(context: Context) {
        scope.launch { refresh(context.applicationContext) }
    }
}

@androidx.compose.runtime.Composable
private fun Top3Content(picks: List<Top3Pick>, busy: BusySummary?) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier.fillMaxSize().background(widgetBackground(WidgetPalette.Background)).cornerRadius(18.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clickable(openToolAction(context, ToolRoutes.TOP3))
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = androidx.glance.layout.Alignment.Vertical.CenterVertically) {
            GText("TODAY'S TOP 3", style = wText(9.sp, WidgetPalette.White, bold = true), modifier = GlanceModifier.defaultWeight())
            if (picks.isNotEmpty()) {
                GText(
                    "${picks.count { it.done }}/${picks.size}",
                    style = wText(9.sp, if (picks.all { it.done }) WidgetPalette.Good else WidgetPalette.Gray, bold = true)
                )
            }
        }
        Spacer(GlanceModifier.height(3.dp))
        WDivider()
        Spacer(GlanceModifier.height(4.dp))
        if (picks.isEmpty()) {
            GText("Tap to pick today's three must-dos", style = wText(8.sp, WidgetPalette.Gray))
        }
        Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            picks.forEach { p ->
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = androidx.glance.layout.Alignment.Vertical.CenterVertically
                ) {
                    WDot(if (p.done) WidgetPalette.Good else WidgetPalette.Dim, 5)
                    Spacer(GlanceModifier.width(6.dp))
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        GText(p.activity.name.uppercase(), style = wText(8.sp, if (p.done) WidgetPalette.Good else WidgetPalette.White, bold = true), maxLines = 1)
                        GText(
                            "${ToolFormat.amount(p.progress, p.activity.unit)} / ${ToolFormat.amount(p.activity.dailyGoal, p.activity.unit)}",
                            style = wText(7.sp, WidgetPalette.Gray), maxLines = 1
                        )
                    }
                    if (!p.done) {
                        WChip("+", actionRunCallback<Top3StepAction>(actionParametersOf(ActivityKey to p.activity.id)))
                    }
                }
            }
        }
        if (busy != null) {
            GText(
                "CAL ${ToolFormat.duration(busy.busyMillis)} BUSY · ${ToolFormat.duration(busy.freeLeftMillis)} FREE" +
                    (busy.next?.let { " · NEXT ${ToolFormat.clock(it.begin)}" } ?: ""),
                style = wText(6.5.sp, WidgetPalette.Dim), maxLines = 1
            )
        }
    }
}
