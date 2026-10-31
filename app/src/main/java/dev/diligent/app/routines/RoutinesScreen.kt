package dev.diligent.app.routines

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
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
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.focus.domain.FocusMath
import dev.diligent.app.tools.ToolButton
import dev.diligent.app.tools.ToolCard
import dev.diligent.app.tools.ToolChip
import dev.diligent.app.tools.ToolColors
import dev.diligent.app.tools.ToolField
import dev.diligent.app.tools.ToolNote
import dev.diligent.app.tools.ToolScaffold
import dev.diligent.app.tools.ToolSection
import dev.diligent.app.tools.toolLabel
import dev.diligent.app.ui.theme.Cinzel
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DotMatrix
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RoutinesViewModel @Inject constructor(
    private val store: RoutineStore,
    repository: DiligentRepository
) : ViewModel() {

    val state: StateFlow<RoutineState> = store.state

    val activities: StateFlow<List<Activity>> = repository.getAllActiveActivities()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(routine: Routine) = viewModelScope.launch { store.save(routine) }
    fun delete(id: Long) = viewModelScope.launch { store.delete(id) }
    fun start(id: Long) = viewModelScope.launch { store.start(id) }
    fun next() = viewModelScope.launch { store.next() }
    fun skip() = viewModelScope.launch { store.skip() }
    fun end() = viewModelScope.launch { store.end() }
}

@Composable
fun RoutinesScreen(onBack: () -> Unit, viewModel: RoutinesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activities by viewModel.activities.collectAsStateWithLifecycle()
    val byId = activities.associateBy { it.id }
    var editing by remember { mutableStateOf<Routine?>(null) }

    editing?.let { routine ->
        RoutineEditor(
            routine = routine,
            activities = activities,
            onDismiss = { editing = null },
            onSave = {
                viewModel.save(it)
                editing = null
            },
            onDelete = if (routine.id != 0L) ({
                viewModel.delete(routine.id)
                editing = null
            }) else null
        )
    }

    ToolScaffold(title = "Routines", onBack = onBack) {
        val run = state.run
        val running = state.running
        if (run != null && running != null) {
            val step = running.steps.getOrNull(run.step)
            val activity = step?.let { byId[it.activityId] }
            ToolCard {
                Text("NOW · STEP ${run.step + 1} OF ${running.steps.size}", style = toolLabel(10.sp, bold = true), color = ToolColors.Good)
                Text(
                    activity?.name ?: "Deleted activity",
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = Cinzel),
                    color = DiligentColors.White
                )
                if (step != null && activity != null) {
                    if (FocusMath.isTimeUnit(activity.unit)) {
                        val now = rememberSecondTicker()
                        val left = RoutineMath.remaining(step, run, now)
                        Text(
                            if (left >= 0) FocusMath.formatClock(left) else "+${FocusMath.formatClock(-left)} over",
                            style = toolLabel(34.sp, bold = true),
                            color = if (left >= 0) DiligentColors.White else ToolColors.Warn
                        )
                        Text("focus timer running · logs the time when you move on", style = toolLabel(10.sp), color = DiligentColors.Gray600)
                    } else {
                        Text(
                            "Log ${formatAmount(step.amount)} ${activity.unit} when done",
                            style = toolLabel(14.sp, bold = true),
                            color = DiligentColors.White
                        )
                    }
                }
                running.steps.getOrNull(run.step + 1)?.let { upcoming ->
                    Text("next: ${byId[upcoming.activityId]?.name ?: "?"}", style = toolLabel(10.sp), color = DiligentColors.Gray600)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolButton("DONE · NEXT", Modifier.weight(1f)) { viewModel.next() }
                    ToolButton("SKIP", Modifier.weight(0.6f), outlined = true) { viewModel.skip() }
                    ToolButton("END", Modifier.weight(0.6f), outlined = true) { viewModel.end() }
                }
            }
        }

        ToolSection("YOUR ROUTINES")
        if (state.routines.isEmpty()) {
            ToolNote("A routine chains activities in order, e.g. a morning stack: Reading 20 min → LeetCode 1 → Quantum 45 min. Each timed step runs the focus timer; counted steps log their amount when you tap DONE.")
        }
        state.routines.forEach { routine ->
            ToolCard(onClick = { editing = routine }) {
                Text(routine.name, style = toolLabel(13.sp, bold = true), color = DiligentColors.White)
                val minutes = RoutineMath.plannedMinutes(routine) { id -> byId[id]?.let { FocusMath.isTimeUnit(it.unit) } == true }
                Text(
                    "${routine.steps.size} steps · about ${FocusMath.formatMinutes(minutes)}",
                    style = toolLabel(10.sp),
                    color = DiligentColors.Gray600
                )
                Text(
                    routine.steps.joinToString(" → ") { byId[it.activityId]?.name ?: "?" },
                    style = toolLabel(10.sp),
                    color = DiligentColors.Gray500,
                    maxLines = 2
                )
                Spacer(Modifier.height(8.dp))
                ToolButton(if (state.run?.routineId == routine.id) "RESTART" else "START", Modifier.fillMaxWidth()) {
                    viewModel.start(routine.id)
                }
            }
        }
        ToolButton("+ NEW ROUTINE", Modifier.fillMaxWidth(), outlined = true, enabled = activities.isNotEmpty()) {
            editing = Routine(0, "", listOfNotNull(activities.firstOrNull()?.let { RoutineStep(it.id) }))
        }
    }
}

@Composable
private fun RoutineEditor(
    routine: Routine,
    activities: List<Activity>,
    onDismiss: () -> Unit,
    onSave: (Routine) -> Unit,
    onDelete: (() -> Unit)?
) {
    var name by remember { mutableStateOf(routine.name) }
    var steps by remember { mutableStateOf(routine.steps) }
    val byId = activities.associateBy { it.id }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DiligentColors.SurfaceElevated,
        title = { Text(if (routine.id == 0L) "New routine" else "Edit routine", fontFamily = Cinzel, color = DiligentColors.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolField(name, { name = it }, "name, e.g. Morning stack", Modifier.fillMaxWidth())
                steps.forEachIndexed { index, step ->
                    val activity = byId[step.activityId]
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ToolChip(
                            text = "${index + 1}. ${activity?.name ?: "?"}".take(18),
                            selected = true,
                            onClick = {
                                // Cycle through activities
                                val at = activities.indexOfFirst { it.id == step.activityId }
                                val nextActivity = activities[(at + 1).mod(activities.size)]
                                steps = steps.toMutableList().also { it[index] = step.copy(activityId = nextActivity.id) }
                            }
                        )
                        val timed = activity != null && FocusMath.isTimeUnit(activity.unit)
                        var text by remember(index, step.activityId) {
                            mutableStateOf(if (timed) step.minutes.toString() else formatAmount(step.amount))
                        }
                        ToolField(
                            value = text,
                            onChange = { value ->
                                text = value
                                val number = value.replace(',', '.').toFloatOrNull() ?: return@ToolField
                                steps = steps.toMutableList().also {
                                    it[index] = if (timed) step.copy(minutes = number.toInt().coerceIn(1, 600)) else step.copy(amount = number)
                                }
                            },
                            hint = if (timed) "min" else "amount",
                            modifier = Modifier.width(64.dp),
                            keyboard = KeyboardType.Decimal
                        )
                        Text(if (timed) "min" else activity?.unit.orEmpty(), style = toolLabel(9.sp), color = DiligentColors.Gray600)
                        IconButton(onClick = { steps = RoutineMath.move(steps, index, -1) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.ArrowUpward, "Up", tint = DiligentColors.Gray700, modifier = Modifier.size(14.dp))
                        }
                        IconButton(onClick = { steps = RoutineMath.move(steps, index, 1) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.ArrowDownward, "Down", tint = DiligentColors.Gray700, modifier = Modifier.size(14.dp))
                        }
                        IconButton(onClick = { steps = steps.filterIndexed { i, _ -> i != index } }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, "Remove", tint = DiligentColors.Gray700, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                TextButton(onClick = { activities.firstOrNull()?.let { steps = steps + RoutineStep(it.id) } }) {
                    Text("+ STEP", fontFamily = DotMatrix, color = DiligentColors.White)
                }
                Text("tap a step's name to change its activity", style = toolLabel(9.sp), color = DiligentColors.Gray500)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && steps.isNotEmpty(),
                onClick = { onSave(routine.copy(name = name.trim(), steps = steps)) }
            ) { Text("SAVE", color = DiligentColors.White, fontFamily = DotMatrix) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("DELETE", color = ToolColors.Alert, fontFamily = DotMatrix) }
                }
                TextButton(onClick = onDismiss) { Text("CANCEL", color = DiligentColors.Gray600, fontFamily = DotMatrix) }
            }
        }
    )
}

/** Wall-clock now, once a second, only while the screen is visible. */
@Composable
private fun rememberSecondTicker(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000 - now % 1_000)
            }
        }
    }
    return now
}

private fun formatAmount(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
