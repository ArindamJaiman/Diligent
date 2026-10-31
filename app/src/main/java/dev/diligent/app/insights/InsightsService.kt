package dev.diligent.app.insights

import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.data.local.entity.FocusRecord
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.focus.domain.FocusStats
import dev.diligent.app.planning.Energy
import dev.diligent.app.tracking.DayClock
import dev.diligent.app.tracking.Goals
import dev.diligent.app.tracking.Schedule
import dev.diligent.app.tracking.Tracking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A period's numbers for the wrap-up and the PDF report. */
data class PeriodSummary(
    val start: LocalDate,
    val end: LocalDate,
    val completion: Double,
    val hours: Double,
    val perActivity: List<Comparison>,
    val sessions: Int,
    val focusMinutes: Int,
    val bestDay: LocalDate?,
    val bestHour: Int?,
    val longestStreak: Int,
    val days: List<DayFacts>,
    val notes: List<Pair<LocalDate, String>>
)

/**
 * Builds Insights from what's already stored: daily progress, day logs (sleep, steps, mood,
 * scrolling) and focus sessions. Nothing leaves the phone.
 */
@Singleton
class InsightsService @Inject constructor(private val repository: DiligentRepository) {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** One [DayFacts] per day from [start] to [end]. */
    suspend fun facts(start: LocalDate, end: LocalDate): List<DayFacts> {
        val activities = repository.getAllActivitiesSnapshot().filter { !it.isArchived }
        val rows = repository.getAllProgressRangeSnapshot(start.minusDays(14).toString(), end.toString())
        val logs = repository.getDayLogs(start.toString(), end.toString()).associateBy { it.date }
        val sessions = repository.getFocusRecords(DayClock.startOf(start), DayClock.startOf(end.plusDays(1)))
            .groupingBy { DayClock.dateOf(it.startedAt) }.eachCount()
        val byActivity = rows.groupBy { it.activityId }
        val history = activities.associate { it.id to repository.split(byActivity[it.id].orEmpty()) }

        return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.map { day ->
            var met = 0.0
            var due = 0.0
            var hours = 0.0
            val done = HashSet<Long>()
            activities.forEach { activity ->
                val h = history.getValue(activity.id)
                val (m, d) = Tracking.completion(activity, h.progress, h.skipped, day, day, repository.createdDate(activity), h.completed)
                met += m
                due += d
                if (day in h.completed || (Goals.isLimit(activity) && m > 0)) done += activity.id
                if (!Goals.isLimit(activity)) {
                    val value = h.progress[day] ?: 0f
                    hours += when (activity.unit) {
                        "hrs" -> value.toDouble()
                        "mins" -> value / 60.0
                        else -> 0.0
                    }
                }
            }
            val log = logs[day.toString()]
            DayFacts(
                date = day,
                completion = if (due > 0) (met / due).coerceIn(0.0, 1.0) else null,
                hours = hours,
                sleepHours = log?.sleepMinutes?.let { it / 60.0 },
                steps = log?.steps,
                mood = log?.mood?.takeIf { it > 0 },
                energy = log?.energy?.takeIf { it > 0 },
                distractingMinutes = log?.distractingMinutes,
                mindfulMinutes = log?.mindfulMinutes,
                focusSessions = sessions[day] ?: 0,
                done = done
            )
        }.toList()
    }

    suspend fun readiness(): Readiness {
        val today = DayClock.today()
        val days = facts(today.minusDays(34), today)
        val nights = days.takeLast(14).mapNotNull { it.sleepHours?.let { h -> (h * 60).toInt() } }
        return ReadinessMath.compute(days, nights.takeIf { it.isNotEmpty() }?.let { Energy.sleepDebt(it) })
    }

    suspend fun correlations(): Pair<List<Correlation>, List<String>> {
        val today = DayClock.today()
        val days = facts(today.minusDays(90), today.minusDays(1))
        val names = repository.getAllActiveSnapshot().associate { it.id to it.name }
        return Correlations.all(days) to Correlations.companions(days, names)
    }

    /** Totals per activity in [start]..[end], in each activity's unit. */
    suspend fun totals(start: LocalDate, end: LocalDate): Map<Long, Double> =
        repository.getAllProgressRangeSnapshot(start.toString(), end.toString())
            .groupBy { it.activityId }
            .mapValues { (_, rows) -> rows.sumOf { it.progress.toDouble() } }

    suspend fun compare(start: LocalDate, end: LocalDate): List<Comparison> {
        val length = ChronoUnit.DAYS.between(start, end) + 1
        val names = repository.getAllActiveSnapshot().filterNot { Goals.isLimit(it) }.associate { it.id to (it.name to it.unit) }
        return PeriodCompare.compare(totals(start, end), totals(start.minusDays(length), start.minusDays(1)), names)
    }

    suspend fun period(start: LocalDate, end: LocalDate): PeriodSummary {
        val days = facts(start, end)
        val records = repository.getFocusRecords(DayClock.startOf(start), DayClock.startOf(end.plusDays(1)))
        val rated = days.filter { it.completion != null }
        val logs = repository.getDayLogs(start.toString(), end.toString())
        return PeriodSummary(
            start = start,
            end = end,
            completion = rated.mapNotNull { it.completion }.average().takeIf { !it.isNaN() } ?: 0.0,
            hours = days.sumOf { it.hours },
            perActivity = compare(start, end),
            sessions = records.size,
            focusMinutes = records.sumOf { it.focusMinutes },
            bestDay = rated.maxByOrNull { (it.completion ?: 0.0) * 100 + it.hours }?.date,
            bestHour = FocusStats.bestWindow(FocusStats.minutesByHour(records, zone), 1),
            longestStreak = longestRun(days.filter { (it.completion ?: 0.0) >= 0.999 }.map { it.date }),
            days = days,
            notes = logs.filter { it.note.isNotBlank() }.mapNotNull { log -> runCatching { LocalDate.parse(log.date) }.getOrNull()?.let { it to log.note } }
        )
    }

    suspend fun lifetime(): Lifetime {
        val activities = repository.getAllActivitiesSnapshot()
        val rows = repository.getAllDailyProgressSnapshot()
        val records = repository.getAllFocusRecords().sortedBy { it.startedAt }
        val byActivity = rows.groupBy { it.activityId }
        val bestStreak = activities.maxOfOrNull { activity ->
            longestRun(byActivity[activity.id].orEmpty().filter { it.isCompleted && !it.isSkipped }.mapNotNull { parse(it) })
        } ?: 0
        val hours = activities.filter { it.unit == "hrs" || it.unit == "mins" }.filterNot { Goals.isLimit(it) }.associate { activity ->
            val total = byActivity[activity.id].orEmpty().sumOf { it.progress.toDouble() }
            activity.name to if (activity.unit == "mins") total / 60 else total
        }.filterValues { it > 0 }
        val today = DayClock.today()
        val weeks = perfectWeeks(activities, byActivity, today)
        return Lifetime(
            bestStreak = bestStreak,
            hoursBySkill = hours,
            sessions = records.size,
            intactInARow = records.asReversed().takeWhile { !it.broken }.size,
            perfectWeeks = weeks,
            earlySessions = records.count { hour(it) < 8 },
            lateSessions = records.count { hour(it) >= 22 },
            completions = rows.count { it.isCompleted },
            daysTracked = rows.map { it.date }.distinct().size
        )
    }

    /** Finished Monday–Sunday weeks in the last year where every due goal was met. */
    private fun perfectWeeks(activities: List<Activity>, byActivity: Map<Long, List<DailyProgress>>, today: LocalDate): Int {
        var count = 0
        var week = Schedule.weekStart(today).minusWeeks(1)
        val live = activities.filterNot { it.isArchived }
        if (live.isEmpty()) return 0
        repeat(52) {
            var met = 0.0
            var due = 0.0
            live.forEach { activity ->
                val h = repository.split(byActivity[activity.id].orEmpty())
                val (m, d) = Tracking.completion(activity, h.progress, h.skipped, week, week.plusDays(6), repository.createdDate(activity), h.completed)
                met += m
                due += d
            }
            if (due > 0 && met >= due - 1e-6) count++
            week = week.minusWeeks(1)
        }
        return count
    }

    private fun hour(record: FocusRecord) = Instant.ofEpochMilli(record.startedAt).atZone(zone).hour

    private fun parse(row: DailyProgress): LocalDate? = runCatching { LocalDate.parse(row.date) }.getOrNull()

    companion object {
        /** Longest run of consecutive days. */
        fun longestRun(days: List<LocalDate>): Int {
            val sorted = days.toSortedSet()
            var best = 0
            var run = 0
            var previous: LocalDate? = null
            for (day in sorted) {
                run = if (previous != null && previous.plusDays(1) == day) run + 1 else 1
                best = maxOf(best, run)
                previous = day
            }
            return best
        }
    }
}
