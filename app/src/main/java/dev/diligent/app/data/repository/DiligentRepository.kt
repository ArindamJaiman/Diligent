package dev.diligent.app.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.data.local.dao.ActivityDao
import dev.diligent.app.data.local.dao.ActivityReminderDao
import dev.diligent.app.data.local.dao.DailyProgressDao
import dev.diligent.app.data.local.dao.DayLogDao
import dev.diligent.app.data.local.dao.FocusRecordDao
import dev.diligent.app.data.local.dao.GithubContributionDao
import dev.diligent.app.data.local.dao.GithubUserDao
import dev.diligent.app.data.local.dao.SettingsDao
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.ActivityReminder
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.data.local.entity.DayLog
import dev.diligent.app.data.local.entity.FocusRecord
import dev.diligent.app.data.local.entity.GithubContribution
import dev.diligent.app.data.local.entity.GithubUser
import dev.diligent.app.data.local.entity.GithubUsers
import dev.diligent.app.data.local.entity.Settings
import dev.diligent.app.goals.StreakEngine
import dev.diligent.app.goals.StreakState
import dev.diligent.app.tracking.DayClock
import dev.diligent.app.tracking.Goals
import dev.diligent.app.tracking.Schedule
import dev.diligent.app.tracking.Tracking
import dev.diligent.app.tracking.TrackingSummary
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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
    private val githubUserDao: GithubUserDao,
    private val focusRecordDao: FocusRecordDao,
    private val dayLogDao: DayLogDao
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
        if (previous != null && (previous.dailyGoal != activity.dailyGoal || previous.goalType != activity.goalType)) {
            dailyProgressDao.getProgressForActivityOnDate(activity.id, today())?.let { progress ->
                dailyProgressDao.update(
                    progress.copy(isCompleted = !progress.isSkipped && Goals.isMet(activity, progress.progress))
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

    /** Today's progress; switches to the new day when the tracking day rolls over while observed. */
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

    suspend fun getProgressOn(activityId: Long, date: String): DailyProgress? =
        dailyProgressDao.getProgressForActivityOnDate(activityId, date)

    /**
     * Increment progress for an activity today.
     * Creates a new DailyProgress row if none exists.
     * Automatically marks complete when the goal is met.
     */
    /** @param refreshWidgets false when a widget tap redraws the widgets itself (tapped one first) */
    suspend fun incrementProgress(activityId: Long, amount: Float = 1f, refreshWidgets: Boolean = true) =
        addProgress(activityId, amount, refreshWidgets = refreshWidgets)

    /**
     * Adds [amount] (in the activity's own unit) to its progress on [date], creating the row
     * if needed and marking it complete once the goal is met. The focus timer calls this once
     * per tracking day, so a session that crosses the day start counts towards both days.
     */
    suspend fun addProgress(activityId: Long, amount: Float, date: String = today(), refreshWidgets: Boolean = true) {
        val activity = activityDao.getById(activityId) ?: return
        writeDay(activity, date, refreshWidgets) { it.copy(progress = (it.progress + amount).coerceAtLeast(0f)) }
    }

    /**
     * Raises today's progress for [activity] to at least [value] (LeetCode, Health and other
     * auto-logs). Never lowers it, and never un-completes a day.
     */
    suspend fun raiseProgressTo(activity: Activity, value: Float, date: String = today()) {
        val existing = dailyProgressDao.getProgressForActivityOnDate(activity.id, date)
        if (existing != null && existing.progress >= value) return
        writeDay(activity, date, recompute = false) {
            it.copy(progress = value, isCompleted = it.isCompleted || Goals.isMet(activity, value))
        }
    }

    /**
     * Decrement progress for an activity on [date] (today by default).
     * Clamps to 0 — never goes negative.
     */
    suspend fun decrementProgress(
        activityId: Long,
        amount: Float = 1f,
        refreshWidgets: Boolean = true,
        date: String = today()
    ) {
        dailyProgressDao.getProgressForActivityOnDate(activityId, date) ?: return
        val activity = activityDao.getById(activityId) ?: return
        writeDay(activity, date, refreshWidgets) { it.copy(progress = (it.progress - amount).coerceAtLeast(0f)) }
    }

    /** Sets [date]'s progress outright (editing a past day). */
    suspend fun setProgress(activityId: Long, date: String, value: Float) {
        val activity = activityDao.getById(activityId) ?: return
        writeDay(activity, date) { it.copy(progress = value.coerceAtLeast(0f)) }
    }

    /**
     * Toggle completion for an activity on [date]. Limits have nothing to tick off: their days
     * are good until they go over.
     */
    suspend fun toggleComplete(activityId: Long, date: String = today()) {
        val activity = activityDao.getById(activityId) ?: return
        if (Goals.isLimit(activity)) return
        writeDay(activity, date, recompute = false) {
            if (it.isCompleted) it.copy(isCompleted = false)
            else it.copy(isCompleted = true, isSkipped = false, progress = maxOf(it.progress, activity.dailyGoal))
        }
    }

    /** Marks [date] as skipped (ill, travelling): it neither counts nor breaks the streak. */
    suspend fun setSkipped(activityId: Long, date: String, skipped: Boolean) {
        val activity = activityDao.getById(activityId) ?: return
        writeDay(activity, date) { it.copy(isSkipped = skipped) }
    }

    suspend fun setDayNote(activityId: Long, date: String, note: String) {
        val activity = activityDao.getById(activityId) ?: return
        writeDay(activity, date, recompute = false) { it.copy(notes = note.trim()) }
    }

    /** 1–5, or 0 to clear. */
    suspend fun setDayRating(activityId: Long, date: String, rating: Int) {
        val activity = activityDao.getById(activityId) ?: return
        writeDay(activity, date, recompute = false) { it.copy(rating = rating.coerceIn(0, 5)) }
    }

    /**
     * Applies [change] to [activity]'s row for [date], creating it when missing. With
     * [recompute], completion follows the goal (a skipped day is never complete).
     */
    private suspend fun writeDay(
        activity: Activity,
        date: String,
        refreshWidgets: Boolean = true,
        recompute: Boolean = true,
        change: (DailyProgress) -> DailyProgress
    ) {
        val existing = dailyProgressDao.getProgressForActivityOnDate(activity.id, date)
        val changed = change(existing ?: DailyProgress(activityId = activity.id, date = date))
        val row = changed.copy(
            isCompleted = if (recompute) !changed.isSkipped && Goals.isMet(activity, changed.progress) else changed.isCompleted,
            lastUpdated = System.currentTimeMillis()
        )
        if (existing != null) dailyProgressDao.update(row) else dailyProgressDao.insert(row)
        if (refreshWidgets) trackerChanged()
    }

    suspend fun getAllDailyProgressSnapshot(): List<DailyProgress> =
        dailyProgressDao.getAllSnapshot()

    suspend fun insertAllProgress(progressList: List<DailyProgress>) {
        dailyProgressDao.insertAll(progressList)
        trackerChanged()
    }

    /** Rows for one activity over the last [days] days (heatmap, strength, editing). */
    suspend fun getHistory(activityId: Long, days: Long = Tracking.HISTORY_DAYS): List<DailyProgress> =
        dailyProgressDao.getHistorySnapshot(activityId, daysAgo(days))

    suspend fun getTotalSince(activityId: Long, date: String): Float = dailyProgressDao.getTotalSince(activityId, date)

    suspend fun getLifetimeTotal(activityId: Long): Float = dailyProgressDao.getLifetimeTotal(activityId)

    // ─── Streaks ────────────────────────────────────────────────

    /**
     * The current streak for an activity: consecutive good days (or weeks, for a weekly
     * target) ending today or yesterday, where rest days and skipped days are neutral and
     * banked streak freezes cover single missed days (see [StreakEngine], [Tracking]).
     */
    suspend fun calculateStreak(activityId: Long): Int = streakState(activityId).current

    /** Streak plus freezes banked and days saved by a freeze. */
    suspend fun streakState(activityId: Long): StreakState {
        val activity = activityDao.getById(activityId) ?: return StreakEngine.compute(emptyList(), DayClock.today())
        return trackingSummary(activity).streak
    }

    /** Streak, habit strength and today's schedule caption. */
    suspend fun trackingSummary(activity: Activity): TrackingSummary =
        trackingSummary(activity, getHistory(activity.id))

    fun trackingSummary(activity: Activity, history: List<DailyProgress>): TrackingSummary {
        val days = split(history)
        return Tracking.summarize(activity, days.progress, days.skipped, DayClock.today(), createdDate(activity), days.completed)
    }

    /** One activity's rows by day. */
    data class DayHistory(
        val progress: Map<LocalDate, Float>,
        val skipped: Set<LocalDate>,
        /** Days stored as complete (a day ticked off by hand counts even under the goal). */
        val completed: Set<LocalDate>
    )

    fun split(history: List<DailyProgress>): DayHistory {
        val progress = HashMap<LocalDate, Float>(history.size)
        val skipped = HashSet<LocalDate>()
        val completed = HashSet<LocalDate>()
        history.forEach { row ->
            val day = runCatching { LocalDate.parse(row.date, dateFormatter) }.getOrNull() ?: return@forEach
            progress[day] = row.progress
            if (row.isSkipped) skipped += day
            if (row.isCompleted && !row.isSkipped) completed += day
        }
        return DayHistory(progress, skipped, completed)
    }

    fun createdDate(activity: Activity): LocalDate = DayClock.dateOf(activity.createdAt)

    /**
     * Activities still to do today: due by their schedule, and not yet done or skipped.
     * Limits never appear: there's nothing to do for them.
     */
    suspend fun openToday(): List<Activity> {
        val today = DayClock.today()
        return activityDao.getAllActiveSnapshot().filter { activity ->
            if (Goals.isLimit(activity)) return@filter false
            val days = split(dailyProgressDao.getHistorySnapshot(activity.id, daysAgo(14)))
            today !in days.completed && Schedule.isDue(activity, days.completed, days.skipped, today)
        }
    }

    // ─── Statistics ─────────────────────────────────────────────

    /**
     * Calculate completion rate for a given date range: good days over due days, following
     * each activity's schedule and goal type. Returns a value between 0.0 and 1.0.
     */
    suspend fun getCompletionRate(startDate: String, endDate: String): Float {
        val activities = activityDao.getAllActiveSnapshot()
        if (activities.isEmpty()) return 0f
        val start = LocalDate.parse(startDate, dateFormatter)
        val end = minOf(LocalDate.parse(endDate, dateFormatter), DayClock.today())
        val rows = dailyProgressDao.getAllProgressRangeSnapshot(startDate, endDate).groupBy { it.activityId }

        var met = 0.0
        var due = 0.0
        activities.forEach { activity ->
            val days = split(rows[activity.id].orEmpty())
            val (m, d) = Tracking.completion(activity, days.progress, days.skipped, start, end, createdDate(activity), days.completed)
            met += m
            due += d
        }
        if (due <= 0.0) return 0f
        return (met / due).toFloat().coerceIn(0f, 1f)
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
                val activity = activities[p.activityId]
                when {
                    activity == null || Goals.isLimit(activity) -> 0.0
                    activity.unit == "hrs" -> p.progress.toDouble()
                    activity.unit == "mins" -> p.progress / 60.0
                    else -> 0.0
                }
            }
            .toFloat()
    }

    // ─── Commitment ─────────────────────────────────────────────

    /**
     * Applies lowered goals whose waiting week is over (commitment mode, see
     * dev.diligent.app.planning.Commitment). Runs on app start and from the daily jobs.
     */
    suspend fun applyDueGoalChanges() {
        val today = today()
        activityDao.getAllSnapshot()
            .filter { it.pendingGoalDate.isNotEmpty() && it.pendingGoalDate <= today && it.pendingGoal > 0f }
            .forEach { updateActivity(it.copy(dailyGoal = it.pendingGoal, pendingGoal = 0f, pendingGoalDate = "")) }
    }

    // ─── Focus sessions & day logs ──────────────────────────────

    suspend fun saveFocusRecord(record: FocusRecord): Long = focusRecordDao.insert(record)
    suspend fun updateFocusRecord(record: FocusRecord) = focusRecordDao.update(record)
    suspend fun getFocusRecord(id: Long): FocusRecord? = focusRecordDao.getById(id)
    fun observeFocusRecordsSince(from: Long): Flow<List<FocusRecord>> = focusRecordDao.observeSince(from)
    suspend fun getFocusRecords(from: Long, to: Long): List<FocusRecord> = focusRecordDao.between(from, to)
    suspend fun getAllFocusRecords(): List<FocusRecord> = focusRecordDao.getAll()
    suspend fun insertAllFocusRecords(records: List<FocusRecord>) = focusRecordDao.insertAll(records)

    suspend fun getDayLog(date: String = today()): DayLog = dayLogDao.get(date) ?: DayLog(date)
    fun observeDayLog(date: String): Flow<DayLog?> = dayLogDao.observe(date)
    suspend fun getDayLogs(startDate: String, endDate: String): List<DayLog> = dayLogDao.range(startDate, endDate)
    fun observeDayLogs(startDate: String, endDate: String): Flow<List<DayLog>> = dayLogDao.observeRange(startDate, endDate)
    suspend fun getAllDayLogs(): List<DayLog> = dayLogDao.getAll()
    suspend fun insertAllDayLogs(logs: List<DayLog>) = dayLogDao.insertAll(logs)

    private val dayLogMutex = Mutex()

    /** Read-modify-write of one day's log, so the health sync and the mood picker can't clobber each other. */
    suspend fun updateDayLog(date: String = today(), change: (DayLog) -> DayLog) = dayLogMutex.withLock {
        val current = dayLogDao.get(date) ?: DayLog(date)
        dayLogDao.upsert(change(current).copy(date = date, updatedAt = System.currentTimeMillis()))
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

    /** Today's tracking date (see [DayClock]: the day may start after midnight). */
    fun today(): String = DayClock.todayString()

    /**
     * Emits today's date, then again just after each tracking-day start.
     * Only one suspended delay per day — no polling.
     */
    fun todayFlow(): Flow<String> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(DayClock.dateOf(now).format(dateFormatter))
            delay(DayClock.nextDayStart(now) - now + 1_000)
        }
    }.distinctUntilChanged()

    fun daysAgo(days: Long): String =
        DayClock.today().minusDays(days).format(dateFormatter)

    fun startOfWeek(): String =
        DayClock.today().with(java.time.DayOfWeek.MONDAY).format(dateFormatter)

    fun startOfMonth(): String =
        DayClock.today().withDayOfMonth(1).format(dateFormatter)

    /** Keeps the home-screen tracker widget in sync with in-app changes. */
    private suspend fun trackerChanged() {
        WidgetUpdater.refreshTracker(context)
    }

    companion object {
        /** Contribution pages fetched at once during a sync. */
        const val GITHUB_SYNC_PARALLELISM = 4
    }
}
