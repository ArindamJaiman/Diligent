package dev.diligent.app.screentime

import android.content.Context
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext as GlanceLocalContext
import androidx.glance.LocalSize
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
import androidx.glance.layout.Box
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
import dev.diligent.app.tools.ToolColors
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.tools.ToolJob
import dev.diligent.app.tools.ToolJobs
import dev.diligent.app.tools.ToolNote
import dev.diligent.app.tools.ToolRoutes
import dev.diligent.app.tools.ToolScaffold
import dev.diligent.app.tools.ToolSection
import dev.diligent.app.tools.ToolStat
import dev.diligent.app.tools.ToolTimeDialog
import dev.diligent.app.tools.ToolToggle
import dev.diligent.app.tools.ToolsEntryPoint
import dev.diligent.app.tools.WChip
import dev.diligent.app.tools.WidgetPalette
import dev.diligent.app.tools.openToolAction
import dev.diligent.app.tools.rememberNotificationAsker
import dev.diligent.app.tools.runAction
import dev.diligent.app.tools.toolLabel
import dev.diligent.app.tools.wText
import dev.diligent.app.ui.theme.DiligentColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import androidx.glance.text.Text as GText

// Home-screen widget (Glance only: no Compose UI imports in this file).

class ScreenTimeWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val service = EntryPointAccessors.fromApplication(context.applicationContext, ToolsEntryPoint::class.java).screenTimeService()
        val summary = service.today()
        provideContent { ScreenContent(summary) }
    }
}

class ScreenTimeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScreenTimeWidget()
}

/** Recomputes from local data only — no network. */
class ScreenTimeRefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ScreenTimeWidgetUpdater.refresh(context)
    }
}

object ScreenTimeWidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun refresh(context: Context) {
        try {
            ScreenTimeWidget().updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ScreenTimeWidget", "refresh failed", e)
        }
    }

    fun refreshAsync(context: Context) {
        scope.launch { refresh(context.applicationContext) }
    }
}

@androidx.compose.runtime.Composable
private fun ScreenContent(s: ScreenSummary) {
    val context = GlanceLocalContext.current
    val size = LocalSize.current
    val barWidth = (size.width.value - 24f).coerceAtLeast(40f)
    Column(
        modifier = GlanceModifier.fillMaxSize().background(WidgetPalette.Background).cornerRadius(18.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clickable(openToolAction(context, ToolRoutes.SCREEN_TIME))
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = androidx.glance.layout.Alignment.Vertical.CenterVertically) {
            GText("FOCUS vs SCROLL", style = wText(9.sp, WidgetPalette.White, bold = true), modifier = GlanceModifier.defaultWeight())
            WChip("↻", runAction<ScreenTimeRefreshAction>())
        }
        Spacer(GlanceModifier.defaultWeight())
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                GText(ToolFormat.duration(s.focusMillis), style = wText(18.sp, WidgetPalette.Good, bold = true))
                GText("FOCUS", style = wText(7.sp, WidgetPalette.Gray))
            }
            Column(modifier = GlanceModifier.defaultWeight()) {
                GText(if (s.allowed) ToolFormat.duration(s.distractMillis) else "—", style = wText(18.sp, if (s.distractMillis > s.focusMillis) WidgetPalette.Alert else WidgetPalette.White, bold = true))
                GText(if (s.allowed) "SCROLL" else "ALLOW ACCESS", style = wText(7.sp, WidgetPalette.Gray))
            }
        }
        Spacer(GlanceModifier.height(6.dp))
        val total = s.focusMillis + s.distractMillis
        Row(modifier = GlanceModifier.fillMaxWidth().height(6.dp).cornerRadius(3.dp).background(WidgetPalette.Line)) {
            if (total > 0) {
                val focusW = (barWidth * s.focusShare).coerceIn(0f, barWidth)
                if (focusW > 0f) Box(modifier = GlanceModifier.width(focusW.dp).height(6.dp).background(WidgetPalette.Good)) {}
                if (barWidth - focusW > 0f) Box(modifier = GlanceModifier.width((barWidth - focusW).dp).height(6.dp).background(WidgetPalette.Alert)) {}
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        GText("AS OF ${ToolFormat.clock(s.at)}", style = wText(6.5.sp, WidgetPalette.Dim))
    }
}
