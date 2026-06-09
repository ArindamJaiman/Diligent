package dev.diligent.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.data.repository.DiligentRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * ViewModel for the Statistics screen.
 * Aggregates completion rates, streaks, total hours, and chart data.
 */
@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val repository: DiligentRepository
) : ViewModel() {

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    // ─── State ──────────────────────────────────────────────────

    data class StatsState(
        val dailyCompletionRate: Float = 0f,
        val weeklyCompletionRate: Float = 0f,
        val monthlyCompletionRate: Float = 0f,
        val totalHoursTracked: Float = 0f,
        val activityStreaks: Map<String, Int> = emptyMap(),
        val weeklyChartData: List<Pair<String, Float>> = emptyList(),
        val isLoading: Boolean = true
    )

    private val _state = MutableStateFlow(StatsState())
    val state: StateFlow<StatsState> = _state.asStateFlow()

    init {
        loadStatistics()
    }

    private fun loadStatistics() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            val today = repository.today()
            val weekStart = repository.startOfWeek()
            val monthStart = repository.startOfMonth()

            // Completion rates
            val dailyRate = repository.getCompletionRate(today, today)
            val weeklyRate = repository.getCompletionRate(weekStart, today)
            val monthlyRate = repository.getCompletionRate(monthStart, today)

            // Total hours
            val totalHours = repository.getTotalHoursTracked(monthStart, today)

            // Streaks per activity
            val activities = repository.getAllActiveSnapshot()
            val streaks = activities.associate { activity ->
                activity.name to repository.calculateStreak(activity.id)
            }

            // Weekly chart data (last 7 days, daily completion %)
            val chartData = (6 downTo 0).map { daysAgo ->
                val date = LocalDate.now().minusDays(daysAgo.toLong())
                val dateStr = date.format(dateFormatter)
                val rate = repository.getCompletionRate(dateStr, dateStr)
                val label = date.dayOfWeek.name.take(3)
                label to rate
            }

            _state.update {
                StatsState(
                    dailyCompletionRate = dailyRate,
                    weeklyCompletionRate = weeklyRate,
                    monthlyCompletionRate = monthlyRate,
                    totalHoursTracked = totalHours,
                    activityStreaks = streaks,
                    weeklyChartData = chartData,
                    isLoading = false
                )
            }
        }
    }

    fun refresh() = loadStatistics()
}
