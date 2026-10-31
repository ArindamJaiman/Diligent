package dev.diligent.app.planning

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.R
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.focus.domain.FocusMath
import dev.diligent.app.focus.domain.FocusStats
import dev.diligent.app.focus.shield.FocusCalendar
import dev.diligent.app.focus.shield.FocusShield
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.tools.ToolRoutes
import dev.diligent.app.tools.openToolIntent
import dev.diligent.app.top3.CalendarBusy
import dev.diligent.app.top3.Top3Service
import dev.diligent.app.tracking.DayClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class PlanSettings(
    /** The working day ends at this hour; nothing is planned after it. */
    val endHour: Int = 22,
    /** Build the plan automatically every morning and send it as a notification. */
    val autoMorning: Boolean = false,
    val morningHour: Int = 7,
    val morningMinute: Int = 30,
    /** Put planned blocks into the Focus calendar (Focus → Calendar log). */
    val writeCalendar: Boolean = false,
    /** Evening shutdown reminder. */
    val shutdownPrompt: Boolean = true,
    val shutdownHour: Int = 21,
    val shutdownMinute: Int = 30
)

@Serializable
data class StoredBlock(val activityId: Long, val start: Long, val end: Long, val eventId: Long = 0L)

@Serializable
data class StoredPlan(val date: String = "", val blocks: List<StoredBlock> = emptyList(), val madeAt: Long = 0L)

/** Where today's energy comes from, for the Plan screen. */
data class EnergyView(
    val lastNightMinutes: Int?,
    /** Minutes owed over the last 14 nights with data. */
    val sleepDebt: Int?,
    val peak: Slot?,
    /** "best focus hours" or "after waking". */
    val peakSource: String?
)

/**
 * Today's plan (Structured, TimeBloc, Reclaim): open activities fitted around calendar events,
 * deep work into the energy peak (Rise), optionally written to a calendar, and the evening
 * shutdown (Sunsama). Plans live in SharedPreferences; one day at a time.
 */
@Singleton
class PlanService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DiligentRepository,
    private val top3: Top3Service
) {
    private val prefs = context.getSharedPreferences("planner", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<PlanSettings> = _settings.asStateFlow()

    private val _plan = MutableStateFlow(readPlan())
    val plan: StateFlow<StoredPlan> = _plan.asStateFlow()

    fun update(change: (PlanSettings) -> PlanSettings) {
        val next = change(_settings.value)
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(next)).apply()
        _settings.value = next
    }

    /** Today's plan, or an empty one when the stored plan is from another day. */
    fun today(): StoredPlan = _plan.value.takeIf { it.date == DayClock.todayString() } ?: StoredPlan(DayClock.todayString())

    suspend fun energy(now: Long = System.currentTimeMillis()): EnergyView {
        val zone = ZoneId.systemDefault()
        val today = DayClock.today()
        val logs = repository.getDayLogs(today.minusDays(14).toString(), today.toString())
        val nights = logs.mapNotNull { it.sleepMinutes }
        val todayLog = logs.firstOrNull { it.date == today.toString() }
        val records = repository.getFocusRecords(now - 30L * 24 * 3_600_000, now)
        val best = if (records.size >= 5) FocusStats.bestWindow(FocusStats.minutesByHour(records, zone)) else null
        val wake = todayLog?.let { log -> log.sleepStart?.let { start -> log.sleepMinutes?.let { start + it * 60_000L } } }
        val (peak, source) = when {
            best != null -> Energy.peakFromHour(today, best, zone) to "your best focus hours"
            wake != null -> Energy.peakAfterWaking(wake) to "2–5 h after waking"
            else -> null to null
        }
        return EnergyView(todayLog?.sleepMinutes, nights.takeIf { it.isNotEmpty() }?.let { Energy.sleepDebt(it) }, peak, source)
    }

    /** What's still to do today, as plannable minutes. Top 3 picks rank first. */
    private suspend fun tasks(): List<PlanTask> {
        val picks = top3.todayIds()
        val today = repository.today()
        return repository.openToday().mapNotNull { activity ->
            val done = repository.getProgressOn(activity.id, today)?.progress ?: 0f
            val minutes = remainingMinutes(activity, done)
            if (minutes <= 0) null
            else PlanTask(
                activityId = activity.id,
                minutes = minutes,
                deep = FocusMath.isTimeUnit(activity.unit) && (activity.dailyGoal >= 1f || activity.unit == "mins" && activity.dailyGoal >= 60f),
                priority = if (activity.id in picks) 2 else 0
            )
        }
    }

    /** Builds (or rebuilds) today's plan from now on; replaces calendar blocks it wrote before. */
    suspend fun replan(now: Long = System.currentTimeMillis()): StoredPlan {
        val zone = ZoneId.systemDefault()
        val day = DayClock.today()
        val end = day.atTime(LocalTime.of(_settings.value.endHour.coerceIn(12, 23), 59)).atZone(zone).toInstant().toEpochMilli()
        // Our own blocks from the last plan aren't "busy": they're about to be replaced.
        removeCalendarBlocks(today().blocks)
        val busy = CalendarBusy.read(context, day).orEmpty().map { Slot(it.begin, it.end) }
        val blocks = DayPlanner.plan(maxOf(now, DayClock.startOf(day)), end, busy, tasks(), energy(now).peak)
        val names = repository.getAllActiveSnapshot().associate { it.id to it.name }
        val stored = blocks.map { block ->
            val eventId = if (_settings.value.writeCalendar) addCalendarBlock(block, names[block.activityId] ?: "Focus") else 0L
            StoredBlock(block.activityId, block.start, block.end, eventId)
        }
        return save(StoredPlan(day.toString(), stored, now))
    }

    suspend fun clear() {
        removeCalendarBlocks(today().blocks)
        save(StoredPlan(DayClock.todayString()))
    }

    // ─── Calendar ───────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun addCalendarBlock(block: PlanBlock, name: String): Long {
        val calendarId = FocusShield.config(context).calendarId
        if (calendarId == 0L || !FocusCalendar.hasPermission(context)) return 0L
        return runCatching {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, "▢ $name")
                put(CalendarContract.Events.DESCRIPTION, "Planned by Diligent")
                put(CalendarContract.Events.DTSTART, block.start)
                put(CalendarContract.Events.DTEND, block.end)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE)
            }
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)?.let { ContentUris.parseId(it) } ?: 0L
        }.getOrDefault(0L)
    }

    @SuppressLint("MissingPermission")
    private fun removeCalendarBlocks(blocks: List<StoredBlock>) {
        if (!FocusCalendar.hasPermission(context)) return
        blocks.filter { it.eventId > 0 }.forEach { block ->
            runCatching {
                context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, block.eventId), null, null)
            }
        }
    }

    // ─── Scheduled jobs ─────────────────────────────────────────

    /** Morning: build the plan and send it. */
    suspend fun runMorning() {
        if (!_settings.value.autoMorning) return
        val plan = replan()
        if (plan.blocks.isEmpty()) return
        val names = repository.getAllActiveSnapshot().associate { it.id to it.name }
        val text = plan.blocks.take(4).joinToString(" · ") { "${names[it.activityId] ?: "?"} ${ToolFormat.clock(it.start)}" }
        notify(ID_MORNING, "Your day is planned", text, ToolRoutes.PLAN)
    }

    /** Evening: a nudge to close the day, unless it's already closed. */
    suspend fun runShutdownPrompt() {
        if (!_settings.value.shutdownPrompt) return
        if (repository.getDayLog().shutdownAt > 0L) return
        val open = repository.openToday()
        val text = if (open.isEmpty()) "Everything's done. Rate the day and pick tomorrow's top 3."
        else "${open.size} still open · carry them over, rate the day, pick tomorrow's top 3"
        notify(ID_SHUTDOWN, "Shut down the day", text, ToolRoutes.SHUTDOWN)
    }

    fun nextMorning(now: ZonedDateTime): ZonedDateTime? = _settings.value.takeIf { it.autoMorning }?.let { at(now, it.morningHour, it.morningMinute) }

    fun nextShutdown(now: ZonedDateTime): ZonedDateTime? = _settings.value.takeIf { it.shutdownPrompt }?.let { at(now, it.shutdownHour, it.shutdownMinute) }

    private fun at(now: ZonedDateTime, hour: Int, minute: Int): ZonedDateTime {
        var next = now.with(LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))).withSecond(0).withNano(0)
        if (!next.isAfter(now.plusSeconds(30))) next = next.plusDays(1)
        return next
    }

    @SuppressLint("MissingPermission")
    private fun notify(id: Int, title: String, text: String, route: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Planning", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Morning plan and evening shutdown"
        })
        val open = PendingIntent.getActivity(context, id, openToolIntent(context, route), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setColor(0xFFFFFFFF.toInt())
            .build()
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return
        try {
            compat.notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    // ─── Storage ────────────────────────────────────────────────

    private fun save(plan: StoredPlan): StoredPlan {
        prefs.edit().putString(KEY_PLAN, json.encodeToString(plan)).apply()
        _plan.value = plan
        return plan
    }

    private fun readSettings(): PlanSettings =
        prefs.getString(KEY_SETTINGS, null)?.let { runCatching { json.decodeFromString<PlanSettings>(it) }.getOrNull() } ?: PlanSettings()

    private fun readPlan(): StoredPlan =
        prefs.getString(KEY_PLAN, null)?.let { runCatching { json.decodeFromString<StoredPlan>(it) }.getOrNull() } ?: StoredPlan()

    companion object {
        private const val KEY_SETTINGS = "settings"
        private const val KEY_PLAN = "plan"
        private const val CHANNEL = "planning"
        private const val ID_MORNING = 4301
        private const val ID_SHUTDOWN = 4302

        /** Minutes of work left: time goals by what's missing, counted goals at 25 min a unit (max 2 h). */
        fun remainingMinutes(activity: Activity, done: Float): Int {
            val left = (activity.dailyGoal - done).coerceAtLeast(0f)
            return when (activity.unit) {
                "hrs" -> (left * 60).toInt()
                "mins" -> left.toInt()
                else -> (kotlin.math.ceil(left) * 25).toInt().coerceAtMost(120)
            }
        }
    }
}
