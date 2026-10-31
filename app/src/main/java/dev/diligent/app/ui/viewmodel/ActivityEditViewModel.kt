package dev.diligent.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.ActivityReminder
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.planning.Commitment
import dev.diligent.app.tracking.DayClock
import dev.diligent.app.tracking.Goals
import dev.diligent.app.tracking.Schedule
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * ViewModel for creating and editing activities.
 * Handles form state, validation, and persistence.
 */
@HiltViewModel
class ActivityEditViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DiligentRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    // Activity ID from navigation args (null = creating new)
    private val activityId: Long? = savedStateHandle.get<Long>("activityId")
        ?.takeIf { it > 0 }

    val isEditing: Boolean get() = activityId != null

    // ─── Form State ─────────────────────────────────────────────

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _unit = MutableStateFlow("hrs")
    val unit: StateFlow<String> = _unit.asStateFlow()

    private val _dailyGoal = MutableStateFlow("1")
    val dailyGoal: StateFlow<String> = _dailyGoal.asStateFlow()

    private val _icon = MutableStateFlow("radio_button_unchecked")
    val icon: StateFlow<String> = _icon.asStateFlow()

    // When it's due: daily, on chosen weekdays, N times a week, or every N days
    private val _scheduleType = MutableStateFlow(Schedule.DAILY)
    val scheduleType: StateFlow<String> = _scheduleType.asStateFlow()

    private val _scheduleDays = MutableStateFlow(Schedule.ALL_DAYS)
    val scheduleDays: StateFlow<Int> = _scheduleDays.asStateFlow()

    private val _scheduleCount = MutableStateFlow(3)
    val scheduleCount: StateFlow<Int> = _scheduleCount.asStateFlow()

    // Reach the goal, stay under it, or quit
    private val _goalType = MutableStateFlow(Goals.AT_LEAST)
    val goalType: StateFlow<String> = _goalType.asStateFlow()

    // Long-term target: total in the activity's unit by a date
    private val _targetTotal = MutableStateFlow("")
    val targetTotal: StateFlow<String> = _targetTotal.asStateFlow()

    private val _targetDate = MutableStateFlow("")
    val targetDate: StateFlow<String> = _targetDate.asStateFlow()

    private val _isPrivate = MutableStateFlow(false)
    val isPrivate: StateFlow<Boolean> = _isPrivate.asStateFlow()

    /** "Goal 1 hrs from 7 Nov": an easier goal waiting out commitment mode's week. */
    private val _pendingNote = MutableStateFlow<String?>(null)
    val pendingNote: StateFlow<String?> = _pendingNote.asStateFlow()

    val commitmentOn: Boolean get() = Commitment.config(context).on

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveSuccess = MutableSharedFlow<Boolean>()
    val saveSuccess: SharedFlow<Boolean> = _saveSuccess.asSharedFlow()

    private val _nameError = MutableStateFlow<String?>(null)
    val nameError: StateFlow<String?> = _nameError.asStateFlow()

    private val _goalError = MutableStateFlow<String?>(null)
    val goalError: StateFlow<String?> = _goalError.asStateFlow()

    // Reminders for this activity
    val reminders: StateFlow<List<ActivityReminder>> =
        activityId?.let { id ->
            repository.getRemindersForActivity(id)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        } ?: MutableStateFlow(emptyList())

    init {
        // Load existing activity data if editing
        activityId?.let { id ->
            viewModelScope.launch {
                repository.getActivityById(id)?.let { activity ->
                    _name.value = activity.name
                    _unit.value = activity.unit
                    _dailyGoal.value = activity.dailyGoal.let {
                        if (it == it.toInt().toFloat()) it.toInt().toString() else it.toString()
                    }
                    _icon.value = activity.icon
                    _scheduleType.value = activity.scheduleType
                    _scheduleDays.value = activity.scheduleDays
                    if (activity.scheduleType == Schedule.WEEKLY || activity.scheduleType == Schedule.INTERVAL) {
                        _scheduleCount.value = activity.scheduleCount
                    }
                    _goalType.value = activity.goalType
                    _targetTotal.value = if (activity.targetTotal > 0f) formatNumber(activity.targetTotal) else ""
                    _targetDate.value = activity.targetDate
                    _isPrivate.value = activity.isPrivate
                    _pendingNote.value = pendingNote(activity)
                }
            }
        }
    }

    // ─── Setters ────────────────────────────────────────────────

    fun setName(value: String) {
        _name.value = value
        _nameError.value = null
    }

    fun setUnit(value: String) { _unit.value = value }
    fun setDailyGoal(value: String) {
        _dailyGoal.value = value
        _goalError.value = null
    }
    fun setIcon(value: String) { _icon.value = value }

    fun setScheduleType(value: String) {
        if (_scheduleType.value != value) {
            _scheduleCount.value = if (value == Schedule.INTERVAL) 2 else 3
        }
        _scheduleType.value = value
    }

    fun toggleScheduleDay(bit: Int) {
        val next = _scheduleDays.value xor bit
        if (next and Schedule.ALL_DAYS != 0) _scheduleDays.value = next // keep at least one day
    }

    fun setScheduleCount(value: Int) {
        _scheduleCount.value = if (_scheduleType.value == Schedule.WEEKLY) value.coerceIn(1, 7) else value.coerceIn(2, 30)
    }

    fun setGoalType(value: String) {
        _goalType.value = value
        _goalError.value = null
    }

    fun setTargetTotal(value: String) { _targetTotal.value = value }
    fun setTargetDate(value: String) { _targetDate.value = value }
    fun setPrivate(value: Boolean) { _isPrivate.value = value }

    // ─── Save ───────────────────────────────────────────────────

    fun save() {
        viewModelScope.launch {
            // Validate
            if (_name.value.isBlank()) {
                _nameError.value = "Activity name is required"
                return@launch
            }

            // A zero/negative goal would mark the activity complete before any work. Quitting
            // has no amount: any progress is a slip.
            val goal = if (_goalType.value == Goals.QUIT) 0f else _dailyGoal.value.trim().replace(',', '.').toFloatOrNull()
            if (goal == null || goal.isNaN() || goal.isInfinite() || (_goalType.value != Goals.QUIT && goal <= 0f)) {
                _goalError.value = "Enter a goal greater than 0"
                return@launch
            }
            val target = _targetTotal.value.trim().replace(',', '.').toFloatOrNull()?.takeIf { it > 0f } ?: 0f
            val schedule = Triple(
                _scheduleType.value,
                if (_scheduleType.value == Schedule.WEEKDAYS) _scheduleDays.value else Schedule.ALL_DAYS,
                if (_scheduleType.value == Schedule.WEEKLY || _scheduleType.value == Schedule.INTERVAL) _scheduleCount.value else 1
            )

            _isSaving.value = true

            try {
                if (isEditing) {
                    val existing = repository.getActivityById(activityId!!)
                    if (existing != null) {
                        val edited = existing.copy(
                            name = _name.value.trim(),
                            unit = _unit.value,
                            dailyGoal = goal,
                            icon = _icon.value,
                            scheduleType = schedule.first,
                            scheduleDays = schedule.second,
                            scheduleCount = schedule.third,
                            goalType = _goalType.value,
                            targetTotal = target,
                            targetDate = if (target > 0f) _targetDate.value else "",
                            isPrivate = _isPrivate.value
                        )
                        // Commitment mode parks an easier goal for a week (see Commitment).
                        val saved = if (existing.dailyGoal == goal && existing.pendingGoalDate.isNotEmpty()) {
                            edited
                        } else {
                            Commitment.apply(existing, edited, commitmentOn, DayClock.today())
                        }
                        repository.updateActivity(saved)
                    }
                } else {
                    repository.createActivity(
                        Activity(
                            name = _name.value.trim(),
                            unit = _unit.value,
                            dailyGoal = goal,
                            icon = _icon.value,
                            scheduleType = schedule.first,
                            scheduleDays = schedule.second,
                            scheduleCount = schedule.third,
                            goalType = _goalType.value,
                            targetTotal = target,
                            targetDate = if (target > 0f) _targetDate.value else "",
                            isPrivate = _isPrivate.value
                        )
                    )
                }
                _saveSuccess.emit(true)
            } catch (e: Exception) {
                _saveSuccess.emit(false)
            } finally {
                _isSaving.value = false
            }
        }
    }

    // ─── Reminders ──────────────────────────────────────────────

    fun addReminder(hour: Int, minute: Int, daysOfWeek: Int = 127) {
        activityId ?: return
        viewModelScope.launch {
            repository.createReminder(
                ActivityReminder(
                    activityId = activityId,
                    hour = hour,
                    minute = minute,
                    daysOfWeek = daysOfWeek
                )
            )
        }
    }

    fun deleteReminder(reminder: ActivityReminder) {
        viewModelScope.launch {
            repository.deleteReminder(reminder)
        }
    }

    fun toggleReminder(reminder: ActivityReminder) {
        viewModelScope.launch {
            repository.updateReminder(reminder.copy(isEnabled = !reminder.isEnabled))
        }
    }

    private fun pendingNote(activity: Activity): String? {
        if (activity.pendingGoalDate.isEmpty()) return null
        val date = runCatching { LocalDate.parse(activity.pendingGoalDate) }.getOrNull() ?: return null
        return "Goal changes to ${formatNumber(activity.pendingGoal)} ${activity.unit} on " +
            date.format(DateTimeFormatter.ofPattern("d MMM")) + " (commitment mode)"
    }

    private fun formatNumber(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
}
