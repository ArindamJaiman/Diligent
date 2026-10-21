package dev.diligent.app.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.data.local.dao.ActivityDao
import dev.diligent.app.data.local.dao.ActivityReminderDao
import dev.diligent.app.data.local.dao.DailyProgressDao
import dev.diligent.app.data.local.dao.GithubContributionDao
import dev.diligent.app.data.local.dao.GithubUserDao
import dev.diligent.app.data.local.dao.SettingsDao
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.ActivityReminder
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.data.local.entity.GithubContribution
import dev.diligent.app.data.local.entity.GithubUser
import dev.diligent.app.data.local.entity.GithubUsers
import dev.diligent.app.data.local.entity.Settings
import dev.diligent.app.goals.StreakEngine
import dev.diligent.app.goals.StreakState
import dev.diligent.app.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
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
    @ApplicationContext private val context: Context,
    private val activityDao: ActivityDao,
    private val dailyProgressDao: DailyProgressDao,
    private val settingsDao: SettingsDao,
    private val activityReminderDao: ActivityReminderDao,
    private val githubContributionDao: GithubContributionDao,
    private val githubUserDao: GithubUserDao
) {
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    // ─── Activities ─────────────────────────────────────────────

    fun getAllActiveActivities(): Flow<List<Activity>> = activityDao.getAllActive()
    fun getAllActivities(): Flow<List<Activity>> = activityDao.getAll()
    suspend fun getAllActiveSnapshot(): List<Activity> = activityDao.getAllActiveSnapshot()
    suspend fun getAllActivitiesSnapshot(): List<Activity> = activityDao.getAllSnapshot()
    suspend fun getActivityById(id: Long): Activity? = activityDao.getById(id)
    fun getActivityByIdFlow(id: Long): Flow<Activity?> = activityDao.getByIdFlow(id)
    fun searchActivities(query: String): Flow<List<Activity>> = activityDao.search(query)

    suspend fun createActivity(activity: Activity): Long {
        val sortOrder = activityDao.getNextSortOrder()
        val id = activityDao.insert(activity.copy(sortOrder = sortOrder))
        trackerChanged()
        return id
    }

    /**
     * Updates an activity. When the daily goal changes, today's completion flag is
     * recalculated so the dashboard and widget don't show a stale checkmark.
     */
    suspend fun updateActivity(activity: Activity) {
        val previous = activityDao.getById(activity.id)
        activityDao.update(activity)
        if (previous != null && previous.dailyGoal != activity.dailyGoal) {
            dailyProgressDao.getProgressForActivityOnDate(activity.id, today())?.let { progress ->
                dailyProgressDao.update(
                    progress.copy(isCompleted = progress.progress >= activity.dailyGoal)
                )
            }
        }
        trackerChanged()
    }

    suspend fun deleteActivity(activity: Activity) {
        activityDao.delete(activity)
        trackerChanged()
    }

    suspend fun archiveActivity(id: Long) {
        activityDao.archive(id)
        trackerChanged()
    }

    suspend fun insertAllActivities(activities: List<Activity>) {
        activityDao.insertAll(activities)
        trackerChanged()
    }

    // ─── Daily Progress ─────────────────────────────────────────

    /** Today's progress; switches to the new day at midnight while observed. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getTodayProgress(): Flow<List<DailyProgress>> =
        todayFlow().flatMapLatest { dailyProgressDao.getProgressForDate(it) }

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
    suspend fun incrementProgress(activityId: Long, amount: Float = 1f) = addProgress(activityId, amount)

    /**
     * Adds [amount] (in the activity's own unit) to its progress on [date], creating the row
     * if needed and marking it complete once the goal is met. The focus timer calls this once
     * per local day, so a session that crosses midnight counts towards both days.
     */
    suspend fun addProgress(activityId: Long, amount: Float, date: String = today()) {
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
        trackerChanged()
    }

    /**
     * Raises today's progress for [activity] to at least [value] (used by LeetCode auto-log).
     * Never lowers it, and never un-completes a day.
     */
    suspend fun raiseProgressTo(activity: Activity, value: Float) {
        val date = today()
        val existing = dailyProgressDao.getProgressForActivityOnDate(activity.id, date)
        if (existing != null && existing.progress >= value) return

        if (existing != null) {
            dailyProgressDao.update(
                existing.copy(
                    progress = value,
                    isCompleted = existing.isCompleted || value >= activity.dailyGoal,
                    lastUpdated = System.currentTimeMillis()
                )
            )
        } else {
            dailyProgressDao.insert(
                DailyProgress(
                    activityId = activity.id,
                    date = date,
                    progress = value,
                    isCompleted = value >= activity.dailyGoal,
                    lastUpdated = System.currentTimeMillis()
                )
            )
        }
        trackerChanged()
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
        trackerChanged()
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
        trackerChanged()
    }

    suspend fun getAllDailyProgressSnapshot(): List<DailyProgress> =
        dailyProgressDao.getAllSnapshot()

    suspend fun insertAllProgress(progressList: List<DailyProgress>) {
        dailyProgressDao.insertAll(progressList)
        trackerChanged()
    }

    // ─── Streaks ────────────────────────────────────────────────

    /**
     * Calculates the current streak for an activity: consecutive completed days ending
     * today or yesterday, where banked streak freezes cover single missed days
     * (see [StreakEngine]).
     */
    suspend fun calculateStreak(activityId: Long): Int = streakState(activityId).current

    /** Streak plus freezes banked and days saved by a freeze. */
    suspend fun streakState(activityId: Long): StreakState {
        val completedDates = dailyProgressDao.getCompletedDates(activityId)
            .mapNotNull { runCatching { LocalDate.parse(it, dateFormatter) }.getOrNull() }
        return StreakEngine.compute(completedDates, LocalDate.now())
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

        // Only count completions of activities that are still active, so archived
        // history can't push the rate past what the denominator allows.
        val activeIds = activities.mapTo(HashSet()) { it.id }
        val completed = progress.count { it.isCompleted && it.activityId in activeIds }
        return (completed.toFloat() / totalPossible.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Calculate total hours tracked across all activities in a date range.
     * Activities tracked in minutes are converted to hours.
     */
    suspend fun getTotalHoursTracked(startDate: String, endDate: String): Float {
        val progress = dailyProgressDao.getAllProgressRangeSnapshot(startDate, endDate)
        val activities = activityDao.getAllActiveSnapshot().associateBy { it.id }
        return progress
            .sumOf { p ->
                when (activities[p.activityId]?.unit) {
                    "hrs" -> p.progress.toDouble()
                    "mins" -> p.progress / 60.0
                    else -> 0.0
                }
            }
            .toFloat()
    }

    // ─── Settings ───────────────────────────────────────────────

    fun getSettings(): Flow<Settings?> = settingsDao.getSettings()
    suspend fun getSettingsSnapshot(): Settings = settingsDao.getSettingsSnapshot() ?: Settings()
    suspend fun hasSettings(): Boolean = settingsDao.getSettingsSnapshot() != null
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

    // ─── GitHub Contributions ───────────────────────────────────

    fun getGithubContribution(username: String): Flow<GithubContribution?> =
        githubContributionDao.getContribution(username)

    suspend fun getGithubContributionSnapshot(username: String): GithubContribution? =
        githubContributionDao.getContributionSnapshot(username)

    suspend fun saveGithubContribution(contribution: GithubContribution) =
        githubContributionDao.insert(contribution)

    suspend fun syncGithubContributions(username: String): Boolean {
        if (username.isBlank()) return false
        val result = GithubContributionFetcher.fetchContributions(username) ?: return false

        val cached = GithubContribution(
            username = username,
            contributionsJson = GithubContributionFetcher.encode(result.contributions),
            lastUpdated = System.currentTimeMillis(),
            totalAnnual = result.totalAnnual,
            currentStreak = result.currentStreak
        )
        saveGithubContribution(cached)
        return true
    }

    /**
     * Syncs every developer's graph, [GITHUB_SYNC_PARALLELISM] requests at a time so a long
     * list doesn't fire dozens of requests at GitHub at once. Returns (synced, total).
     */
    suspend fun syncAllGithubContributions(): Pair<Int, Int> = coroutineScope {
        val users = getGithubUsersSnapshot().map { it.username }
        val gate = Semaphore(GITHUB_SYNC_PARALLELISM)
        val synced = users.map { username ->
            async(Dispatchers.IO) { gate.withPermit { syncGithubContributions(username) } }
        }.awaitAll().count { it }
        synced to users.size
    }

    // ─── GitHub Developers ──────────────────────────────────────

    private val githubUsersMutex = Mutex()

    fun getGithubUsers(): Flow<List<GithubUser>> = githubUserDao.observeAll()

    suspend fun getGithubUsersSnapshot(): List<GithubUser> = githubUserDao.getAll()

    /**
     * Applies one edit from [GithubUsers] to the stored list. Graphs cached for a developer
     * who left the list are deleted; a change of letter case keeps its graph.
     */
    suspend fun editGithubUsers(edit: (List<GithubUser>) -> GithubUsers.Edit): GithubUsers.Edit =
        githubUsersMutex.withLock {
            val before = githubUserDao.getAll()
            val result = edit(before)
            if (result is GithubUsers.Edit.Done) {
                githubUserDao.replaceAll(result.users)
                for (old in before) {
                    if (result.users.any { it.username == old.username }) continue
                    val renamed = result.users.firstOrNull { it.username.equals(old.username, ignoreCase = true) }
                    val cached = githubContributionDao.getContributionSnapshot(old.username)
                    if (renamed != null && cached != null) githubContributionDao.insert(cached.copy(username = renamed.username))
                    githubContributionDao.deleteByUsername(old.username)
                }
            }
            result
        }

    /** Replaces the whole list (restore, first-launch defaults). */
    suspend fun replaceGithubUsers(users: List<GithubUser>) {
        editGithubUsers { GithubUsers.Edit.Done(GithubUsers.ordered(users)) }
    }

    // ─── Helpers ────────────────────────────────────────────────

    fun today(): String = LocalDate.now().format(dateFormatter)

    /**
     * Emits today's date, then again just after each local midnight.
     * Only one suspended delay per day — no polling.
     */
    fun todayFlow(): Flow<String> = flow {
        while (true) {
            val now = LocalDateTime.now()
            emit(now.toLocalDate().format(dateFormatter))
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
            delay(Duration.between(now, nextMidnight).toMillis() + 1_000)
        }
    }.distinctUntilChanged()

    fun daysAgo(days: Long): String =
        LocalDate.now().minusDays(days).format(dateFormatter)

    fun startOfWeek(): String =
        LocalDate.now().with(java.time.DayOfWeek.MONDAY).format(dateFormatter)

    fun startOfMonth(): String =
        LocalDate.now().withDayOfMonth(1).format(dateFormatter)

    /** Keeps the home-screen tracker widget in sync with in-app changes. */
    private suspend fun trackerChanged() {
        WidgetUpdater.refreshTracker(context)
    }

    companion object {
        /** Contribution pages fetched at once during a sync. */
        const val GITHUB_SYNC_PARALLELISM = 4
    }
}
