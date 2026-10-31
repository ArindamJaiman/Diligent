package dev.diligent.app.now

import android.content.Context
import android.util.Log
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
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
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.focus.FocusTimer
import dev.diligent.app.focus.domain.FocusMath
import dev.diligent.app.planning.PlanService
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.tools.ToolRoutes
import dev.diligent.app.tools.WChip
import dev.diligent.app.tools.WDivider
import dev.diligent.app.tools.WDot
import dev.diligent.app.tools.WidgetPalette
import dev.diligent.app.tools.openToolAction
import dev.diligent.app.tools.wText
import dev.diligent.app.top3.Top3Service
import dev.diligent.app.tracking.Privacy
import dev.diligent.app.widget.updateAllExcept
import dev.diligent.app.widget.widgetBackground
import kotlinx.coroutines.CancellationException
import java.time.LocalTime
import androidx.glance.text.Text as GText

// Home-screen widget (Glance only: no Compose UI imports in this file).

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NowEntryPoint {
    fun repository(): DiligentRepository
    fun focusTimer(): FocusTimer
    fun top3(): Top3Service
    fun plans(): PlanService
}

/** What the widget shows right now. */
sealed interface NowCard {
    data class Focus(val name: String, val since: Long, val until: Long, val intention: String) : NowCard
    data object PickTop3 : NowCard
    data class Shutdown(val done: Int, val open: Int) : NowCard
    data class Next(val name: String, val at: Long, val minutes: Int) : NowCard
    data class Open(val items: List<Triple<Long, String, String>>, val done: Int, val total: Int) : NowCard
    data object AllDone : NowCard
}

/**
 * Picks the card (iOS Smart Stack): a running timer first; in the morning, picking the Top 3;
 * after the shutdown time, closing the day; otherwise the next planned block or what's open.
 */
object NowLogic {
    fun pick(
        hour: Int,
        focus: NowCard.Focus?,
        top3Picked: Boolean,
        shutdownHour: Int,
        shutdownDone: Boolean,
        next: NowCard.Next?,
        open: NowCard.Open
    ): NowCard = when {
        focus != null -> focus
        hour in 5..10 && !top3Picked && open.total > 0 -> NowCard.PickTop3
        hour >= shutdownHour && !shutdownDone -> NowCard.Shutdown(open.done, open.items.size)
        next != null -> next
        open.items.isEmpty() && open.total > 0 -> NowCard.AllDone
        else -> open
    }
}

private val ActivityKey = ActionParameters.Key<Long>("now_activity")

class NowWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, NowEntryPoint::class.java)
        val repository = entry.repository()
        val now = System.currentTimeMillis()
        val session = entry.focusTimer().current().session
        val focus = session?.let {
            NowCard.Focus(
                Privacy.publicName(it.activityName, repository.getActivityById(it.activityId)?.isPrivate == true),
                it.startedAt,
                if (it.targetMinutes > 0) it.startedAt + it.targetMinutes * FocusMath.MINUTE else 0L,
                it.intention
            )
        }
        val active = repository.getAllActiveSnapshot()
        val rows = repository.getTodayProgressSnapshot().associateBy { it.activityId }
        val openList = repository.openToday()
        val open = NowCard.Open(
            items = openList.take(3).map { a ->
                Triple(a.id, Privacy.publicName(a), "${ToolFormat.amount(rows[a.id]?.progress ?: 0f, a.unit)} / ${ToolFormat.amount(a.dailyGoal, a.unit)}")
            },
            done = active.count { rows[it.id]?.isCompleted == true },
            total = active.size
        )
        val plans = entry.plans()
        val nextBlock = plans.today().blocks.firstOrNull { it.end > now }
        val next = nextBlock?.let { b -> active.firstOrNull { it.id == b.activityId }?.let { NowCard.Next(Privacy.publicName(it), b.start, ((b.end - b.start) / 60_000).toInt()) } }
        val card = NowLogic.pick(
            hour = LocalTime.now().hour,
            focus = focus,
            top3Picked = entry.top3().todayIds().isNotEmpty(),
            shutdownHour = plans.settings.value.shutdownHour,
            shutdownDone = repository.getDayLog().shutdownAt > 0L,
            next = next,
            open = open
        )
        provideContent { NowContent(card) }
    }
}

class NowWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowWidget()
}

/** + on an open activity: a half hour, 15 minutes or one unit. */
class NowStepAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[ActivityKey] ?: return
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, NowEntryPoint::class.java)
        val activity = entry.repository().getActivityById(id) ?: return
        entry.repository().incrementProgress(id, Top3Service.step(activity.unit))
    }
}

object NowWidgetUpdater {
    suspend fun refresh(context: Context, except: GlanceId? = null) {
        try {
            NowWidget().updateAllExcept(context, except)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("NowWidget", "refresh failed", e)
        }
    }
}

@androidx.compose.runtime.Composable
private fun NowContent(card: NowCard) {
    val context = LocalContext.current
    val tap = when (card) {
        is NowCard.PickTop3 -> openToolAction(context, ToolRoutes.TOP3)
        is NowCard.Shutdown -> openToolAction(context, ToolRoutes.SHUTDOWN)
        is NowCard.Next -> openToolAction(context, ToolRoutes.PLAN)
        is NowCard.Focus -> openToolAction(context, ToolRoutes.FOCUS)
        else -> openToolAction(context, ToolRoutes.HUB)
    }
    Column(
        modifier = GlanceModifier.fillMaxSize().background(widgetBackground(WidgetPalette.Background)).cornerRadius(18.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clickable(tap)
    ) {
        val (label, color) = when (card) {
            is NowCard.Focus -> "NOW · FOCUS" to WidgetPalette.Good
            is NowCard.PickTop3 -> "THIS MORNING" to WidgetPalette.White
            is NowCard.Shutdown -> "THIS EVENING" to WidgetPalette.White
            is NowCard.Next -> "NEXT UP" to WidgetPalette.White
            is NowCard.AllDone -> "TODAY" to WidgetPalette.Good
            is NowCard.Open -> "STILL OPEN · ${card.done}/${card.total} DONE" to WidgetPalette.White
        }
        GText(label, style = wText(9.sp, color, bold = true))
        Spacer(GlanceModifier.height(3.dp))
        WDivider()
        Spacer(GlanceModifier.height(6.dp))
        when (card) {
            is NowCard.Focus -> {
                GText(card.name.uppercase(), style = wText(13.sp, WidgetPalette.White, bold = true), maxLines = 1)
                GText(
                    "since ${ToolFormat.clock(card.since)}" + (if (card.until > 0) " · until ${ToolFormat.clock(card.until)}" else ""),
                    style = wText(8.sp, WidgetPalette.Gray)
                )
                if (card.intention.isNotBlank()) GText("“${card.intention}”", style = wText(8.sp, WidgetPalette.Gray), maxLines = 2)
            }
            is NowCard.PickTop3 -> {
                GText("Pick today's three must-dos", style = wText(12.sp, WidgetPalette.White, bold = true))
                GText("tap to choose · or let Plan schedule them", style = wText(8.sp, WidgetPalette.Gray))
            }
            is NowCard.Shutdown -> {
                GText("Shut down the day", style = wText(12.sp, WidgetPalette.White, bold = true))
                GText("${card.done} done · ${card.open} open · rate it, pick tomorrow", style = wText(8.sp, WidgetPalette.Gray))
            }
            is NowCard.Next -> {
                GText(card.name.uppercase(), style = wText(13.sp, WidgetPalette.White, bold = true), maxLines = 1)
                GText("${ToolFormat.clock(card.at)} · ${card.minutes} min block", style = wText(8.sp, WidgetPalette.Gray))
            }
            is NowCard.AllDone -> GText("Everything's done ✓", style = wText(12.sp, WidgetPalette.Good, bold = true))
            is NowCard.Open -> card.items.forEach { (id, name, line) ->
                Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = androidx.glance.layout.Alignment.Vertical.CenterVertically) {
                    WDot(WidgetPalette.Dim, 5)
                    Spacer(GlanceModifier.width(6.dp))
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        GText(name.uppercase(), style = wText(8.sp, WidgetPalette.White, bold = true), maxLines = 1)
                        GText(line, style = wText(7.sp, WidgetPalette.Gray), maxLines = 1)
                    }
                    WChip("+", actionRunCallback<NowStepAction>(actionParametersOf(ActivityKey to id)))
                }
            }
        }
    }
}
