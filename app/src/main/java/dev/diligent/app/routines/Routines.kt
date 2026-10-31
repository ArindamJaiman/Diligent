package dev.diligent.app.routines

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.focus.FocusTimer
import dev.diligent.app.focus.domain.FocusMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One step of a routine. Time activities (hrs/mins) run the focus timer for [minutes]; other
 * activities log [amount] when the step is done.
 */
@Serializable
data class RoutineStep(val activityId: Long, val minutes: Int = 10, val amount: Float = 1f)

/** An ordered chain of activities, e.g. a morning stack (Fabulous, Structured). */
@Serializable
data class Routine(val id: Long, val name: String, val steps: List<RoutineStep>)

/** The routine being run: which step, and when it began. */
@Serializable
data class RoutineRun(val routineId: Long, val step: Int, val stepStartedAt: Long)

@Serializable
private data class RoutineData(val routines: List<Routine> = emptyList(), val run: RoutineRun? = null)

data class RoutineState(val routines: List<Routine> = emptyList(), val run: RoutineRun? = null) {
    val running: Routine? get() = run?.let { r -> routines.firstOrNull { it.id == r.routineId } }
}

/** Pure arithmetic for the runner. */
object RoutineMath {

    /** Planned length: time steps by their minutes, other steps count as a minute each. */
    fun plannedMinutes(routine: Routine, isTime: (Long) -> Boolean): Int =
        routine.steps.sumOf { if (isTime(it.activityId)) it.minutes else 1 }

    /** Milliseconds left in the step, negative once it runs over. */
    fun remaining(step: RoutineStep, run: RoutineRun, now: Long): Long =
        step.minutes * FocusMath.MINUTE - (now - run.stepStartedAt)

    /** The routine with step [from] moved by [by] places (−1 up, +1 down); unchanged at the ends. */
    fun move(steps: List<RoutineStep>, from: Int, by: Int): List<RoutineStep> {
        val to = from + by
        if (from !in steps.indices || to !in steps.indices) return steps
        return steps.toMutableList().apply { add(to, removeAt(from)) }
    }
}

/**
 * Routines and the run in progress, in SharedPreferences (small, and nothing here needs a
 * migration). Time steps go through [FocusTimer], so they get its notification, widget and
 * session log, and a killed app picks up where it was.
 */
@Singleton
class RoutineStore @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: DiligentRepository,
    private val focusTimer: FocusTimer
) {
    private val prefs = context.getSharedPreferences("routines", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private val _state = MutableStateFlow(read())
    val state: StateFlow<RoutineState> = _state.asStateFlow()

    suspend fun save(routine: Routine) = edit { data ->
        val id = if (routine.id == 0L) (data.routines.maxOfOrNull { it.id } ?: 0L) + 1 else routine.id
        val saved = routine.copy(id = id)
        val list = if (data.routines.any { it.id == id }) data.routines.map { if (it.id == id) saved else it }
        else data.routines + saved
        data.copy(routines = list)
    }

    suspend fun delete(id: Long) {
        if (_state.value.run?.routineId == id) end()
        edit { it.copy(routines = it.routines.filterNot { r -> r.id == id }) }
    }

    suspend fun start(id: Long) {
        val routine = _state.value.routines.firstOrNull { it.id == id }?.takeIf { it.steps.isNotEmpty() } ?: return
        if (_state.value.run != null) end()
        val run = RoutineRun(routine.id, 0, System.currentTimeMillis())
        edit { it.copy(run = run) }
        beginStep(routine.steps[0])
    }

    /** Finishes the current step (logging it) and moves to the next, or ends after the last. */
    suspend fun next() = advance(log = true)

    /** Moves on without logging a counted step (time already spent on a timed step still counts). */
    suspend fun skip() = advance(log = false)

    suspend fun end() {
        val state = _state.value
        val step = state.run?.let { run -> state.running?.steps?.getOrNull(run.step) }
        if (step != null && focusTimer.current().session?.activityId == step.activityId) focusTimer.stop()
        edit { it.copy(run = null) }
    }

    private suspend fun advance(log: Boolean) {
        val state = _state.value
        val run = state.run ?: return
        val routine = state.running ?: return end()
        val step = routine.steps.getOrNull(run.step) ?: return end()
        val activity = repository.getActivityById(step.activityId)
        if (activity != null && FocusMath.isTimeUnit(activity.unit)) {
            if (focusTimer.current().session?.activityId == activity.id) focusTimer.stop()
        } else if (activity != null && log) {
            repository.addProgress(activity.id, step.amount)
        }
        val next = run.step + 1
        if (next >= routine.steps.size) {
            edit { it.copy(run = null) }
        } else {
            edit { it.copy(run = run.copy(step = next, stepStartedAt = System.currentTimeMillis())) }
            beginStep(routine.steps[next])
        }
    }

    private suspend fun beginStep(step: RoutineStep) {
        val activity = repository.getActivityById(step.activityId) ?: return
        if (FocusMath.isTimeUnit(activity.unit)) focusTimer.start(activity.id, pomodoro = false)
    }

    private suspend fun edit(change: (RoutineData) -> RoutineData) = mutex.withLock {
        val current = RoutineData(_state.value.routines, _state.value.run)
        val next = change(current)
        prefs.edit().putString(KEY, json.encodeToString(next)).apply()
        _state.value = RoutineState(next.routines, next.run)
    }

    private fun read(): RoutineState {
        val data = prefs.getString(KEY, null)
            ?.let { runCatching { json.decodeFromString<RoutineData>(it) }.getOrNull() }
            ?: RoutineData()
        return RoutineState(data.routines, data.run)
    }

    private companion object {
        const val KEY = "data"
    }
}
