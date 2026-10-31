package dev.diligent.app.planning

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.DayLog
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.focus.FocusTimer
import dev.diligent.app.focus.domain.FocusMath
import dev.diligent.app.focus.shield.FocusCalendar
import dev.diligent.app.focus.shield.FocusShield
import dev.diligent.app.focus.shield.RatingDots
import dev.diligent.app.tools.ToolButton
import dev.diligent.app.tools.ToolCard
import dev.diligent.app.tools.ToolChip
import dev.diligent.app.tools.ToolColors
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.tools.ToolJob
import dev.diligent.app.tools.ToolJobs
import dev.diligent.app.tools.ToolNote
import dev.diligent.app.tools.ToolScaffold
import dev.diligent.app.tools.ToolSection
import dev.diligent.app.tools.ToolStat
import dev.diligent.app.tools.ToolTimeDialog
import dev.diligent.app.tools.ToolToggle
import dev.diligent.app.tools.rememberNotificationAsker
import dev.diligent.app.tools.toolLabel
import dev.diligent.app.top3.CalendarBusy
import dev.diligent.app.top3.CalendarEvent
import dev.diligent.app.top3.Top3Service
import dev.diligent.app.tracking.DayClock
import dev.diligent.app.ui.theme.DiligentColors
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── Plan ───────────────────────────────────────────────────────

@HiltViewModel
class PlanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: PlanService,
    private val timer: FocusTimer,
    repository: DiligentRepository
) : ViewModel() {

    val settings: StateFlow<PlanSettings> = service.settings
    val plan: StateFlow<StoredPlan> = service.plan
    val activities: StateFlow<List<Activity>> = repository.getAllActiveActivities()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val messages = MutableSharedFlow<String>()

    private val _energy = MutableStateFlow<EnergyView?>(null)
    val energy: StateFlow<EnergyView?> = _energy.asStateFlow()

    private val _events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    val events: StateFlow<List<CalendarEvent>> = _events.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        _energy.value = service.energy()
        _events.value = CalendarBusy.read(context, DayClock.today()).orEmpty()
    }

    fun replan() = viewModelScope.launch {
        val plan = service.replan()
        refresh()
        messages.emit(if (plan.blocks.isEmpty()) "Nothing left to plan today" else "${plan.blocks.size} blocks planned")
    }

    fun clear() = viewModelScope.launch { service.clear() }

    fun start(block: StoredBlock) = viewModelScope.launch {
        val minutes = ((block.end - maxOf(block.start, System.currentTimeMillis())) / 60_000).toInt().coerceAtLeast(5)
        timer.start(block.activityId, pomodoro = false, targetMinutes = minutes)
        messages.emit("Focus started for $minutes min")
    }

    fun update(change: (PlanSettings) -> PlanSettings) {
        service.update(change)
        ToolJobs.reschedule(context, ToolJob.PLAN_MORNING)
        ToolJobs.reschedule(context, ToolJob.SHUTDOWN_PROMPT)
    }

    fun calendarReady() = FocusShield.config(context).calendarId != 0L && FocusCalendar.hasPermission(context)
}

@Composable
fun PlanScreen(onBack: () -> Unit, viewModel: PlanViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    val activities by viewModel.activities.collectAsStateWithLifecycle()
    val energy by viewModel.energy.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val names = activities.associate { it.id to it.name }
    val context = LocalContext.current
    var morningPicker by remember { mutableStateOf(false) }
    val askNotifications = rememberNotificationAsker()
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refresh() }

    if (morningPicker) {
        ToolTimeDialog("Plan every morning at", settings.morningHour, settings.morningMinute, { morningPicker = false }) { h, m ->
            viewModel.update { it.copy(morningHour = h, morningMinute = m, autoMorning = true) }
            morningPicker = false
        }
    }

    ToolScaffold(title = "Plan", onBack = onBack, messages = viewModel.messages) {
        // ─── Energy ────────────────────────────────────────────
        energy?.let { e ->
            ToolCard {
                Row {
                    ToolStat(e.lastNightMinutes?.let { "%d:%02d".format(it / 60, it % 60) } ?: "–", "LAST NIGHT", Modifier.weight(1f))
                    ToolStat(e.sleepDebt?.let { "%d:%02d".format(it / 60, it % 60) } ?: "–", "SLEEP DEBT · 14D", Modifier.weight(1f),
                        color = if ((e.sleepDebt ?: 0) > 8 * 60) ToolColors.Warn else DiligentColors.White)
                    ToolStat(e.peak?.let { ToolFormat.clock(it.start) } ?: "–", "PEAK STARTS", Modifier.weight(1f), color = ToolColors.Good)
                }
                Spacer(Modifier.height(6.dp))
                val peak = e.peak
                Text(
                    if (peak != null && e.peakSource != null) "Deep work goes into your peak (${ToolFormat.clock(peak.start)}–${ToolFormat.clock(peak.end)}, from ${e.peakSource})."
                    else "Connect sleep in Health, or finish a few focus sessions, to plan around your energy.",
                    style = toolLabel(10.sp), color = DiligentColors.Gray600
                )
            }
        }

        // ─── Timeline ──────────────────────────────────────────
        ToolSection("TODAY")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolButton(if (plan.blocks.isEmpty() || plan.date != DayClock.todayString()) "AUTO-PLAN MY DAY" else "RE-PLAN FROM NOW", Modifier.weight(1f)) {
                viewModel.replan()
            }
            if (plan.blocks.isNotEmpty()) ToolButton("CLEAR", outlined = true) { viewModel.clear() }
        }
        val now = System.currentTimeMillis()
        val today = plan.takeIf { it.date == DayClock.todayString() }?.blocks.orEmpty()
        val timeline = (today.map { it.start to it } + events.filter { it.end > now }.map { it.begin to it }).sortedBy { it.first }
        if (timeline.isEmpty()) {
            ToolNote("Auto-plan fits what's left today around your calendar: deep work into your peak, Top 3 first, 15-minute minimum blocks with 5-minute gaps.")
        }
        timeline.forEach { (_, item) ->
            when (item) {
                is StoredBlock -> TimelineRow(
                    time = "${ToolFormat.clock(item.start)}–${ToolFormat.clock(item.end)}",
                    title = names[item.activityId] ?: "?",
                    detail = FocusMath.formatMinutes(((item.end - item.start) / 60_000).toInt()),
                    color = if (item.end < now) DiligentColors.Gray500 else ToolColors.Good,
                    action = if (item.end > now) "START" else null,
                    onAction = { viewModel.start(item) }
                )
                is CalendarEvent -> TimelineRow(
                    time = "${ToolFormat.clock(item.begin)}–${ToolFormat.clock(item.end)}",
                    title = item.title,
                    detail = "calendar",
                    color = DiligentColors.Gray600,
                    action = null,
                    onAction = {}
                )
            }
        }

        // ─── Settings ──────────────────────────────────────────
        ToolSection("AUTOMATIC")
        ToolCard {
            ToolToggle(
                title = "Plan every morning",
                subtitle = "At ${ToolFormat.hm(settings.morningHour, settings.morningMinute)}, with a notification of your blocks · tap to change",
                checked = settings.autoMorning,
                onChecked = { on -> if (on) askNotifications(); viewModel.update { it.copy(autoMorning = on) } },
                onClick = { morningPicker = true }
            )
            Spacer(Modifier.height(6.dp))
            ToolToggle(
                title = "Put blocks in my calendar",
                subtitle = if (viewModel.calendarReady()) "Into the Focus calendar, marked free; replaced when you re-plan"
                else "Pick a calendar in Hub → Focus → Calendar log first",
                checked = settings.writeCalendar,
                onChecked = { on ->
                    if (on && !FocusCalendar.hasPermission(context)) calendarPermission.launch(Manifest.permission.WRITE_CALENDAR)
                    viewModel.update { it.copy(writeCalendar = on) }
                }
            )
            Spacer(Modifier.height(8.dp))
            Text("DAY ENDS AT", style = toolLabel(9.sp, bold = true), color = DiligentColors.Gray500)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(18, 20, 22, 23).forEach { h -> ToolChip("$h:00", settings.endHour == h) { viewModel.update { it.copy(endHour = h) } } }
            }
        }
    }
}

@Composable
private fun TimelineRow(time: String, title: String, detail: String, color: androidx.compose.ui.graphics.Color, action: String?, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DiligentColors.Surface).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.dp).height(30.dp).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(time, style = toolLabel(10.sp), color = DiligentColors.Gray600)
            Text(title, style = toolLabel(12.sp, bold = true), color = DiligentColors.White, maxLines = 1)
        }
        Text(detail, style = toolLabel(10.sp), color = DiligentColors.Gray600)
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            ToolButton(action, outlined = true, onClick = onAction)
        }
    }
}

// ─── Shutdown ───────────────────────────────────────────────────

@HiltViewModel
class ShutdownViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DiligentRepository,
    private val top3: Top3Service,
    private val service: PlanService
) : ViewModel() {

    data class Day(
        val done: List<Activity>,
        val open: List<Activity>,
        val log: DayLog,
        val hours: Float,
        val tomorrow: List<Long>,
        val all: List<Activity>
    )

    private val _day = MutableStateFlow<Day?>(null)
    val day: StateFlow<Day?> = _day.asStateFlow()
    val settings: StateFlow<PlanSettings> = service.settings
    val messages = MutableSharedFlow<String>()

    init {
        load()
    }

    private fun load() = viewModelScope.launch {
        val all = repository.getAllActiveSnapshot()
        val todayRows = repository.getTodayProgressSnapshot().associateBy { it.activityId }
        val open = repository.openToday()
        val done = all.filter { todayRows[it.id]?.isCompleted == true }
        val tomorrow = DayClock.today().plusDays(1)
        val planned = top3.plannedFor(tomorrow)
        // Default: tomorrow carries today's unfinished picks, then other open work.
        val suggested = planned.ifEmpty { (top3.todayIds().filter { id -> open.any { it.id == id } } + open.map { it.id }).distinct().take(Top3Service.MAX) }
        _day.value = Day(
            done = done,
            open = open,
            log = repository.getDayLog(),
            hours = repository.getTotalHoursTracked(repository.today(), repository.today()),
            tomorrow = suggested,
            all = all
        )
    }

    fun setMood(value: Int) = editLog { it.copy(mood = value) }
    fun setEnergy(value: Int) = editLog { it.copy(energy = value) }

    fun toggleTomorrow(id: Long) {
        val current = _day.value ?: return
        val next = if (id in current.tomorrow) current.tomorrow - id else (current.tomorrow + id).take(Top3Service.MAX)
        _day.value = current.copy(tomorrow = next)
    }

    fun shutDown(note: String) = viewModelScope.launch {
        val day = _day.value ?: return@launch
        repository.updateDayLog { it.copy(note = note.trim(), shutdownAt = System.currentTimeMillis()) }
        top3.planFor(DayClock.today().plusDays(1), day.tomorrow)
        load()
        messages.emit("Day closed ✓ · tomorrow's Top 3 is set")
    }

    fun update(change: (PlanSettings) -> PlanSettings) {
        service.update(change)
        ToolJobs.reschedule(context, ToolJob.SHUTDOWN_PROMPT)
    }

    private fun editLog(change: (DayLog) -> DayLog) = viewModelScope.launch {
        repository.updateDayLog(change = change)
        _day.value = _day.value?.copy(log = repository.getDayLog())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShutdownScreen(onBack: () -> Unit, viewModel: ShutdownViewModel = hiltViewModel()) {
    val day by viewModel.day.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var note by remember(day?.log?.note) { mutableStateOf(day?.log?.note.orEmpty()) }
    var picker by remember { mutableStateOf(false) }
    val askNotifications = rememberNotificationAsker()

    if (picker) {
        ToolTimeDialog("Shutdown reminder", settings.shutdownHour, settings.shutdownMinute, { picker = false }) { h, m ->
            viewModel.update { it.copy(shutdownHour = h, shutdownMinute = m, shutdownPrompt = true) }
            picker = false
        }
    }

    ToolScaffold(title = "Shutdown", onBack = onBack, messages = viewModel.messages) {
        val d = day ?: return@ToolScaffold
        ToolCard {
            if (d.log.shutdownAt > 0) {
                Text("Day closed at ${ToolFormat.clock(d.log.shutdownAt)} ✓", style = toolLabel(12.sp, bold = true), color = ToolColors.Good)
                Spacer(Modifier.height(6.dp))
            }
            Row {
                ToolStat("${d.done.size}", "DONE", Modifier.weight(1f), color = ToolColors.Good)
                ToolStat("${d.open.size}", "STILL OPEN", Modifier.weight(1f), color = if (d.open.isEmpty()) DiligentColors.White else ToolColors.Warn)
                ToolStat("%.1f h".format(d.hours), "TRACKED", Modifier.weight(1f))
            }
            if (d.open.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("open: " + d.open.joinToString(", ") { it.name }, style = toolLabel(10.sp), color = DiligentColors.Gray600)
            }
        }

        ToolSection("HOW WAS TODAY")
        ToolCard {
            Text("MOOD", style = toolLabel(9.sp, bold = true), color = DiligentColors.Gray500)
            RatingDots(d.log.mood) { viewModel.setMood(it) }
            Spacer(Modifier.height(10.dp))
            Text("ENERGY", style = toolLabel(9.sp, bold = true), color = DiligentColors.Gray500)
            RatingDots(d.log.energy) { viewModel.setEnergy(it) }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("one line about today…", style = toolLabel(11.sp), color = DiligentColors.Gray500) },
                textStyle = toolLabel(12.sp).copy(color = DiligentColors.White),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = DiligentColors.White,
                    unfocusedBorderColor = DiligentColors.Border,
                    focusedContainerColor = DiligentColors.Black,
                    unfocusedContainerColor = DiligentColors.Black
                )
            )
            ToolNote("Mood and energy feed Insights: what you do on your best days.")
        }

        ToolSection("TOMORROW'S TOP 3")
        ToolCard {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                d.all.forEach { activity ->
                    ToolChip(activity.name, activity.id in d.tomorrow) { viewModel.toggleTomorrow(activity.id) }
                }
            }
            ToolNote("Unfinished picks and open work are suggested first. They become tomorrow's Top 3 unless you change them in the morning.")
        }

        ToolButton("SHUT DOWN THE DAY", Modifier.fillMaxWidth()) { viewModel.shutDown(note) }

        ToolCard {
            ToolToggle(
                title = "Evening reminder",
                subtitle = "At ${ToolFormat.hm(settings.shutdownHour, settings.shutdownMinute)} unless the day is closed · tap to change",
                checked = settings.shutdownPrompt,
                onChecked = { on -> if (on) askNotifications(); viewModel.update { it.copy(shutdownPrompt = on) } },
                onClick = { picker = true }
            )
        }
    }
}
