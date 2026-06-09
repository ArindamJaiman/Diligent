package dev.diligent.app.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.ActivityReminder
import dev.diligent.app.data.repository.DiligentRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for creating and editing activities.
 * Handles form state, validation, and persistence.
 */
@HiltViewModel
class ActivityEditViewModel @Inject constructor(
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

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveSuccess = MutableSharedFlow<Boolean>()
    val saveSuccess: SharedFlow<Boolean> = _saveSuccess.asSharedFlow()

    private val _nameError = MutableStateFlow<String?>(null)
    val nameError: StateFlow<String?> = _nameError.asStateFlow()

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
                    _dailyGoal.value = activity.dailyGoal.toString()
                    _icon.value = activity.icon
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
    fun setDailyGoal(value: String) { _dailyGoal.value = value }
    fun setIcon(value: String) { _icon.value = value }

    // ─── Save ───────────────────────────────────────────────────

    fun save() {
        viewModelScope.launch {
            // Validate
            if (_name.value.isBlank()) {
                _nameError.value = "Activity name is required"
                return@launch
            }

            val goal = _dailyGoal.value.toFloatOrNull() ?: 1f

            _isSaving.value = true

            try {
                if (isEditing) {
                    val existing = repository.getActivityById(activityId!!)
                    if (existing != null) {
                        repository.updateActivity(
                            existing.copy(
                                name = _name.value.trim(),
                                unit = _unit.value,
                                dailyGoal = goal,
                                icon = _icon.value
                            )
                        )
                    }
                } else {
                    repository.createActivity(
                        Activity(
                            name = _name.value.trim(),
                            unit = _unit.value,
                            dailyGoal = goal,
                            icon = _icon.value
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
}
