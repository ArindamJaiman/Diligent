package dev.diligent.app.review

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.R
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.data.repository.GithubContributionFetcher
import dev.diligent.app.goals.GoalCoach
import dev.diligent.app.goals.GoalSuggestion
import dev.diligent.app.goals.GoalsCalculator
import dev.diligent.app.goals.GoalsPrefs
import dev.diligent.app.leetcode.data.LeetCodePrefs
import dev.diligent.app.leetcode.data.LeetCodeSnapshot
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.tools.ToolRoutes
import dev.diligent.app.tools.openToolIntent
import dev.diligent.app.tracking.DayClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

data class ActivityWeek(
    val name: String,
    val unit: String,
    val total: Float,
    val goalTotal: Float,
    val daysHit: Int,
    val days: Int,
    val streak: Int
)

data class WeeklyReviewData(
    val weekStart: LocalDate,
    val weekEnd: LocalDate,
    /** Days of the week that have happened (7 for a finished week). */
    val days: Int,
    val activities: List<ActivityWeek>,
    /** Goal-days hit ÷ goal-days possible. */
    val completion: Float,
    val hours: Float,
    val bestDay: LocalDate?,
    val bestDayHits: Int,
    val leetcodeSubmissions: Int?,
    val leetcodeActiveDays: Int?,
    val leetcodeSolvedTotal: Int?,
    val github: List<Pair<String, Int>>,
    val suggestion: GoalSuggestion?
) {
    val completionPercent: Int get() = (completion * 100).roundToInt()
    val weekNumber: Int get() = weekStart.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
}

/** Pure: everything the review shows, from data the app already has. */
object WeeklyReviewBuilder {

    fun weekStart(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun build(
        activities: List<Activity>,
        progress: List<DailyProgress>,
        weekStart: LocalDate,
        today: LocalDate,
        streaks: Map<Long, Int>,
        leetcode: LeetCodeSnapshot?,
        github: List<Pair<String, Set<LocalDate>>>,
        suggestion: GoalSuggestion?
    ): WeeklyReviewData {
        val weekEnd = weekStart.plusDays(6)
        val lastDay = if (today.isBefore(weekEnd)) today else weekEnd
        val days = generateSequence(weekStart) { it.plusDays(1) }.takeWhile { !it.isAfter(lastDay) }.toList()
        val inWeek = progress.filter { row -> row.date >= weekStart.toString() && row.date <= lastDay.toString() }
        val byActivity = inWeek.groupBy { it.activityId }

        val rows = activities.map { a ->
            val list = byActivity[a.id].orEmpty()
            ActivityWeek(
                name = a.name,
                unit = a.unit,
                total = list.sumOf { it.progress.toDouble() }.toFloat(),
                goalTotal = a.dailyGoal * days.size,
                daysHit = list.count { it.isCompleted },
                days = days.size,
                streak = streaks[a.id] ?: 0
            )
        }
        val possible = activities.size * days.size
        val hit = rows.sumOf { it.daysHit }
        val hits = days.associateWith { day -> inWeek.count { it.date == day.toString() && it.isCompleted } }
        val best = hits.maxByOrNull { it.value }?.takeIf { it.value > 0 }

        val hours = activities.sumOf { a ->
            val total = byActivity[a.id].orEmpty().sumOf { it.progress.toDouble() }
            when (a.unit.trim().lowercase()) {
                "hrs", "hr", "hours", "hour", "h" -> total
                "mins", "min", "minutes" -> total / 60.0
                else -> 0.0
            }
        }.toFloat()

        val lcDays = leetcode?.calendar?.filterKeys { it >= weekStart.toString() && it <= weekEnd.toString() }

        return WeeklyReviewData(
            weekStart = weekStart,
            weekEnd = weekEnd,
            days = days.size,
            activities = rows.sortedByDescending { if (it.days == 0) 0f else it.daysHit.toFloat() / it.days },
            completion = if (possible == 0) 0f else hit.toFloat() / possible,
            hours = hours,
            bestDay = best?.key,
            bestDayHits = best?.value ?: 0,
            leetcodeSubmissions = lcDays?.values?.sum(),
            leetcodeActiveDays = lcDays?.count { it.value > 0 },
            leetcodeSolvedTotal = leetcode?.solved?.all,
            github = github.map { (user, active) -> user to active.count { !it.isBefore(weekStart) && !it.isAfter(weekEnd) } },
            suggestion = suggestion
        )
    }

    fun summaryLines(data: WeeklyReviewData): List<String> = buildList {
        add("${data.completionPercent}% of goals hit · ${ToolFormat.amount((data.hours * 10).roundToInt() / 10f, "hrs")} logged")
        data.bestDay?.let { add("Best day: ${it.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())} (${data.bestDayHits} goals)") }
        data.activities.take(3).forEach { add("${it.name}: ${it.daysHit}/${it.days} days") }
        data.leetcodeSubmissions?.let { add("LeetCode: $it submissions on ${data.leetcodeActiveDays ?: 0} days") }
        data.github.filter { it.second > 0 }.forEach { add("GitHub ${it.first}: ${it.second}/7 days") }
        data.suggestion?.let {
            add("Tip: ${if (it.raise) "raise" else "lower"} ${it.activityName} to ${ToolFormat.amount(it.suggested, it.unit)}")
        }
    }
}

data class WeeklyReviewSettings(val enabled: Boolean = true, val day: DayOfWeek = DayOfWeek.SUNDAY, val hour: Int = 20, val minute: Int = 0)

@Singleton
class WeeklyReviewService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DiligentRepository,
    private val leetCodePrefs: LeetCodePrefs,
    private val goalsPrefs: GoalsPrefs
) {
    private val prefs = context.getSharedPreferences("weekly_review_prefs", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<WeeklyReviewSettings> = _settings

    fun update(transform: (WeeklyReviewSettings) -> WeeklyReviewSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putBoolean("enabled", next.enabled)
            .putInt("day", next.day.value)
            .putInt("hour", next.hour)
            .putInt("minute", next.minute)
            .apply()
        _settings.value = next
    }

    private fun readSettings() = WeeklyReviewSettings(
        enabled = prefs.getBoolean("enabled", true),
        day = DayOfWeek.of(prefs.getInt("day", DayOfWeek.SUNDAY.value).coerceIn(1, 7)),
        hour = prefs.getInt("hour", 20),
        minute = prefs.getInt("minute", 0)
    )

    /** @param weeksAgo 0 = this week (Monday to today), 1 = last week. */
    suspend fun build(weeksAgo: Long = 0, today: LocalDate = DayClock.today()): WeeklyReviewData {
        val start = WeeklyReviewBuilder.weekStart(today).minusWeeks(weeksAgo)
        val activities = repository.getAllActiveSnapshot()
        val history = repository.getAllProgressRangeSnapshot(start.minusDays(GoalCoach.WINDOW_DAYS + 1).toString(), start.plusDays(6).toString())
        val streaks = activities.associate { it.id to repository.calculateStreak(it.id) }
        val goals = GoalsCalculator.build(activities, history, goalsPrefs.snoozed.value, today)
        val github = repository.getGithubUsersSnapshot().map { slot ->
            val cached = repository.getGithubContributionSnapshot(slot.username)
            val active = cached?.let { GithubContributionFetcher.decode(it.contributionsJson) }.orEmpty()
                .filter { it.level > 0 }
                .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
                .toSet()
            slot.username to active
        }.filter { it.second.isNotEmpty() }
        return WeeklyReviewBuilder.build(
            activities = activities,
            progress = history,
            weekStart = start,
            today = today,
            streaks = streaks,
            leetcode = leetCodePrefs.current().snapshot,
            github = github,
            suggestion = goals.suggestions.firstOrNull()
        )
    }

    suspend fun runScheduled() {
        if (!_settings.value.enabled) return
        val data = build()
        if (data.activities.isEmpty()) return
        notify(data)
    }

    fun nextRun(now: ZonedDateTime): ZonedDateTime? {
        val s = _settings.value
        if (!s.enabled) return null
        var next = now.with(TemporalAdjusters.nextOrSame(s.day)).with(LocalTime.of(s.hour, s.minute)).withSecond(0).withNano(0)
        if (!next.isAfter(now.plusSeconds(30))) next = next.plusWeeks(1)
        return next
    }

    @SuppressLint("MissingPermission")
    private fun notify(data: WeeklyReviewData) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Weekly review", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Your week in numbers, once a week"
            }
        )
        val lines = WeeklyReviewBuilder.summaryLines(data)
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID, openToolIntent(context, ToolRoutes.WEEKLY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Week ${data.weekNumber} review · ${data.completionPercent}% of goals")
            .setContentText(lines.drop(1).firstOrNull() ?: lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setColor(0xFFFFFFFF.toInt())
            .build()
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return
        try {
            compat.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val CHANNEL = "weekly_review"
        private const val NOTIFICATION_ID = 3401
    }
}
