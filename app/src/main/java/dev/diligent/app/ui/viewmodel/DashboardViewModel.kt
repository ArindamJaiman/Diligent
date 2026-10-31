package dev.diligent.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.focus.FocusTimer
import dev.diligent.app.data.local.entity.FocusRecord
import dev.diligent.app.focus.data.FocusState
import dev.diligent.app.focus.notify.FocusNotifier
import dev.diligent.app.ui.components.FocusStart
import dev.diligent.app.tracking.Goals
import dev.diligent.app.tracking.TrackingSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the main dashboard screen.
 * Combines activities with their daily progress into a unified state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: DiligentRepository,
    private val focusTimer: FocusTimer
) : ViewModel() {

    // ─── Sort/Filter State ──────────────────────────────────────

    enum class SortMode { NAME, PROGRESS, COMPLETION, STREAK }

    private val _sortMode = MutableStateFlow(SortMode.NAME)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // ─── Data Streams ───────────────────────────────────────────

    /**
     * Combined activity + today's progress, displayed as ActivityWithProgress.
     */
    data class ActivityWithProgress(
        val activity: Activity,
        val progress: DailyProgress?,
        val streak: Int = 0,
        /** Streak unit, strength and today's schedule line; null until first computed. */
        val summary: TrackingSummary? = null
    ) {
        val currentProgress: Float get() = progress?.progress ?: 0f
        val isLimit: Boolean get() = Goals.isLimit(activity)
        /** Limits are never ticked off; their day is good until it goes [isOver]. */
        val isCompleted: Boolean get() = !isLimit && progress?.isCompleted == true
        val isOver: Boolean get() = isLimit && !Goals.isMet(activity, currentProgress)
        val isSkipped: Boolean get() = progress?.isSkipped == true
        /** Share of the goal reached, or for a limit, share of the allowance used. */
        val completionPercent: Float get() =
            if (activity.dailyGoal > 0) (currentProgress / activity.dailyGoal).coerceIn(0f, 1f)
            else if (isOver) 1f else 0f
        val lastUpdated: Long get() = progress?.lastUpdated ?: 0L
    }

    private val activities = repository.getAllActiveActivities()
    private val todayProgress = repository.getTodayProgress()

    /**
     * Streak per activity, recalculated whenever activities or today's progress change
     * (previously it was only computed once, so completing a goal never bumped the streak).
     */
    private val summaries: Flow<Map<Long, TrackingSummary>> =
        combine(activities, todayProgress) { list, _ -> list }
            .mapLatest { list -> list.associate { it.id to repository.trackingSummary(it) } }

    val activitiesWithProgress: StateFlow<List<ActivityWithProgress>> =
        combine(
            activities,
            todayProgress,
            summaries.onStart { emit(emptyMap()) },
            _sortMode,
            _searchQuery
        ) { activities, todayProgress, summaries, sort, query ->
            val progressMap = todayProgress.associateBy { it.activityId }

            activities
                .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                .map { activity ->
                    ActivityWithProgress(
                        activity = activity,
                        progress = progressMap[activity.id],
                        streak = summaries[activity.id]?.streak?.current ?: 0,
                        summary = summaries[activity.id]
                    )
                }
                .let { list ->
                    when (sort) {
                        SortMode.NAME -> list.sortedBy { it.activity.name.lowercase() }
                        SortMode.PROGRESS -> list.sortedByDescending { it.completionPercent }
                        SortMode.COMPLETION -> list.sortedByDescending { it.isCompleted }
                        SortMode.STREAK -> list.sortedByDescending { it.streak }
                    }
                }
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ─── Actions ────────────────────────────────────────────────

    fun incrementProgress(activityId: Long) {
        viewModelScope.launch {
            repository.incrementProgress(activityId)
        }
    }

    fun decrementProgress(activityId: Long) {
        viewModelScope.launch {
            repository.decrementProgress(activityId)
        }
    }

    fun toggleComplete(activityId: Long) {
        viewModelScope.launch {
            repository.toggleComplete(activityId)
        }
    }

    // ─── Focus timer ────────────────────────────────────────────

    /** The running timer (shared with the widget, tile and notification). */
    val focus: StateFlow<FocusState> = focusTimer.state

    fun startFocus(activityId: Long, start: FocusStart) {
        viewModelScope.launch {
            focusTimer.start(activityId, start.pomodoro, start.intention, start.targetMinutes, start.shield)
        }
    }

    fun stopFocus() {
        viewModelScope.launch {
            if (!focusTimer.stop()) {
                val until = focusTimer.current().session?.lockedUntil ?: 0L
                _messages.emit("Hard mode · keep going until ${FocusNotifier.formatClock(until)}")
            }
        }
    }

    fun interruptFocus() {
        viewModelScope.launch { focusTimer.interrupt() }
    }

    /** The session that just ended, for the "how focused were you?" prompt. */
    val finishedFocus: StateFlow<FocusRecord?> = focusTimer.finished

    fun rateFinished(rating: Int) {
        val record = finishedFocus.value ?: return
        viewModelScope.launch { focusTimer.rate(record.id, rating) }
    }

    fun dismissFinished() = focusTimer.dismissFinished()

    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun deleteActivity(activity: Activity) {
        viewModelScope.launch {
            repository.deleteActivity(activity)
        }
    }

    fun archiveActivity(activityId: Long) {
        viewModelScope.launch {
            repository.archiveActivity(activityId)
        }
    }
}
