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
import dev.diligent.app.ui.theme.Cinzel
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DotMatrix
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
) : ViewModel() {

    private val activityId: Long = savedStateHandle.get<Long>("activityId") ?: 0L
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    val activity: StateFlow<Activity?> =
        repository.getActivityByIdFlow(activityId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val todayProgress: StateFlow<DailyProgress?> =
        repository.getProgressForDate(repository.today())
            .map { list -> list.find { it.activityId == activityId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _streak = MutableStateFlow(0)
    val streak: StateFlow<Int> = _streak.asStateFlow()

    private val _weeklyData = MutableStateFlow<List<Pair<String, Float>>>(emptyList())
    val weeklyData: StateFlow<List<Pair<String, Float>>> = _weeklyData.asStateFlow()

    private val _totalCompleted = MutableStateFlow(0)
    val totalCompleted: StateFlow<Int> = _totalCompleted.asStateFlow()

    init {
        viewModelScope.launch {
            _streak.value = repository.calculateStreak(activityId)

            // Weekly data
            val today = LocalDate.now()
            val weekData = (6 downTo 0).map { daysAgo ->
                val date = today.minusDays(daysAgo.toLong())
                val dateStr = date.format(dateFormatter)
                val progress = repository.getAllProgressRangeSnapshot(dateStr, dateStr)
                    .find { it.activityId == activityId }
                val label = date.dayOfWeek.name.take(3)
                label to (progress?.progress ?: 0f)
            }
            _weeklyData.value = weekData

            // Total completed days
            val allDates = repository.getAllProgressRangeSnapshot(
                today.minusDays(365).format(dateFormatter),
                today.format(dateFormatter)
            )
            _totalCompleted.value = allDates.count { it.activityId == activityId && it.isCompleted }
        }
    }

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
    val streak by viewModel.streak.collectAsStateWithLifecycle()
    val weeklyData by viewModel.weeklyData.collectAsStateWithLifecycle()
    val totalCompleted by viewModel.totalCompleted.collectAsStateWithLifecycle()

    var showDeleteDialog by remember { mutableStateOf(false) }

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

                Spacer(modifier = Modifier.height(24.dp))

                // ─── Today's Progress ───────────────────────
                val progress = todayProgress?.progress ?: 0f
                val isCompleted = todayProgress?.isCompleted == true
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
                            text = if (isCompleted) "✓ DONE"
                            else "${formatFloat(progress)} / ${formatFloat(act.dailyGoal)}",
                            style = MaterialTheme.typography.displayMedium.copy(
                                fontFamily = DotMatrix,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            ),
                            color = if (isCompleted) DiligentColors.CompletedGreen
                            else DiligentColors.White
                        )

                        Text(
                            text = act.unit,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = DotMatrix,
                                letterSpacing = 2.sp
                            ),
                            color = DiligentColors.Gray500
                        )

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
                                        if (isCompleted) DiligentColors.CompletedGreen
                                        else DiligentColors.White
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

                            FilledTonalButton(
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
                        suffix = "days",
                        modifier = Modifier.weight(1f)
                    )
                    StatMiniCard(
                        label = "COMPLETED",
                        value = "$totalCompleted",
                        suffix = "days",
                        modifier = Modifier.weight(1f)
                    )
                    StatMiniCard(
                        label = "TODAY",
                        value = "${(percent * 100).toInt()}",
                        suffix = "%",
                        modifier = Modifier.weight(1f)
                    )
                }

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
