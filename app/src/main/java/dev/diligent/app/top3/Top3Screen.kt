package dev.diligent.app.top3

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
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
import dev.diligent.app.tracking.DayClock
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

data class Top3Ui(
    val picks: List<Top3Pick>,
    val activities: List<Activity>,
    val order: List<Long>,
    val settings: Top3Settings,
    val busy: BusySummary?,
    val calendarAllowed: Boolean
)

@HiltViewModel
class Top3ViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: Top3Service,
    private val repository: DiligentRepository
) : ViewModel() {

    private val calendarTick = MutableStateFlow(0)
    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    val ui: StateFlow<Top3Ui?> = combine(
        service.state,
        repository.getAllActiveActivities(),
        repository.getTodayProgress(),
        calendarTick,
        ticker
    ) { state, activities, progress, _, now ->
        val today = DayClock.today()
        val byId = progress.associateBy(DailyProgress::activityId)
        val ids = if (state.date == today) state.ids else emptyList()
        val events = CalendarBusy.read(context, today)
        Top3Ui(
            picks = ids.mapNotNull { id -> activities.firstOrNull { it.id == id }?.let { Top3Service.pick(it, byId[id]) } },
            activities = activities,
            order = service.suggestedOrder(activities, today),
            settings = state.settings,
            busy = events?.let { CalendarBusy.summarize(it, now, today, ZoneId.systemDefault()) },
            calendarAllowed = events != null
        )
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setPicks(ids: List<Long>) = service.setPicks(ids)

    fun step(pick: Top3Pick) = viewModelScope.launch {
        repository.incrementProgress(pick.activity.id, Top3Service.step(pick.activity.unit))
    }

    fun calendarChanged() {
        calendarTick.value++
        Top3WidgetUpdater.refreshAsync(context)
    }

    fun update(transform: (Top3Settings) -> Top3Settings) {
        service.updateSettings(transform)
        ToolJobs.reschedule(context, ToolJob.TOP3_PROMPT)
    }
}

@Composable
fun Top3Screen(onBack: () -> Unit, viewModel: Top3ViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val askNotifications = rememberNotificationAsker()
    val calendarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.calendarChanged() }

    ToolScaffold(title = "Today's Top 3", onBack = onBack) {
        val state = ui ?: return@ToolScaffold

        if (pickTime) {
            ToolTimeDialog("Morning prompt", state.settings.hour, state.settings.minute, onDismiss = { pickTime = false }) { h, m ->
                viewModel.update { it.copy(hour = h, minute = m) }
                pickTime = false
            }
        }

        if (state.picks.isEmpty() || editing) {
            var selected by remember(state.picks) { mutableStateOf(state.picks.map { it.activity.id }) }
            ToolCard {
                Text("PICK UP TO ${Top3Service.MAX}", style = toolLabel(10.sp), color = DiligentColors.Gray500)
                Text("What would make today a win?", style = toolLabel(13.sp, bold = true), color = DiligentColors.White)
                Spacer(Modifier.height(6.dp))
                val ordered = state.order.mapNotNull { id -> state.activities.firstOrNull { it.id == id } }
                ordered.forEach { a ->
                    val checked = a.id in selected
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected = if (checked) selected - a.id else if (selected.size < Top3Service.MAX) selected + a.id else selected
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(checkedColor = DiligentColors.White, checkmarkColor = DiligentColors.Black, uncheckedColor = DiligentColors.Gray500)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(a.name, style = toolLabel(12.sp), color = DiligentColors.White, modifier = Modifier.weight(1f))
                        Text("goal ${ToolFormat.amount(a.dailyGoal, a.unit)}", style = toolLabel(10.sp), color = DiligentColors.Gray600)
                    }
                }
                Spacer(Modifier.height(10.dp))
                ToolButton(
                    if (selected.isEmpty()) "PICK AT LEAST ONE" else "SET TODAY'S TOP ${selected.size}",
                    Modifier.fillMaxWidth(),
                    enabled = selected.isNotEmpty()
                ) {
                    viewModel.setPicks(selected)
                    editing = false
                }
            }
        } else {
            ToolCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${state.picks.count { it.done }}/${state.picks.size} DONE",
                        style = toolLabel(12.sp, bold = true),
                        color = if (state.picks.all { it.done }) ToolColors.Good else DiligentColors.White,
                        modifier = Modifier.weight(1f)
                    )
                    Text("EDIT", style = toolLabel(10.sp, bold = true), color = DiligentColors.Gray600, modifier = Modifier.clickable { editing = true })
                }
                state.picks.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(color = DiligentColors.Border)
                    Column(Modifier.padding(vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", style = toolLabel(16.sp, bold = true), color = DiligentColors.Gray600, modifier = Modifier.width(24.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.activity.name, style = toolLabel(13.sp, bold = true), color = if (p.done) ToolColors.Good else DiligentColors.White)
                                Text(
                                    "${ToolFormat.amount(p.progress, p.activity.unit)} / ${ToolFormat.amount(p.activity.dailyGoal, p.activity.unit)}",
                                    style = toolLabel(10.sp), color = DiligentColors.Gray600
                                )
                            }
                            ToolButton("+${ToolFormat.amount(Top3Service.step(p.activity.unit), "").trim()}") { viewModel.step(p) }
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { if (p.activity.dailyGoal > 0) (p.progress / p.activity.dailyGoal).coerceIn(0f, 1f) else 0f },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = if (p.done) ToolColors.Good else DiligentColors.White,
                            trackColor = DiligentColors.Gray300,
                            drawStopIndicator = {}
                        )
                    }
                }
            }
        }

        ToolSection("CALENDAR")
        ToolCard {
            val busy = state.busy
            if (!state.calendarAllowed) {
                Text("Show today's busy time next to your Top 3", style = toolLabel(12.sp, bold = true), color = DiligentColors.White)
                Text("Reads your phone's calendars on this device only.", style = toolLabel(10.sp), color = DiligentColors.Gray600)
                Spacer(Modifier.height(8.dp))
                ToolButton("ALLOW CALENDAR", Modifier.fillMaxWidth()) { calendarLauncher.launch(Manifest.permission.READ_CALENDAR) }
            } else if (busy != null) {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(ToolFormat.duration(busy.busyMillis), style = toolLabel(20.sp, bold = true), color = DiligentColors.White)
                        Text("BUSY TODAY · ${busy.events} EVENTS", style = toolLabel(9.sp), color = DiligentColors.Gray600)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(ToolFormat.duration(busy.freeLeftMillis), style = toolLabel(20.sp, bold = true), color = ToolColors.Good)
                        Text("FREE UNTIL ${CalendarBusy.DAY_END}", style = toolLabel(9.sp), color = DiligentColors.Gray600)
                    }
                }
                (busy.current ?: busy.next)?.let { e ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        (if (busy.current != null) "NOW: " else "NEXT: ") + "${e.title} · ${ToolFormat.clock(e.begin)}–${ToolFormat.clock(e.end)}",
                        style = toolLabel(11.sp), color = DiligentColors.Gray700
                    )
                }
            }
        }

        ToolSection("MORNING PROMPT")
        ToolCard {
            ToolToggle(
                title = "Ask me to pick my top 3",
                subtitle = "At ${ToolFormat.hm(state.settings.hour, state.settings.minute)}, only if I haven't picked yet",
                checked = state.settings.prompt,
                onChecked = { on -> askNotifications(); viewModel.update { it.copy(prompt = on) } },
                onClick = { pickTime = true }
            )
        }
        ToolNote("Unfinished picks from yesterday are listed first. The widget's + button adds ½ h, 15 min or 1, depending on the unit.")
    }
}
