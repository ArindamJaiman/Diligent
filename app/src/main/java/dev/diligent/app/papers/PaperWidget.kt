package dev.diligent.app.papers

import android.content.Context
import android.util.Log
import androidx.compose.runtime.*
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
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.repository.DiligentRepository
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
import dev.diligent.app.tools.ToolTimeDialog
import dev.diligent.app.tools.ToolToggle
import dev.diligent.app.tools.ToolsEntryPoint
import dev.diligent.app.tools.WChip
import dev.diligent.app.tools.WDivider
import dev.diligent.app.tools.WidgetPalette
import dev.diligent.app.tools.openLinkAction
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.glance.text.Text as GText

// Home-screen widget (Glance only: no Compose UI imports in this file).

class PaperWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = EntryPointAccessors.fromApplication(context.applicationContext, ToolsEntryPoint::class.java).paperService().current()
        provideContent { PaperContent(state) }
    }
}

class PaperWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PaperWidget()
}

class PaperSaveAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val service = EntryPointAccessors.fromApplication(context.applicationContext, ToolsEntryPoint::class.java).paperService()
        service.current().today?.let { service.save(it) }
        PaperWidgetUpdater.refresh(context)
    }
}

class PaperSyncAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ToolJobs.runNow(context, ToolJob.PAPERS_SYNC)
    }
}

object PaperWidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun refresh(context: Context) {
        try {
            PaperWidget().updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("PaperWidget", "refresh failed", e)
        }
    }

    fun refreshAsync(context: Context) {
        scope.launch { refresh(context.applicationContext) }
    }
}

@androidx.compose.runtime.Composable
private fun PaperContent(state: PaperState) {
    val context = LocalContext.current
    val paper = state.today
    Column(
        modifier = GlanceModifier.fillMaxSize().background(WidgetPalette.Background).cornerRadius(18.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = androidx.glance.layout.Alignment.Vertical.CenterVertically) {
            GText(
                "PAPER OF THE DAY",
                style = wText(9.sp, WidgetPalette.White, bold = true),
                modifier = GlanceModifier.defaultWeight().clickable(openToolAction(context, ToolRoutes.PAPERS))
            )
            if (paper == null) {
                WChip(if (state.syncing) "SYNC…" else "SYNC", runAction<PaperSyncAction>(), dim = state.syncing)
            } else {
                val saved = state.store.queue.any { it.id == paper.id }
                WChip(if (saved) "SAVED" else "SAVE", runAction<PaperSaveAction>(), dim = saved)
            }
        }
        Spacer(GlanceModifier.height(3.dp))
        WDivider()
        Spacer(GlanceModifier.height(5.dp))
        if (paper == null) {
            GText(state.error ?: "Tap SYNC to load today's arXiv feed", style = wText(8.sp, WidgetPalette.Gray))
        } else {
            Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight().clickable(openLinkAction(context, paper.url))) {
                GText(paper.primaryCategory.uppercase(), style = wText(7.sp, WidgetPalette.Accent, bold = true))
                GText(paper.title, style = wText(10.sp, WidgetPalette.White, bold = true), maxLines = 3)
                Spacer(GlanceModifier.height(3.dp))
                GText(paper.authors, style = wText(7.sp, WidgetPalette.Gray), maxLines = 1)
            }
            GText(
                if (state.store.queue.isEmpty()) "TAP TO READ ON ARXIV" else "QUEUE: ${state.store.queue.size}",
                style = wText(6.5.sp, WidgetPalette.Dim)
            )
        }
    }
}
