package dev.diligent.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.tracking.DayClock
import dev.diligent.app.tracking.Goals
import dev.diligent.app.tracking.HabitStrength
import dev.diligent.app.tracking.HeatDay
import dev.diligent.app.tracking.Heatmap
import dev.diligent.app.tracking.Schedule
import dev.diligent.app.tracking.Tracking
import dev.diligent.app.tracking.TrackingSummary
import dev.diligent.app.ui.components.DayEditor
import dev.diligent.app.ui.components.DayEditorSheet
import dev.diligent.app.ui.components.YearHeatmap
import dev.diligent.app.ui.theme.Cinzel
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DotMatrix
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * ViewModel for the Activity Detail screen.
 */
@HiltViewModel
class ActivityDetailViewModel @Inject constructor(
    private val repository: DiligentRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel(), DayEditor {

    private val activityId: Long = savedStateHandle.get<Long>("activityId") ?: 0L
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    val activity: StateFlow<Activity?> =
        repository.getActivityByIdFlow(activityId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val todayProgress: StateFlow<DailyProgress?> =
        repository.getTodayProgress()
            .map { list -> list.find { it.activityId == activityId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    data class History(
        val streak: Int = 0,
        val weekly: List<Pair<String, Float>> = emptyList(),
        val totalCompleted: Int = 0,
        val summary: TrackingSummary? = null,
        val heatmap: List<List<HeatDay>> = emptyList(),
        val rows: Map<String, DailyProgress> = emptyMap(),
        /** Quit / limit activities: days since the last slip. */
        val daysClean: Long = 0
    )

    /**
     * The last 400 days of this activity, re-derived whenever a row or the activity changes,
     * so the streak, strength, chart and heatmap update right after +1 / DONE or an edit.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val history: StateFlow<History> =
        repository.todayFlow()
            .flatMapLatest { todayStr ->
                val today = LocalDate.parse(todayStr, dateFormatter)
                combine(
                    repository.getActivityByIdFlow(activityId).filterNotNull(),
                    repository.getProgressRange(
                        activityId,
                        today.minusDays(Tracking.HISTORY_DAYS).format(dateFormatter),
                        todayStr
                    )
                ) { activity, rows ->
                    val byDate = rows.associateBy { it.date }
                    val weekly = (6 downTo 0).map { daysAgo ->
                        val date = today.minusDays(daysAgo.toLong())
                        date.dayOfWeek.name.take(3) to (byDate[date.format(dateFormatter)]?.progress ?: 0f)
                    }
                    val days = repository.split(rows)
                    val created = repository.createdDate(activity)
                    val summary = repository.trackingSummary(activity, rows)
                    History(
                        streak = summary.streak.current,
                        weekly = weekly,
                        totalCompleted = rows.count { it.isCompleted },
                        summary = summary,
                        heatmap = Heatmap.build(activity, days.progress, days.skipped, days.completed, today, created),
                        rows = byDate,
                        daysClean = Tracking.daysSinceSlip(activity, days.progress, today, created)
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), History())

    /** The day open in the editor sheet, if any. */
    private val _editing = MutableStateFlow<LocalDate?>(null)
    val editing: StateFlow<LocalDate?> = _editing.asStateFlow()

    fun edit(date: LocalDate?) {
        _editing.value = date
    }

    fun today(): LocalDate = DayClock.today()

    fun increment() {
        viewModelScope.launch { repository.incrementProgress(activityId) }
    }

    fun decrement() {
        viewModelScope.launch { repository.decrementProgress(activityId) }
    }

    fun toggleComplete() {
        viewModelScope.launch { repository.toggleComplete(activityId) }
    }

    fun delete() {
        viewModelScope.launch {
            activity.value?.let { repository.deleteActivity(it) }
        }
    }

    // ─── DayEditor: any day, from the heatmap ───────────────────

    private fun LocalDate.key() = format(dateFormatter)

    override fun adjust(date: LocalDate, delta: Float) {
        viewModelScope.launch {
            if (delta >= 0f) repository.addProgress(activityId, delta, date.key())
            else repository.decrementProgress(activityId, -delta, date = date.key())
        }
    }

    override fun set(date: LocalDate, value: Float) {
        viewModelScope.launch { repository.setProgress(activityId, date.key(), value) }
    }

    override fun toggleDone(date: LocalDate) {
        viewModelScope.launch { repository.toggleComplete(activityId, date.key()) }
    }

    override fun setSkipped(date: LocalDate, skipped: Boolean) {
        viewModelScope.launch { repository.setSkipped(activityId, date.key(), skipped) }
    }

    override fun setRating(date: LocalDate, rating: Int) {
        viewModelScope.launch { repository.setDayRating(activityId, date.key(), rating) }
    }

    override fun setNote(date: LocalDate, note: String) {
        viewModelScope.launch { repository.setDayNote(activityId, date.key(), note) }
    }
}

/**
 * Detailed view of a single activity with history, streak, and controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityDetailScreen(
    onNavigateBack: () -> Unit,
    onNavigateToEdit: (Long) -> Unit,
    viewModel: ActivityDetailViewModel = hiltViewModel()
) {
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val todayProgress by viewModel.todayProgress.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val streak = history.streak
    val weeklyData = history.weekly
    val totalCompleted = history.totalCompleted

    var showDeleteDialog by remember { mutableStateOf(false) }

    val editingActivity = activity
    val editingDate = editing
    if (editingActivity != null && editingDate != null) {
        DayEditorSheet(
            activity = editingActivity,
            date = editingDate,
            row = history.rows[editingDate.toString()],
            editor = viewModel,
            onDismiss = { viewModel.edit(null) }
        )
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    "Delete Activity",
                    fontFamily = Cinzel,
                    color = DiligentColors.White
                )
            },
            text = {
                Text(
                    "This will permanently delete \"${activity?.name}\" and all its progress data.",
                    fontFamily = DotMatrix,
                    color = DiligentColors.Gray700
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete()
                    showDeleteDialog = false
                    onNavigateBack()
                }) {
                    Text("DELETE", color = Color(0xFFFF6B6B), fontFamily = DotMatrix)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("CANCEL", color = DiligentColors.Gray600, fontFamily = DotMatrix)
                }
            },
            containerColor = DiligentColors.SurfaceElevated
        )
    }

    Scaffold(
        containerColor = DiligentColors.Black,
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = DiligentColors.White)
                    }
                },
                actions = {
                    activity?.let { a ->
                        IconButton(onClick = { onNavigateToEdit(a.id) }) {
                            Icon(Icons.Default.Edit, "Edit", tint = DiligentColors.Gray700)
                        }
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, "Delete", tint = DiligentColors.Gray700)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DiligentColors.Black)
            )
        }
    ) { paddingValues ->
        activity?.let { act ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                // ─── Activity Title ─────────────────────────
                Text(
                    text = act.name,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontFamily = Cinzel,
                        letterSpacing = 1.sp
                    ),
                    color = DiligentColors.White
                )
                Text(
                    text = listOf(
                        Schedule.describe(act),
                        when (act.goalType) {
                            Goals.AT_MOST -> "max ${formatFloat(act.dailyGoal)} ${act.unit} a day"
                            Goals.QUIT -> "quitting"
                            else -> "${formatFloat(act.dailyGoal)} ${act.unit} a day"
                        }
                    ).joinToString(" · ").uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = DotMatrix, letterSpacing = 2.sp),
                    color = DiligentColors.Gray600
                )

                Spacer(modifier = Modifier.height(24.dp))

                // ─── Today's Progress ───────────────────────
                val progress = todayProgress?.progress ?: 0f
                val isLimit = Goals.isLimit(act)
                val isCompleted = !isLimit && todayProgress?.isCompleted == true
                val isOver = isLimit && !Goals.isMet(act, progress)
                val percent = if (act.dailyGoal > 0) (progress / act.dailyGoal).coerceIn(0f, 1f) else 0f

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(
                            1.dp,
                            if (isCompleted) DiligentColors.CompletedGreen.copy(alpha = 0.4f)
                            else DiligentColors.Border,
                            RoundedCornerShape(16.dp)
                        )
                        .background(DiligentColors.Surface)
                        .padding(24.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "TODAY",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = DotMatrix,
                                letterSpacing = 3.sp
                            ),
                            color = DiligentColors.Gray500
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Large progress display
                        Text(
                            text = when {
                                todayProgress?.isSkipped == true -> "SKIPPED"
                                isCompleted -> "✓ DONE"
                                act.goalType == Goals.QUIT -> if (progress > 0f) "SLIPPED" else "✓ CLEAN"
                                else -> "${formatFloat(progress)} / ${formatFloat(act.dailyGoal)}"
                            },
                            style = MaterialTheme.typography.displayMedium.copy(
                                fontFamily = DotMatrix,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            ),
                            color = when {
                                isCompleted -> DiligentColors.CompletedGreen
                                isOver -> DiligentColors.OverRed
                                else -> DiligentColors.White
                            }
                        )

                        Text(
                            text = act.unit + if (act.goalType == Goals.AT_MOST) " max" else "",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = DotMatrix,
                                letterSpacing = 2.sp
                            ),
                            color = DiligentColors.Gray500
                        )
                        history.summary?.caption?.let { caption ->
                            Text(
                                text = caption.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = DotMatrix, letterSpacing = 2.sp),
                                color = DiligentColors.Gray600
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Progress bar
                        val animatedProgress by animateFloatAsState(
                            targetValue = percent,
                            animationSpec = tween(800, easing = FastOutSlowInEasing),
                            label = "detailProgress"
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(DiligentColors.Gray400)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(animatedProgress)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        when {
                                            isCompleted -> DiligentColors.CompletedGreen
                                            isOver -> DiligentColors.OverRed
                                            else -> DiligentColors.White
                                        }
                                    )
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Control buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            FilledTonalButton(
                                onClick = { viewModel.decrement() },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = DiligentColors.Gray300,
                                    contentColor = DiligentColors.White
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Remove, "Decrement", modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("−1", fontFamily = DotMatrix)
                            }

                            if (!isLimit) FilledTonalButton(
                                onClick = { viewModel.toggleComplete() },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (isCompleted) DiligentColors.CompletedGreen.copy(alpha = 0.2f)
                                    else DiligentColors.Gray300,
                                    contentColor = if (isCompleted) DiligentColors.CompletedGreen
                                    else DiligentColors.White
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Check, "Complete", modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (isCompleted) "UNDO" else "DONE", fontFamily = DotMatrix)
                            }

                            FilledTonalButton(
                                onClick = { viewModel.increment() },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = DiligentColors.Gray300,
                                    contentColor = DiligentColors.White
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Add, "Increment", modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("+1", fontFamily = DotMatrix)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.edit(viewModel.today()) }) {
                            Text(
                                text = buildString {
                                    append(if (todayProgress?.notes.isNullOrBlank()) "NOTE · RATE · SKIP" else "“${todayProgress?.notes}”")
                                    todayProgress?.rating?.takeIf { it > 0 }?.let { append("  ·  $it/5") }
                                },
                                fontFamily = DotMatrix,
                                fontSize = 11.sp,
                                color = DiligentColors.Gray600,
                                maxLines = 2
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ─── Stats Cards Row ────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatMiniCard(
                        label = "STREAK",
                        value = "$streak",
                        suffix = history.summary?.streakUnit ?: "days",
                        modifier = Modifier.weight(1f)
                    )
                    if (isLimit) {
                        StatMiniCard(
                            label = "CLEAN",
                            value = "${history.daysClean}",
                            suffix = "days",
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        StatMiniCard(
                            label = "COMPLETED",
                            value = "$totalCompleted",
                            suffix = "days",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    StatMiniCard(
                        label = "STRENGTH",
                        value = "${history.summary?.let { HabitStrength.percent(it.strength) } ?: 0}",
                        suffix = "%",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ─── Year heatmap: tap a day to edit it ─────
                Text(
                    text = "LAST 12 MONTHS",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = DotMatrix,
                        letterSpacing = 2.sp
                    ),
                    color = DiligentColors.Gray500
                )
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, DiligentColors.Border, RoundedCornerShape(12.dp))
                        .background(DiligentColors.Surface)
                        .padding(12.dp)
                ) {
                    YearHeatmap(
                        weeks = history.heatmap,
                        selected = editing,
                        onDayClick = { viewModel.edit(it) }
                    )
                }
                Text(
                    text = "tap a day to log, skip, rate or add a note",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = DotMatrix, fontSize = 9.sp),
                    color = DiligentColors.Gray500,
                    modifier = Modifier.padding(top = 6.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // ─── Weekly Bar Chart ───────────────────────
                Text(
                    text = "LAST 7 DAYS",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = DotMatrix,
                        letterSpacing = 2.sp
                    ),
                    color = DiligentColors.Gray500
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Simple dot-matrix style bar chart
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, DiligentColors.Border, RoundedCornerShape(12.dp))
                        .background(DiligentColors.Surface)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    val maxVal = (weeklyData.maxOfOrNull { it.second } ?: 1f).coerceAtLeast(act.dailyGoal)

                    weeklyData.forEach { (label, value) ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                            modifier = Modifier.weight(1f)
                        ) {
                            val barHeight = if (maxVal > 0) (value / maxVal) else 0f

                            Box(
                                modifier = Modifier
                                    .width(12.dp)
                                    .fillMaxHeight(barHeight.coerceIn(0f, 1f) * 0.7f)
                                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                    .background(
                                        if (value >= act.dailyGoal) DiligentColors.CompletedGreen
                                        else DiligentColors.White.copy(alpha = 0.6f)
                                    )
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = DotMatrix,
                                    fontSize = 8.sp
                                ),
                                color = DiligentColors.Gray600
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun StatMiniCard(
    label: String,
    value: String,
    suffix: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, DiligentColors.Border, RoundedCornerShape(12.dp))
            .background(DiligentColors.Surface)
            .padding(16.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = DotMatrix,
                    letterSpacing = 1.sp,
                    fontSize = 9.sp
                ),
                color = DiligentColors.Gray500
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = DotMatrix,
                    fontWeight = FontWeight.Bold
                ),
                color = DiligentColors.White
            )
            Text(
                text = suffix,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = DotMatrix),
                color = DiligentColors.Gray600
            )
        }
    }
}

private fun formatFloat(value: Float): String {
    return if (value == value.toInt().toFloat()) value.toInt().toString()
    else String.format("%.1f", value)
}
