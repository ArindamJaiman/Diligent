package dev.diligent.app.data.repository

import dev.diligent.app.data.local.dao.ActivityDao
import dev.diligent.app.data.local.dao.ActivityReminderDao
import dev.diligent.app.data.local.dao.DailyProgressDao
import dev.diligent.app.data.local.dao.SettingsDao
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.ActivityReminder
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.data.local.entity.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for all Diligent data.
 * Abstracts DAOs behind a clean API for ViewModels and widgets.
 */
@Singleton
class DiligentRepository @Inject constructor(
    private val activityDao: ActivityDao,
    private val dailyProgressDao: DailyProgressDao,
    private val settingsDao: SettingsDao,
    private val activityReminderDao: ActivityReminderDao
) {
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    // ─── Activities ─────────────────────────────────────────────

    fun getAllActiveActivities(): Flow<List<Activity>> = activityDao.getAllActive()
    fun getAllActivities(): Flow<List<Activity>> = activityDao.getAll()
    suspend fun getAllActiveSnapshot(): List<Activity> = activityDao.getAllActiveSnapshot()
    suspend fun getActivityById(id: Long): Activity? = activityDao.getById(id)
    fun getActivityByIdFlow(id: Long): Flow<Activity?> = activityDao.getByIdFlow(id)
    fun searchActivities(query: String): Flow<List<Activity>> = activityDao.search(query)

    suspend fun createActivity(activity: Activity): Long {
        val sortOrder = activityDao.getNextSortOrder()
        return activityDao.insert(activity.copy(sortOrder = sortOrder))
    }

    suspend fun updateActivity(activity: Activity) = activityDao.update(activity)
    suspend fun deleteActivity(activity: Activity) = activityDao.delete(activity)
    suspend fun archiveActivity(id: Long) = activityDao.archive(id)
    suspend fun insertAllActivities(activities: List<Activity>) = activityDao.insertAll(activities)

    // ─── Daily Progress ─────────────────────────────────────────

    fun getTodayProgress(): Flow<List<DailyProgress>> =
        dailyProgressDao.getProgressForDate(today())

    suspend fun getTodayProgressSnapshot(): List<DailyProgress> =
        dailyProgressDao.getProgressForDateSnapshot(today())

    fun getProgressForDate(date: String): Flow<List<DailyProgress>> =
        dailyProgressDao.getProgressForDate(date)

    fun getProgressRange(activityId: Long, startDate: String, endDate: String): Flow<List<DailyProgress>> =
        dailyProgressDao.getProgressRange(activityId, startDate, endDate)

    fun getAllProgressRange(startDate: String, endDate: String): Flow<List<DailyProgress>> =
        dailyProgressDao.getAllProgressRange(startDate, endDate)

    suspend fun getAllProgressRangeSnapshot(startDate: String, endDate: String): List<DailyProgress> =
        dailyProgressDao.getAllProgressRangeSnapshot(startDate, endDate)

    /**
     * Increment progress for an activity today.
     * Creates a new DailyProgress row if none exists.
     * Automatically marks complete when progress >= dailyGoal.
     */
    suspend fun incrementProgress(activityId: Long, amount: Float = 1f) {
        val date = today()
        val existing = dailyProgressDao.getProgressForActivityOnDate(activityId, date)
        val activity = activityDao.getById(activityId) ?: return

        if (existing != null) {
            val newProgress = existing.progress + amount
            dailyProgressDao.update(
                existing.copy(
                    progress = newProgress,
                    isCompleted = newProgress >= activity.dailyGoal,
                    lastUpdated = System.currentTimeMillis()
                )
            )
        } else {
            dailyProgressDao.insert(
                DailyProgress(
                    activityId = activityId,
                    date = date,
                    progress = amount,
                    isCompleted = amount >= activity.dailyGoal,
                    lastUpdated = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Decrement progress for an activity today.
     * Clamps to 0 — never goes negative.
     */
    suspend fun decrementProgress(activityId: Long, amount: Float = 1f) {
        val date = today()
        val existing = dailyProgressDao.getProgressForActivityOnDate(activityId, date) ?: return
        val activity = activityDao.getById(activityId) ?: return
        val newProgress = (existing.progress - amount).coerceAtLeast(0f)

        dailyProgressDao.update(
            existing.copy(
                progress = newProgress,
                isCompleted = newProgress >= activity.dailyGoal,
                lastUpdated = System.currentTimeMillis()
            )
        )
    }

    /**
     * Toggle completion for an activity today.
     */
    suspend fun toggleComplete(activityId: Long) {
        val date = today()
        val existing = dailyProgressDao.getProgressForActivityOnDate(activityId, date)
        val activity = activityDao.getById(activityId) ?: return

        if (existing != null) {
            dailyProgressDao.update(
                existing.copy(
                    isCompleted = !existing.isCompleted,
                    progress = if (!existing.isCompleted) activity.dailyGoal else existing.progress,
                    lastUpdated = System.currentTimeMillis()
                )
            )
        } else {
            dailyProgressDao.insert(
                DailyProgress(
                    activityId = activityId,
                    date = date,
                    progress = activity.dailyGoal,
                    isCompleted = true,
                    lastUpdated = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun getAllDailyProgressSnapshot(): List<DailyProgress> =
        dailyProgressDao.getAllSnapshot()

    suspend fun insertAllProgress(progressList: List<DailyProgress>) =
        dailyProgressDao.insertAll(progressList)

    // ─── Streaks ────────────────────────────────────────────────

    /**
     * Calculates the current streak for an activity.
     * A streak is the number of consecutive days (ending today or yesterday)
     * where the activity was marked complete.
     */
    suspend fun calculateStreak(activityId: Long): Int {
        val completedDates = dailyProgressDao.getCompletedDates(activityId)
            .map { LocalDate.parse(it, dateFormatter) }
            .sorted()
            .reversed()

        if (completedDates.isEmpty()) return 0

        val today = LocalDate.now()
        var streak = 0
        var expectedDate = today

        // Allow streak to start from today or yesterday
        if (completedDates.first() == today) {
            expectedDate = today
        } else if (completedDates.first() == today.minusDays(1)) {
            expectedDate = today.minusDays(1)
        } else {
            return 0
        }

        for (date in completedDates) {
            if (date == expectedDate) {
                streak++
                expectedDate = expectedDate.minusDays(1)
            } else if (date.isBefore(expectedDate)) {
                break
            }
        }

        return streak
    }

    // ─── Statistics ─────────────────────────────────────────────

    /**
     * Calculate completion rate for a given date range.
     * Returns a value between 0.0 and 1.0.
     */
    suspend fun getCompletionRate(startDate: String, endDate: String): Float {
        val progress = dailyProgressDao.getAllProgressRangeSnapshot(startDate, endDate)
        val activities = activityDao.getAllActiveSnapshot()
        if (activities.isEmpty()) return 0f

        val start = LocalDate.parse(startDate, dateFormatter)
        val end = LocalDate.parse(endDate, dateFormatter)
        val totalDays = ChronoUnit.DAYS.between(start, end) + 1
        val totalPossible = activities.size * totalDays

        if (totalPossible == 0L) return 0f

        val completed = progress.count { it.isCompleted }
        return (completed.toFloat() / totalPossible.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Calculate total hours tracked across all activities in a date range.
     */
    suspend fun getTotalHoursTracked(startDate: String, endDate: String): Float {
        val progress = dailyProgressDao.getAllProgressRangeSnapshot(startDate, endDate)
        val activities = activityDao.getAllActiveSnapshot().associateBy { it.id }
        return progress
            .filter { activities[it.activityId]?.unit == "hrs" }
            .sumOf { it.progress.toDouble() }
            .toFloat()
    }

    // ─── Settings ───────────────────────────────────────────────

    fun getSettings(): Flow<Settings?> = settingsDao.getSettings()
    suspend fun getSettingsSnapshot(): Settings = settingsDao.getSettingsSnapshot() ?: Settings()
    suspend fun updateSettings(settings: Settings) = settingsDao.insert(settings)

    // ─── Reminders ──────────────────────────────────────────────

    fun getRemindersForActivity(activityId: Long): Flow<List<ActivityReminder>> =
        activityReminderDao.getRemindersForActivity(activityId)

    suspend fun getAllEnabledReminders(): List<ActivityReminder> =
        activityReminderDao.getAllEnabledSnapshot()

    suspend fun getAllRemindersSnapshot(): List<ActivityReminder> =
        activityReminderDao.getAllSnapshot()

    suspend fun createReminder(reminder: ActivityReminder): Long =
        activityReminderDao.insert(reminder)

    suspend fun updateReminder(reminder: ActivityReminder) =
        activityReminderDao.update(reminder)

    suspend fun deleteReminder(reminder: ActivityReminder) =
        activityReminderDao.delete(reminder)

    suspend fun insertAllReminders(reminders: List<ActivityReminder>) {
        reminders.forEach { activityReminderDao.insert(it) }
    }

    // ─── Helpers ────────────────────────────────────────────────

    fun today(): String = LocalDate.now().format(dateFormatter)

    fun daysAgo(days: Long): String =
        LocalDate.now().minusDays(days).format(dateFormatter)

    fun startOfWeek(): String =
        LocalDate.now().with(java.time.DayOfWeek.MONDAY).format(dateFormatter)

    fun startOfMonth(): String =
        LocalDate.now().withDayOfMonth(1).format(dateFormatter)
}
