package dev.diligent.app.tracking

import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.goals.StreakEngine
import dev.diligent.app.goals.StreakState
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How often an activity is due, stored in [Activity.scheduleType], [Activity.scheduleDays]
 * and [Activity.scheduleCount]. Kept free of Android so it can be unit tested.
 */
object Schedule {
    /** Every day. */
    const val DAILY = "daily"

    /** Only on the days in scheduleDays; the others are rest days. */
    const val WEEKDAYS = "weekdays"

    /** scheduleCount times a week (Monday to Sunday), on any days. */
    const val WEEKLY = "weekly"

    /** Once every scheduleCount days. */
    const val INTERVAL = "interval"

    const val ALL_DAYS = 127

    /** Mon=1, Tue=2 … Sun=64, the same bitmask as ActivityReminder.daysOfWeek. */
    fun dayBit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    fun isWeekly(activity: Activity) = activity.scheduleType == WEEKLY

    /** False on a rest day of a weekday schedule. Other schedules can fall on any day. */
    fun isScheduledDay(activity: Activity, date: LocalDate): Boolean =
        activity.scheduleType != WEEKDAYS || activity.scheduleDays and dayBit(date.dayOfWeek) != 0

    /**
     * Days that neither count towards nor break a streak: skipped days, rest days of a
     * weekday schedule, and for "every N days" the days before the next one falls due.
     */
    fun neutralDays(activity: Activity, done: Set<LocalDate>, skipped: Set<LocalDate>): (LocalDate) -> Boolean {
        val interval = activity.scheduleCount.coerceAtLeast(1).toLong()
        return { day ->
            day in skipped ||
                !isScheduledDay(activity, day) ||
                (activity.scheduleType == INTERVAL && (1 until interval).any { day.minusDays(it) in done })
        }
    }

    /** Times an activity is expected per day, on average (weekly 3× → 3/7). */
    fun frequency(activity: Activity): Double = when (activity.scheduleType) {
        WEEKDAYS -> Integer.bitCount(activity.scheduleDays and ALL_DAYS).coerceAtLeast(1) / 7.0
        WEEKLY -> activity.scheduleCount.coerceIn(1, 7) / 7.0
        INTERVAL -> 1.0 / activity.scheduleCount.coerceAtLeast(1)
        else -> 1.0
    }

    /** Due days between [start] and [end] inclusive, as a fraction for weekly and interval schedules. */
    fun dueDays(activity: Activity, start: LocalDate, end: LocalDate): Double {
        if (end.isBefore(start)) return 0.0
        val days = ChronoUnit.DAYS.between(start, end) + 1
        return when (activity.scheduleType) {
            WEEKDAYS -> generateSequence(start) { it.plusDays(1) }.take(days.toInt())
                .count { isScheduledDay(activity, it) }.toDouble()
            else -> days * frequency(activity)
        }
    }

    /** Whether [activity] still asks for something on [today] by its schedule (done or not). */
    fun isDue(activity: Activity, done: Set<LocalDate>, skipped: Set<LocalDate>, today: LocalDate): Boolean {
        if (today in skipped) return false
        return when (activity.scheduleType) {
            WEEKDAYS -> isScheduledDay(activity, today)
            WEEKLY -> today in done ||
                done.count { !it.isBefore(weekStart(today)) && it.isBefore(today) } < activity.scheduleCount.coerceIn(1, 7)
            INTERVAL -> today in done || (1 until activity.scheduleCount.coerceAtLeast(1).toLong()).none { today.minusDays(it) in done }
            else -> true
        }
    }

    /** "every day", "Mon · Wed · Fri", "3× a week", "every 2 days". */
    fun describe(activity: Activity): String = when (activity.scheduleType) {
        WEEKDAYS -> {
            val mask = activity.scheduleDays and ALL_DAYS
            when (mask) {
                ALL_DAYS -> "every day"
                0b0011111 -> "weekdays"
                0b1100000 -> "weekends"
                else -> DayOfWeek.entries.filter { mask and dayBit(it) != 0 }
                    .joinToString(" · ") { it.name.take(1) + it.name.substring(1, 3).lowercase() }
            }
        }
        WEEKLY -> "${activity.scheduleCount.coerceIn(1, 7)}× a week"
        INTERVAL -> if (activity.scheduleCount <= 1) "every day" else "every ${activity.scheduleCount} days"
        else -> "every day"
    }

    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * Today's line on the card: null when it's an ordinary due day, otherwise "rest day",
     * "2/3 this week", "week done" or "next in 2 days".
     */
    fun caption(activity: Activity, done: Set<LocalDate>, skipped: Set<LocalDate>, today: LocalDate): String? {
        if (today in skipped) return "skipped"
        return when (activity.scheduleType) {
            WEEKDAYS -> if (isScheduledDay(activity, today)) null else "rest day"
            WEEKLY -> {
                val target = activity.scheduleCount.coerceIn(1, 7)
                val count = done.count { !it.isBefore(weekStart(today)) && !it.isAfter(today) }
                if (count >= target) "week done · $count/$target" else "$count/$target this week"
            }
            INTERVAL -> {
                val interval = activity.scheduleCount.coerceAtLeast(1)
                if (today in done) return null
                val last = done.filter { it.isBefore(today) }.maxOrNull() ?: return null
                val next = last.plusDays(interval.toLong())
                val inDays = ChronoUnit.DAYS.between(today, next)
                when {
                    inDays <= 0 -> null
                    inDays == 1L -> "next tomorrow"
                    else -> "next in $inDays days"
                }
            }
            else -> null
        }
    }
}

/** What counts as a good day, stored in [Activity.goalType]. */
object Goals {
    /** Reach at least the daily goal (the default). */
    const val AT_LEAST = "at_least"

    /** A limit: the day counts while you stay at or under the daily goal. */
    const val AT_MOST = "at_most"

    /** Quit: any progress at all is a slip. */
    const val QUIT = "quit"

    fun isLimit(activity: Activity) = activity.goalType == AT_MOST || activity.goalType == QUIT

    fun isMet(activity: Activity, progress: Float): Boolean = when (activity.goalType) {
        AT_MOST -> progress <= activity.dailyGoal
        QUIT -> progress <= 0f
        else -> activity.dailyGoal > 0f && progress >= activity.dailyGoal
    }

    /** 0..1 credit for a day: partial progress counts for "at least", limits are all or nothing. */
    fun credit(activity: Activity, progress: Float): Double = when {
        isLimit(activity) -> if (isMet(activity, progress)) 1.0 else 0.0
        activity.dailyGoal <= 0f -> 0.0
        else -> (progress / activity.dailyGoal).toDouble().coerceIn(0.0, 1.0)
    }

    /** "max 2 hrs", "quit", or "" for an ordinary goal. */
    fun label(activity: Activity): String = when (activity.goalType) {
        AT_MOST -> "max"
        QUIT -> "quit"
        else -> ""
    }
}

/** Streak, strength and today's caption for one activity, from its history. */
data class TrackingSummary(
    val streak: StreakState,
    /** "days" or "weeks" (weekly schedules count met weeks). */
    val streakUnit: String,
    /** 0..1 habit strength, see [HabitStrength]. */
    val strength: Double,
    val caption: String?
)

object Tracking {

    /**
     * @param progress logged values by day (any range; older than a year is ignored)
     * @param skipped days marked as skipped
     * @param completed days stored as complete, which win over [progress] for "at least" goals
     *   (a day ticked off by hand, or un-ticked)
     */
    fun summarize(
        activity: Activity,
        progress: Map<LocalDate, Float>,
        skipped: Set<LocalDate>,
        today: LocalDate,
        created: LocalDate,
        completed: Set<LocalDate>? = null
    ): TrackingSummary {
        val done = metDays(activity, progress, today, created, completed)
        val streak = if (Schedule.isWeekly(activity)) {
            val weeks = WeeklyStreak.compute(done, activity.scheduleCount.coerceIn(1, 7), today)
            StreakState(weeks, 0, emptyList(), 0)
        } else {
            StreakEngine.compute(done, today, Schedule.neutralDays(activity, done, skipped))
        }
        return TrackingSummary(
            streak = streak,
            streakUnit = if (Schedule.isWeekly(activity)) "weeks" else "days",
            strength = HabitStrength.score(activity, progress, done, skipped, today, created),
            caption = Schedule.caption(activity, done, skipped, today)
        )
    }

    /**
     * Days the goal was met. For limits a day with nothing logged is a good day, so every day
     * from [created] to [today] counts unless it went over.
     */
    fun metDays(
        activity: Activity,
        progress: Map<LocalDate, Float>,
        today: LocalDate,
        created: LocalDate,
        completed: Set<LocalDate>? = null
    ): Set<LocalDate> {
        if (!Goals.isLimit(activity)) {
            completed?.let { days -> return days.filterTo(HashSet()) { !it.isAfter(today) } }
            return progress.filter { (day, value) -> !day.isAfter(today) && Goals.isMet(activity, value) }.keys
        }
        val start = maxOf(created, today.minusDays(HISTORY_DAYS))
        return generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .filter { Goals.isMet(activity, progress[it] ?: 0f) }
            .toSet()
    }

    /** Days since the last slip of a quit/limit activity (or since it was created). */
    fun daysSinceSlip(activity: Activity, progress: Map<LocalDate, Float>, today: LocalDate, created: LocalDate): Long {
        val lastSlip = progress.filter { (day, value) -> !day.isAfter(today) && !Goals.isMet(activity, value) }.keys.maxOrNull()
        val from = lastSlip?.plusDays(1) ?: created
        return (ChronoUnit.DAYS.between(from, today) + 1).coerceAtLeast(0)
    }

    /**
     * Share of due days met in [start]..[end] (0..1), or null when nothing was due. Limits
     * count days without a row as met; days before [created] and skipped days don't count.
     */
    fun completion(
        activity: Activity,
        progress: Map<LocalDate, Float>,
        skipped: Set<LocalDate>,
        start: LocalDate,
        end: LocalDate,
        created: LocalDate,
        completed: Set<LocalDate>? = null
    ): Pair<Double, Double> {
        val from = maxOf(start, created)
        if (end.isBefore(from)) return 0.0 to 0.0
        val skippedInRange = skipped.count { !it.isBefore(from) && !it.isAfter(end) && Schedule.isScheduledDay(activity, it) }
        val due = (Schedule.dueDays(activity, from, end) - skippedInRange * Schedule.frequency(activity)).coerceAtLeast(0.0)
        val met = metDays(activity, progress, end, created, completed)
            .count { !it.isBefore(from) && it !in skipped && Schedule.isScheduledDay(activity, it) }
            .toDouble()
        return minOf(met, due) to due
    }

    /** Limits are checked back this far; older history only matters for "at least" goals. */
    const val HISTORY_DAYS = 400L
}

/** Consecutive Monday–Sunday weeks with at least [perWeek] done days. */
object WeeklyStreak {
    fun compute(done: Set<LocalDate>, perWeek: Int, today: LocalDate): Int {
        val counts = done.filter { !it.isAfter(today) }.groupingBy { Schedule.weekStart(it) }.eachCount()
        var week = Schedule.weekStart(today)
        var streak = 0
        // The current week counts once it's met; until then it's still open.
        if ((counts[week] ?: 0) >= perWeek) streak++
        week = week.minusWeeks(1)
        while ((counts[week] ?: 0) >= perWeek) {
            streak++
            week = week.minusWeeks(1)
        }
        return streak
    }
}

/**
 * Habit strength, 0..1, in the spirit of Loop Habit Tracker's score: every day moves it
 * towards 1 when met (part of the way for partial progress) and towards 0 when missed, so a
 * slip after two months of work dents it instead of zeroing it. Less frequent habits move
 * less per day. Days off covered by the schedule (the rest of a week that met its target, the
 * days between "every N days") count as met; rest days of a weekday schedule, skipped days and
 * today (until it's met) leave the score alone.
 */
object HabitStrength {

    /** Days for the score to move halfway, for a daily habit. */
    const val HALF_LIFE_DAYS = 13.0

    fun multiplier(frequency: Double): Double = 0.5.pow(sqrt(frequency.coerceIn(0.01, 1.0)) / HALF_LIFE_DAYS)

    fun score(
        activity: Activity,
        progress: Map<LocalDate, Float>,
        done: Set<LocalDate>,
        skipped: Set<LocalDate>,
        today: LocalDate,
        created: LocalDate
    ): Double {
        val m = multiplier(Schedule.frequency(activity))
        val interval = activity.scheduleCount.coerceAtLeast(1).toLong()
        val weekTarget = activity.scheduleCount.coerceIn(1, 7)
        val weekCounts = done.groupingBy { Schedule.weekStart(it) }.eachCount()
        val thisWeek = Schedule.weekStart(today)

        var score = 0.0
        var day = maxOf(created, today.minusDays(Tracking.HISTORY_DAYS))
        while (!day.isAfter(today)) {
            val credit = when {
                day in done -> 1.0
                day in skipped || !Schedule.isScheduledDay(activity, day) -> null
                activity.scheduleType == Schedule.WEEKLY -> when {
                    Schedule.weekStart(day) == thisWeek -> null // the week is still open
                    (weekCounts[Schedule.weekStart(day)] ?: 0) >= weekTarget -> 1.0
                    else -> 0.0
                }
                activity.scheduleType == Schedule.INTERVAL && (1 until interval).any { day.minusDays(it) in done } -> 1.0
                day == today -> null // still open
                else -> Goals.credit(activity, progress[day] ?: 0f)
            }
            if (credit != null) score = score * m + credit * (1 - m)
            day = day.plusDays(1)
        }
        return score.coerceIn(0.0, 1.0)
    }

    fun percent(score: Double): Int = (score * 100).roundToInt()
}

/**
 * The tracking day starts [startMinutes] after midnight, so night owls can count work done
 * at 1 AM towards the day before (Whoop counts days from sleep to sleep the same way).
 */
object DayBoundary {

    const val MAX_START_MINUTES = 6 * 60

    fun logicalDate(millis: Long, startMinutes: Int, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).minusMinutes(startMinutes.toLong()).toLocalDate()

    /** Epoch millis when [date]'s tracking day starts. */
    fun startOf(date: LocalDate, startMinutes: Int, zone: ZoneId): Long =
        date.atStartOfDay(zone).plusMinutes(startMinutes.toLong()).toInstant().toEpochMilli()

    /** Epoch millis of the next day start after [now]. */
    fun nextStart(now: Long, startMinutes: Int, zone: ZoneId): Long =
        startOf(logicalDate(now, startMinutes, zone).plusDays(1), startMinutes, zone)

    /**
     * A day start learnt from sleep: an hour after the typical (median) time you fall asleep
     * when that's after midnight, rounded to 15 minutes; 0 when you usually sleep before midnight.
     *
     * @param onsets sleep start times as minutes after local midnight; evening times are negative
     */
    fun fromSleep(onsets: List<Int>): Int {
        if (onsets.isEmpty()) return 0
        val sorted = onsets.sorted()
        val median = sorted[sorted.size / 2]
        if (median <= 0) return 0
        return ((median + 60) / 15 * 15).coerceIn(0, MAX_START_MINUTES)
    }

    /** "midnight", "4:00 AM". */
    fun label(startMinutes: Int): String {
        if (startMinutes <= 0) return "midnight"
        val hours = startMinutes / 60
        val minutes = startMinutes % 60
        return "%d:%02d AM".format(if (hours == 0) 12 else hours, minutes)
    }
}

/** One square of the year heatmap. */
data class HeatDay(val date: LocalDate, val state: HeatState, /** 0..1 share of the goal reached. */ val level: Float)

enum class HeatState { NONE, PARTIAL, DONE, OVER, SKIPPED, REST, BEFORE, FUTURE }

/** The per-activity year grid: [WEEKS] Monday-first columns ending with the current week. */
object Heatmap {

    const val WEEKS = 53

    fun build(
        activity: Activity,
        progress: Map<LocalDate, Float>,
        skipped: Set<LocalDate>,
        completed: Set<LocalDate>?,
        today: LocalDate,
        created: LocalDate
    ): List<List<HeatDay>> {
        val done = Tracking.metDays(activity, progress, today, created, completed)
        val first = Schedule.weekStart(today).minusWeeks((WEEKS - 1).toLong())
        return (0 until WEEKS).map { week ->
            (0 until 7).map { weekday ->
                val day = first.plusWeeks(week.toLong()).plusDays(weekday.toLong())
                val value = progress[day] ?: 0f
                val state = when {
                    day.isAfter(today) -> HeatState.FUTURE
                    day.isBefore(created) && value <= 0f -> HeatState.BEFORE
                    day in skipped -> HeatState.SKIPPED
                    Goals.isLimit(activity) -> if (day in done) HeatState.DONE else HeatState.OVER
                    day in done -> HeatState.DONE
                    value > 0f -> HeatState.PARTIAL
                    !Schedule.isScheduledDay(activity, day) -> HeatState.REST
                    else -> HeatState.NONE
                }
                val level = if (activity.dailyGoal > 0f) (value / activity.dailyGoal).coerceIn(0f, 1f) else 0f
                HeatDay(day, state, level)
            }
        }
    }
}
