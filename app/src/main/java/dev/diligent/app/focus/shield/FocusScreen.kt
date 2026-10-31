package dev.diligent.app.focus.shield

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.FocusRecord
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.focus.FocusTimer
import dev.diligent.app.focus.domain.FocusMath
import dev.diligent.app.focus.domain.FocusStats
import dev.diligent.app.screentime.ScreenTimeService
import dev.diligent.app.tools.ToolButton
import dev.diligent.app.tools.ToolCard
import dev.diligent.app.tools.ToolChip
import dev.diligent.app.tools.ToolColors
import dev.diligent.app.tools.ToolNote
import dev.diligent.app.tools.ToolScaffold
import dev.diligent.app.tools.ToolSection
import dev.diligent.app.tools.ToolStat
import dev.diligent.app.tools.ToolToggle
import dev.diligent.app.tools.toolLabel
import dev.diligent.app.ui.theme.DiligentColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class FocusViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DiligentRepository,
    private val timer: FocusTimer,
    screenTime: ScreenTimeService
) : ViewModel() {

    private val _config = MutableStateFlow(FocusShield.config(context))
    val config: StateFlow<FocusShield.Config> = _config.asStateFlow()

    /** Re-read on resume: the user comes back from Accessibility / DND settings. */
    private val _access = MutableStateFlow(access())
    val access: StateFlow<Access> = _access.asStateFlow()

    data class Access(val service: Boolean, val dnd: Boolean, val calendar: Boolean)

    private fun access() = Access(FocusShield.serviceEnabled(context), FocusShield.dndAccess(context), FocusCalendar.hasPermission(context))

    val distractingCount: Int = screenTime.settings.value.distracting.size

    val records: StateFlow<List<FocusRecord>> =
        repository.observeFocusRecordsSince(System.currentTimeMillis() - 30L * 24 * 3_600_000)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activities: StateFlow<List<Activity>> = repository.getAllActivities()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun calendars() = FocusCalendar.calendars(context)

    fun refreshAccess() {
        _access.value = access()
    }

    fun update(change: (FocusShield.Config) -> FocusShield.Config) {
        val next = change(_config.value)
        FocusShield.save(context, next)
        _config.value = next
    }

    fun rate(record: FocusRecord, rating: Int) = viewModelScope.launch { timer.rate(record.id, rating) }
}

@Composable
fun FocusScreen(onBack: () -> Unit, viewModel: FocusViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val access by viewModel.access.collectAsStateWithLifecycle()
    val records by viewModel.records.collectAsStateWithLifecycle()
    val activities by viewModel.activities.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var calendarMenu by remember { mutableStateOf(false) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refreshAccess() } }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refreshAccess()
        if (FocusCalendar.hasPermission(context)) calendarMenu = true
    }

    ToolScaffold(title = "Focus", onBack = onBack) {
        // ─── Stats ─────────────────────────────────────────────
        val summary = FocusStats.summarize(records)
        val byHour = FocusStats.minutesByHour(records, zone)
        val best = FocusStats.bestWindow(byHour)
        ToolCard {
            Text("LAST 30 DAYS", style = toolLabel(10.sp, bold = true), color = DiligentColors.Gray500)
            Spacer(Modifier.height(6.dp))
            Row {
                ToolStat("${summary.sessions}", "SESSIONS", Modifier.weight(1f))
                ToolStat(FocusMath.formatMinutes(summary.minutes), "FOCUSED", Modifier.weight(1f), color = ToolColors.Good)
                ToolStat("${summary.averageMinutes}m", "AVG LENGTH", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row {
                ToolStat("${(summary.intactShare * 100).toInt()}%", "STAKE HELD", Modifier.weight(1f))
                ToolStat(if (summary.averageRating > 0) "%.1f".format(summary.averageRating) else "–", "AVG RATING", Modifier.weight(1f))
                ToolStat("%.1f".format(summary.interruptionsPerSession), "INTERRUPTS", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                best?.let { "You focus most around ${FocusStats.windowLabel(it)}" } ?: "Finish a few sessions to see your best hours",
                style = toolLabel(11.sp, bold = true), color = DiligentColors.White
            )
            Spacer(Modifier.height(6.dp))
            HourBars(byHour, best)
            val ratings = FocusStats.ratingByHour(records, zone)
            ratings.maxByOrNull { it.value }?.let { (hour, rating) ->
                Text(
                    "Best-rated sessions start around ${FocusStats.windowLabel(hour, 1).substringBefore('–')} (%.1f/5)".format(rating),
                    style = toolLabel(10.sp), color = DiligentColors.Gray600
                )
            }
        }

        // ─── Shield ────────────────────────────────────────────
        ToolSection("FOCUS SHIELD")
        ToolCard {
            if (!access.service) {
                Text("Turn on Diligent Focus Shield in Accessibility", style = toolLabel(12.sp, bold = true), color = DiligentColors.White)
                Text(
                    "It only notices which app opens (never what's on screen) so it can cover distracting apps.",
                    style = toolLabel(10.sp), color = DiligentColors.Gray600
                )
                Spacer(Modifier.height(8.dp))
                ToolButton("OPEN ACCESSIBILITY SETTINGS", Modifier.fillMaxWidth()) { context.startActivity(FocusShield.accessibilitySettings()) }
                Spacer(Modifier.height(10.dp))
            }
            ToolToggle(
                title = "Block distracting apps during focus",
                subtitle = "Opening one anyway breaks the session's stake ◆ → ✕",
                checked = config.shieldDuringFocus,
                onChecked = { on -> viewModel.update { it.copy(shieldDuringFocus = on) } }
            )
            Spacer(Modifier.height(6.dp))
            ToolToggle(
                title = "Mindful pause the rest of the time",
                subtitle = "A ${config.pauseSeconds}-second breath with today's open goals before a distracting app opens",
                checked = config.mindfulPause,
                onChecked = { on -> viewModel.update { it.copy(mindfulPause = on) } }
            )
            if (config.mindfulPause) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 20, 30).forEach { s -> ToolChip("${s}s", config.pauseSeconds == s) { viewModel.update { it.copy(pauseSeconds = s) } } }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${viewModel.distractingCount} apps marked distracting · change them in Hub → Screen vs Focus",
                style = toolLabel(10.sp), color = DiligentColors.Gray600
            )
        }

        // ─── Hard mode, DND ────────────────────────────────────
        ToolSection("COMMITMENT")
        ToolCard {
            ToolToggle(
                title = "Hard mode",
                subtitle = "A session with a planned length can't be stopped (or swapped) until it's over, and the shield offers no way out",
                checked = config.hardMode,
                onChecked = { on -> viewModel.update { it.copy(hardMode = on) } }
            )
            Spacer(Modifier.height(6.dp))
            ToolToggle(
                title = "Do Not Disturb while focusing",
                subtitle = if (access.dnd) "Priority only during sessions; back to normal after" else "Needs Do Not Disturb access",
                checked = config.dnd && access.dnd,
                onChecked = { on ->
                    if (on && !access.dnd) context.startActivity(FocusShield.dndSettings())
                    viewModel.update { it.copy(dnd = on) }
                }
            )
        }

        // ─── Calendar log ──────────────────────────────────────
        ToolSection("CALENDAR LOG")
        ToolCard {
            val calendars = remember(access.calendar) { viewModel.calendars() }
            val chosen = calendars.firstOrNull { it.id == config.calendarId }
            Text("Write each finished session to a calendar", style = toolLabel(12.sp, bold = true), color = DiligentColors.White)
            Text("Your calendar becomes a time log; events are marked free so they never block meetings.", style = toolLabel(10.sp), color = DiligentColors.Gray600)
            Spacer(Modifier.height(8.dp))
            Box {
                ToolChip("CALENDAR: ${chosen?.name?.uppercase() ?: "OFF"}  ▾", chosen != null) {
                    if (access.calendar) calendarMenu = true
                    else calendarPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                }
                DropdownMenu(expanded = calendarMenu, onDismissRequest = { calendarMenu = false }) {
                    DropdownMenuItem(text = { Text("Off") }, onClick = { viewModel.update { it.copy(calendarId = 0L) }; calendarMenu = false })
                    calendars.forEach { cal ->
                        DropdownMenuItem(text = { Text("${cal.name} · ${cal.account}") }, onClick = {
                            viewModel.update { it.copy(calendarId = cal.id) }
                            calendarMenu = false
                        })
                    }
                }
            }
        }

        // ─── Recent sessions ───────────────────────────────────
        if (records.isNotEmpty()) {
            ToolSection("RECENT SESSIONS · TAP TO RATE")
            val names = activities.associate { it.id to it.name }
            val format = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm")
            records.take(12).forEach { record ->
                ToolCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${if (record.broken) "✕" else "◆"} ${names[record.activityId] ?: "?"} · ${FocusMath.formatMinutes(record.focusMinutes)}",
                                style = toolLabel(12.sp, bold = true),
                                color = if (record.broken) ToolColors.Alert else DiligentColors.White
                            )
                            Text(
                                Instant.ofEpochMilli(record.startedAt).atZone(zone).format(format) +
                                    (record.intention.takeIf { it.isNotBlank() }?.let { " · “$it”" } ?: "") +
                                    (record.interruptions.takeIf { it > 0 }?.let { " · ⚡$it" } ?: ""),
                                style = toolLabel(10.sp), color = DiligentColors.Gray600, maxLines = 2
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    RatingDots(record.rating) { viewModel.rate(record, it) }
                }
            }
        }
        ToolNote("Sessions are kept on this phone. Ratings and best hours feed Insights.")
    }
}

@Composable
private fun HourBars(byHour: IntArray, best: Int?) {
    val max = (byHour.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        byHour.forEachIndexed { hour, minutes ->
            val inBest = best != null && (hour - best + 24) % 24 < 2
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight((minutes / max.toFloat()).coerceAtLeast(0.03f))
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(if (inBest) ToolColors.Good else DiligentColors.Gray500)
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("12a", "6a", "12p", "6p", "11p").forEach { Text(it, style = toolLabel(8.sp), color = DiligentColors.Gray500) }
    }
}

@Composable
fun RatingDots(rating: Int, onRate: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        (1..5).forEach { value ->
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (value <= rating) DiligentColors.White else DiligentColors.Gray300)
                    .clickable { onRate(if (value == rating) 0 else value) },
                contentAlignment = Alignment.Center
            ) {
                Text("$value", style = toolLabel(9.sp, bold = true), color = if (value <= rating) DiligentColors.Black else DiligentColors.Gray600)
            }
        }
    }
}
