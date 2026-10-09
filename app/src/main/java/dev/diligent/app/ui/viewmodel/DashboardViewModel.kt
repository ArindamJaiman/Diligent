package dev.diligent.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.data.repository.DiligentRepository
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
    private val repository: DiligentRepository
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
        val streak: Int = 0
    ) {
        val currentProgress: Float get() = progress?.progress ?: 0f
        val isCompleted: Boolean get() = progress?.isCompleted == true
        val completionPercent: Float get() =
            if (activity.dailyGoal > 0) (currentProgress / activity.dailyGoal).coerceIn(0f, 1f)
            else 0f
        val lastUpdated: Long get() = progress?.lastUpdated ?: 0L
    }

    private val activities = repository.getAllActiveActivities()
    private val todayProgress = repository.getTodayProgress()

    /**
     * Streak per activity, recalculated whenever activities or today's progress change
     * (previously it was only computed once, so completing a goal never bumped the streak).
     */
    private val streakMap: Flow<Map<Long, Int>> =
        combine(activities, todayProgress) { list, _ -> list }
            .mapLatest { list -> list.associate { it.id to repository.calculateStreak(it.id) } }

    val activitiesWithProgress: StateFlow<List<ActivityWithProgress>> =
        combine(
            activities,
            todayProgress,
            streakMap.onStart { emit(emptyMap()) },
            _sortMode,
            _searchQuery
        ) { activities, todayProgress, streaks, sort, query ->
            val progressMap = todayProgress.associateBy { it.activityId }

            activities
                .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                .map { activity ->
                    ActivityWithProgress(
                        activity = activity,
                        progress = progressMap[activity.id],
                        streak = streaks[activity.id] ?: 0
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
