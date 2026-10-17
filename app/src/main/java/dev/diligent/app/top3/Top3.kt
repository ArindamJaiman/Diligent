package dev.diligent.app.top3

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.R
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.tools.ToolRoutes
import dev.diligent.app.tools.openToolIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

data class CalendarEvent(val title: String, val begin: Long, val end: Long)

data class BusySummary(
    val events: Int,
    val busyMillis: Long,
    /** Unscheduled time from now until [CalendarBusy.DAY_END] tonight. */
    val freeLeftMillis: Long,
    val next: CalendarEvent?,
    val current: CalendarEvent?
)

/** Today's busy time from the phone's calendars (read locally, never sent anywhere). */
object CalendarBusy {

    val DAY_END: LocalTime = LocalTime.of(22, 0)

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Pure: clips events to [from, to) and merges overlaps. */
    fun busyBetween(events: List<CalendarEvent>, from: Long, to: Long): Long {
        if (to <= from) return 0
        val spans = events.map { maxOf(it.begin, from) to minOf(it.end, to) }.filter { it.first < it.second }.sortedBy { it.first }
        var total = 0L
        var curStart = -1L
        var curEnd = -1L
        for ((s, e) in spans) {
            if (s > curEnd) {
                if (curEnd > curStart) total += curEnd - curStart
                curStart = s
                curEnd = e
            } else if (e > curEnd) {
                curEnd = e
            }
        }
        if (curEnd > curStart) total += curEnd - curStart
        return total
    }

    fun summarize(events: List<CalendarEvent>, now: Long, day: LocalDate, zone: ZoneId): BusySummary {
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val workEnd = day.atTime(DAY_END).atZone(zone).toInstant().toEpochMilli()
        val window = (workEnd - now).coerceAtLeast(0)
        return BusySummary(
            events = events.size,
            busyMillis = busyBetween(events, dayStart, dayEnd),
            freeLeftMillis = (window - busyBetween(events, now, workEnd)).coerceAtLeast(0),
            next = events.filter { it.begin > now }.minByOrNull { it.begin },
            current = events.firstOrNull { it.begin <= now && it.end > now }
        )
    }

    /** Timed, busy events overlapping [day]. All-day and "free" events are ignored. */
    fun read(context: Context, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<CalendarEvent>? {
        if (!hasPermission(context)) return null
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.AVAILABILITY
        )
        return try {
            context.contentResolver.query(uri, projection, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        if (c.getInt(3) == 1) continue
                        if (c.getInt(4) == CalendarContract.Events.AVAILABILITY_FREE) continue
                        add(CalendarEvent(c.getString(0) ?: "Busy", c.getLong(1), c.getLong(2)))
                    }
                }
            } ?: emptyList()
        } catch (e: SecurityException) {
            null
        }
    }
}

data class Top3Pick(val activity: Activity, val progress: Float, val done: Boolean)

data class Top3Settings(val prompt: Boolean = true, val hour: Int = 8, val minute: Int = 0)

data class Top3State(val date: LocalDate, val ids: List<Long>, val settings: Top3Settings)

/**
 * Today's Top 3: up to three activities you commit to each morning. Picks reset at local
 * midnight; yesterday's unfinished picks are offered first the next day.
 */
@Singleton
class Top3Service @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DiligentRepository
) {
    private val prefs = context.getSharedPreferences("top3_prefs", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Top3State> = _state

    fun current(): Top3State = read().also { _state.value = it }

    /** Today's ids (empty when the stored picks are from another day). */
    fun todayIds(today: LocalDate = LocalDate.now()): List<Long> = current().let { if (it.date == today) it.ids else emptyList() }

    fun setPicks(ids: List<Long>, today: LocalDate = LocalDate.now()) {
        val previous = current()
        val editor = prefs.edit().putString("date", today.toString()).putString("ids", ids.take(MAX).joinToString(","))
        // Remember yesterday's list once, to suggest unfinished picks.
        if (previous.date != today && previous.ids.isNotEmpty()) {
            editor.putString("prev_date", previous.date.toString()).putString("prev_ids", previous.ids.joinToString(","))
        }
        editor.apply()
        _state.value = read()
        Top3WidgetUpdater.refreshAsync(context)
    }

    fun updateSettings(transform: (Top3Settings) -> Top3Settings) {
        val next = transform(_state.value.settings)
        prefs.edit().putBoolean("prompt", next.prompt).putInt("hour", next.hour).putInt("minute", next.minute).apply()
        _state.value = read()
    }

    suspend fun picks(today: LocalDate = LocalDate.now()): List<Top3Pick> {
        val ids = todayIds(today)
        if (ids.isEmpty()) return emptyList()
        val active = repository.getAllActiveSnapshot().associateBy { it.id }
        val progress = repository.getTodayProgressSnapshot().associateBy { it.activityId }
        return ids.mapNotNull { id -> active[id]?.let { pick(it, progress[id]) } }
    }

    /** Yesterday's unfinished picks first, then everything else in dashboard order. */
    suspend fun suggestedOrder(activities: List<Activity>, today: LocalDate = LocalDate.now()): List<Long> {
        val prevDate = prefs.getString("prev_date", null)
        val state = current()
        val yesterdayIds = when {
            state.date == today.minusDays(1) -> state.ids
            prevDate == today.minusDays(1).toString() -> prefs.getString("prev_ids", "").orEmpty().split(",").mapNotNull { it.toLongOrNull() }
            else -> emptyList()
        }
        val yesterday = today.minusDays(1).toString()
        val doneYesterday = repository.getAllProgressRangeSnapshot(yesterday, yesterday).filter { it.isCompleted }.map { it.activityId }.toSet()
        val carry = yesterdayIds.filter { it !in doneYesterday && activities.any { a -> a.id == it } }
        return carry + activities.map { it.id }.filter { it !in carry }
    }

    suspend fun runPrompt() {
        val s = current()
        if (!s.settings.prompt || todayIds().isNotEmpty()) return
        if (repository.getAllActiveSnapshot().isEmpty()) return
        val busy = CalendarBusy.read(context, LocalDate.now())?.let {
            CalendarBusy.summarize(it, System.currentTimeMillis(), LocalDate.now(), ZoneId.systemDefault())
        }
        notifyPrompt(busy)
    }

    fun nextRun(now: ZonedDateTime): ZonedDateTime? {
        val s = current().settings
        if (!s.prompt) return null
        var next = now.with(LocalTime.of(s.hour, s.minute)).withSecond(0).withNano(0)
        if (!next.isAfter(now.plusSeconds(30))) next = next.plusDays(1)
        return next
    }

    @SuppressLint("MissingPermission")
    private fun notifyPrompt(busy: BusySummary?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Today's Top 3", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Morning prompt to pick your three must-dos"
            }
        )
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID, openToolIntent(context, ToolRoutes.TOP3),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = busy?.let {
            "Calendar: ${it.events} event${if (it.events == 1) "" else "s"}, ${ToolFormat.duration(it.busyMillis)} busy · " +
                "${ToolFormat.duration(it.freeLeftMillis)} free before ${CalendarBusy.DAY_END}"
        } ?: "Three things that would make today a win."
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pick today's top 3")
            .setContentText(text)
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

    private fun read(): Top3State = Top3State(
        date = prefs.getString("date", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.MIN,
        ids = prefs.getString("ids", "").orEmpty().split(",").mapNotNull { it.toLongOrNull() },
        settings = Top3Settings(
            prompt = prefs.getBoolean("prompt", true),
            hour = prefs.getInt("hour", 8),
            minute = prefs.getInt("minute", 0)
        )
    )

    companion object {
        const val MAX = 3
        private const val CHANNEL = "top3_prompt"
        private const val NOTIFICATION_ID = 3901

        fun pick(activity: Activity, row: DailyProgress?) = Top3Pick(activity, row?.progress ?: 0f, row?.isCompleted == true)

        /** One tap on the widget: half an hour for hour goals, 15 min for minutes, else 1. */
        fun step(unit: String): Float = when (unit.trim().lowercase()) {
            "hrs", "hr", "hours", "hour", "h" -> 0.5f
            "mins", "min", "minutes" -> 15f
            else -> 1f
        }
    }
}
