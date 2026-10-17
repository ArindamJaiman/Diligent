package dev.diligent.app.goals

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.tools.ToolButton
import dev.diligent.app.tools.ToolCard
import dev.diligent.app.tools.ToolColors
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.tools.ToolNote
import dev.diligent.app.tools.ToolScaffold
import dev.diligent.app.tools.ToolSection
import dev.diligent.app.tools.toolLabel
import dev.diligent.app.ui.theme.DiligentColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Remembers dismissed or applied goal suggestions so they stay quiet for a while. */
@Singleton
class GoalsPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("goal_coach_prefs", Context.MODE_PRIVATE)
    private val _snoozed = MutableStateFlow(read())
    val snoozed: StateFlow<Map<Long, LocalDate>> = _snoozed

    /** Hides suggestions for [activityId] until [SNOOZE_DAYS] from [today]. */
    fun snooze(activityId: Long, today: LocalDate) {
        prefs.edit().putLong("snooze_$activityId", today.plusDays(SNOOZE_DAYS).toEpochDay()).apply()
        _snoozed.value = read()
    }

    private fun read(): Map<Long, LocalDate> = prefs.all.mapNotNull { (key, value) ->
        val id = key.removePrefix("snooze_").toLongOrNull() ?: return@mapNotNull null
        val day = (value as? Long)?.let(LocalDate::ofEpochDay) ?: return@mapNotNull null
        id to day
    }.toMap()

    companion object {
        const val SNOOZE_DAYS = 14L
    }
}

data class ActivityStreak(val activity: Activity, val state: StreakState)

data class GoalsUi(val streaks: List<ActivityStreak>, val suggestions: List<GoalSuggestion>)

/** Shared by the Goals screen, the Hub and the weekly review. */
object GoalsCalculator {
    fun build(
        activities: List<Activity>,
        progress: List<DailyProgress>,
        snoozed: Map<Long, LocalDate>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault()
    ): GoalsUi {
        val byActivity = progress.groupBy { it.activityId }
        val streaks = activities.map { activity ->
            val done = byActivity[activity.id].orEmpty().filter { it.isCompleted }
                .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
            ActivityStreak(activity, StreakEngine.compute(done, today))
        }
        val suggestions = activities.mapNotNull { activity ->
            val until = snoozed[activity.id]
            if (until != null && today.isBefore(until)) return@mapNotNull null
            val values = byActivity[activity.id].orEmpty()
                .mapNotNull { row -> runCatching { LocalDate.parse(row.date) }.getOrNull()?.let { it to row.progress } }
                .toMap()
            val created = Instant.ofEpochMilli(activity.createdAt).atZone(zone).toLocalDate()
            GoalCoach.suggest(activity, values, today, created)
        }
        return GoalsUi(streaks, suggestions)
    }
}

@HiltViewModel
class GoalsViewModel @Inject constructor(
    private val repository: DiligentRepository,
    private val prefs: GoalsPrefs
) : ViewModel() {

    val ui: StateFlow<GoalsUi?> = combine(
        repository.getAllActiveActivities(),
        repository.getAllProgressRange("0000-01-01", "9999-12-31"),
        prefs.snoozed,
        repository.todayFlow()
    ) { activities, progress, snoozed, todayText ->
        GoalsCalculator.build(activities, progress, snoozed, LocalDate.parse(todayText))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun apply(suggestion: GoalSuggestion) {
        viewModelScope.launch {
            val activity = repository.getActivityById(suggestion.activityId) ?: return@launch
            repository.updateActivity(activity.copy(dailyGoal = suggestion.suggested))
            prefs.snooze(activity.id, LocalDate.now())
        }
    }

    fun dismiss(suggestion: GoalSuggestion) = prefs.snooze(suggestion.activityId, LocalDate.now())
}

@Composable
fun GoalsScreen(onBack: () -> Unit, viewModel: GoalsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    ToolScaffold(title = "Streaks & Goals", onBack = onBack) {
        val state = ui ?: return@ToolScaffold

        ToolSection("GOAL COACH")
        if (state.suggestions.isEmpty()) {
            ToolCard {
                Text("No changes suggested", style = toolLabel(12.sp, bold = true), color = DiligentColors.White)
                Text(
                    "Goals are checked against your last ${GoalCoach.WINDOW_DAYS} finished days " +
                        "(needs ${GoalCoach.MIN_DAYS}+ days of history).",
                    style = toolLabel(10.sp), color = DiligentColors.Gray600
                )
            }
        }
        state.suggestions.forEach { s ->
            ToolCard {
                Text(s.activityName, style = toolLabel(13.sp, bold = true), color = DiligentColors.White)
                Text(
                    "${if (s.raise) "Raise" else "Lower"} goal ${ToolFormat.amount(s.current, s.unit)} → ${ToolFormat.amount(s.suggested, s.unit)}",
                    style = toolLabel(12.sp, bold = true),
                    color = if (s.raise) ToolColors.Good else ToolColors.Warn
                )
                Text(s.reason, style = toolLabel(10.sp), color = DiligentColors.Gray600)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolButton("APPLY", Modifier.weight(1f)) { viewModel.apply(s) }
                    ToolButton("NOT NOW", Modifier.weight(1f), outlined = true) { viewModel.dismiss(s) }
                }
            }
        }

        ToolSection("STREAK FREEZES")
        ToolCard {
            Text(
                "Every ${StreakEngine.DAYS_PER_FREEZE} completed days in a row earn a freeze (max ${StreakEngine.MAX_FREEZES}). " +
                    "A missed day uses one automatically, so a single bad day doesn't reset your streak.",
                style = toolLabel(10.sp), color = DiligentColors.Gray600
            )
            state.streaks.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = DiligentColors.Border)
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.activity.name, style = toolLabel(12.sp, bold = true), color = DiligentColors.White)
                        Text(
                            listOfNotNull(
                                if (item.state.nextFreezeIn == 0) "bank full" else "next freeze in ${item.state.nextFreezeIn}d",
                                item.state.savedDays.size.takeIf { it > 0 }?.let { "$it day${if (it > 1) "s" else ""} saved" }
                            ).joinToString(" · "),
                            style = toolLabel(10.sp), color = DiligentColors.Gray600
                        )
                    }
                    Text("🔥 ${item.state.current}", style = toolLabel(13.sp, bold = true), color = DiligentColors.White)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "❄ ${item.state.freezes}",
                        style = toolLabel(13.sp, bold = true),
                        color = if (item.state.freezes > 0) ToolColors.Info else DiligentColors.Gray500
                    )
                }
            }
        }
        ToolNote("Streaks everywhere in the app (dashboard, details, statistics) use these freezes.")
    }
}
