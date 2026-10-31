package dev.diligent.app.goals

import dev.diligent.app.data.local.entity.Activity
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.roundToInt

data class StreakState(
    /** Completed days in the current streak (freeze days keep it alive but don't add to it). */
    val current: Int,
    /** Freezes banked and ready to cover a missed day. */
    val freezes: Int,
    /** Missed days in the current streak that a freeze covered. */
    val savedDays: List<LocalDate>,
    /** Completed days still needed to earn the next freeze (0 when the bank is full). */
    val nextFreezeIn: Int
)

/**
 * Streaks with freezes, computed from history alone (nothing extra is stored).
 *
 * Every [DAYS_PER_FREEZE] completed days in a row earn one freeze, up to [MAX_FREEZES].
 * A missed day is covered automatically by a banked freeze, so one bad day doesn't wipe a
 * long streak; with no freeze left, the streak resets. Today never counts as missed — it's
 * still open.
 */
object StreakEngine {

    const val DAYS_PER_FREEZE = 7
    const val MAX_FREEZES = 2

    /**
     * @param neutral days that neither add to nor break the streak: rest days of a schedule
     *   and skipped days (see Schedule.neutralDays)
     */
    fun compute(
        completed: Collection<LocalDate>,
        today: LocalDate,
        neutral: (LocalDate) -> Boolean = { false }
    ): StreakState {
        val done = completed.filter { !it.isAfter(today) }.toSortedSet()
        if (done.isEmpty()) return StreakState(0, 0, emptyList(), DAYS_PER_FREEZE)

        var streak = 0
        var run = 0
        var freezes = 0
        val saved = mutableListOf<LocalDate>()

        var day = done.first()
        while (!day.isAfter(today)) {
            when {
                day in done -> {
                    streak++
                    run++
                    if (run % DAYS_PER_FREEZE == 0 && freezes < MAX_FREEZES) freezes++
                }
                day == today || neutral(day) -> Unit
                streak > 0 && freezes > 0 -> {
                    freezes--
                    saved += day
                    run = 0
                }
                else -> {
                    streak = 0
                    run = 0
                    saved.clear()
                }
            }
            day = day.plusDays(1)
        }
        val nextIn = if (freezes >= MAX_FREEZES) 0 else DAYS_PER_FREEZE - run % DAYS_PER_FREEZE
        return StreakState(streak, freezes, saved.toList(), nextIn)
    }
}

data class GoalSuggestion(
    val activityId: Long,
    val activityName: String,
    val unit: String,
    val current: Float,
    val suggested: Float,
    val raise: Boolean,
    val reason: String
)

/**
 * Suggests a realistic daily goal from the last [WINDOW_DAYS] finished days.
 *
 * - Hit on fewer than 35% of days, with at least [MIN_ACTIVE_DAYS] days of real progress →
 *   lower it to what a typical active day reaches.
 * - Hit on 90%+ of days and averaging 15%+ over → raise it towards the average.
 *
 * Needs at least [MIN_DAYS] days of history; today is ignored because it isn't over.
 */
object GoalCoach {

    const val WINDOW_DAYS = 14L
    const val MIN_DAYS = 7
    const val MIN_ACTIVE_DAYS = 3

    fun suggest(activity: Activity, progress: Map<LocalDate, Float>, today: LocalDate, createdDate: LocalDate): GoalSuggestion? {
        val goal = activity.dailyGoal
        if (goal <= 0f) return null
        val start = maxOf(today.minusDays(WINDOW_DAYS), createdDate)
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { it.isBefore(today) }.toList()
        if (days.size < MIN_DAYS) return null

        val values = days.map { progress[it] ?: 0f }
        val hits = values.count { it >= goal }
        val hitRate = hits.toFloat() / days.size
        val step = step(activity.unit)

        if (hitRate < 0.35f) {
            // Days with nothing logged mean "not tracking", not "goal too high": only suggest
            // a lower goal once there are enough real attempts to size it from.
            val active = values.filter { it > 0f }.sorted()
            if (active.size < MIN_ACTIVE_DAYS) return null
            val suggested = roundTo(percentile(active, 0.6f), step).coerceAtLeast(step)
            if (suggested >= goal - step / 2f) return null
            return GoalSuggestion(
                activity.id, activity.name, activity.unit, goal, suggested, raise = false,
                reason = "Hit $hits of ${days.size} days · a typical active day is ${fmt(percentile(active, 0.5f), activity.unit)}"
            )
        }
        if (hitRate >= 0.9f) {
            val mean = values.average().toFloat()
            if (mean < goal * 1.15f) return null
            val suggested = roundTo(minOf(mean, goal * 1.5f), step)
            if (suggested <= goal + step / 2f) return null
            return GoalSuggestion(
                activity.id, activity.name, activity.unit, goal, suggested, raise = true,
                reason = "Hit $hits of ${days.size} days · averaging ${fmt(mean, activity.unit)}"
            )
        }
        return null
    }

    fun step(unit: String): Float = when (unit.trim().lowercase()) {
        "hrs", "hr", "hours", "hour", "h" -> 0.25f
        "mins", "min", "minutes", "minute", "m" -> 5f
        else -> 1f
    }

    internal fun roundTo(value: Float, step: Float): Float = ((value / step).roundToInt() * step)

    internal fun percentile(sorted: List<Float>, p: Float): Float {
        if (sorted.isEmpty()) return 0f
        val index = (ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun fmt(value: Float, unit: String): String {
        val rounded = (value * 100).roundToInt() / 100f
        val number = if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
        return "$number $unit"
    }
}
