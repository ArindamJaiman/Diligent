package dev.diligent.app.screentime

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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

/** One usage event: [type] is [UsageCounter.RESUMED], [UsageCounter.PAUSED] or [UsageCounter.SCREEN_OFF]. */
data class UsageEvent(val pkg: String, val type: Int, val time: Long)

/**
 * Pure foreground-time counter over usage events, so it can be unit tested.
 * An app already open at [from] (first event is a pause) is counted from [from]; apps still
 * open at [to] are counted up to [to]; screen-off closes everything.
 */
object UsageCounter {
    const val RESUMED = 1
    const val PAUSED = 2
    const val SCREEN_OFF = 3

    fun foregroundTime(events: List<UsageEvent>, from: Long, to: Long): Map<String, Long> {
        val totals = HashMap<String, Long>()
        val open = HashMap<String, Long>()
        val seen = HashSet<String>()
        fun add(pkg: String, ms: Long) {
            if (ms > 0) totals[pkg] = (totals[pkg] ?: 0L) + ms
        }
        for (e in events.sortedBy { it.time }) {
            if (e.time < from || e.time > to) continue
            when (e.type) {
                RESUMED -> {
                    seen += e.pkg
                    // Another app coming forward ends whatever was open.
                    open.entries.removeAll { (pkg, start) ->
                        if (pkg != e.pkg) add(pkg, e.time - start)
                        pkg != e.pkg
                    }
                    open.putIfAbsent(e.pkg, e.time)
                }
                PAUSED -> {
                    val start = open.remove(e.pkg)
                    when {
                        start != null -> add(e.pkg, e.time - start)
                        e.pkg !in seen -> add(e.pkg, e.time - from)
                    }
                    seen += e.pkg
                }
                SCREEN_OFF -> {
                    open.forEach { (pkg, start) -> add(pkg, e.time - start) }
                    open.clear()
                }
            }
        }
        open.forEach { (pkg, start) -> add(pkg, to - start) }
        return totals
    }

    /** Hours logged today on time-based activities (hrs, mins), in millis. */
    fun focusMillis(activities: List<Activity>, today: List<DailyProgress>): Long {
        val byId = activities.associateBy { it.id }
        return today.sumOf { row ->
            val a = byId[row.activityId] ?: return@sumOf 0L
            when (a.unit.trim().lowercase()) {
                "hrs", "hr", "hours", "hour", "h" -> (row.progress * 3_600_000).toLong()
                "mins", "min", "minutes" -> (row.progress * 60_000).toLong()
                else -> 0L
            }
        }
    }
}

data class AppUse(val pkg: String, val label: String, val millis: Long, val distracting: Boolean)

data class ScreenSummary(
    val allowed: Boolean,
    val focusMillis: Long,
    val distractMillis: Long,
    val screenMillis: Long,
    val apps: List<AppUse>,
    val at: Long
) {
    /** Share of (focus + distraction) that was focus, 0..1. */
    val focusShare: Float get() = if (focusMillis + distractMillis == 0L) 0f else focusMillis.toFloat() / (focusMillis + distractMillis)
}

data class ScreenSettings(
    val distracting: Set<String> = ScreenTimeService.DEFAULT_DISTRACTING,
    val nudge: Boolean = true,
    val hour: Int = 21,
    val minute: Int = 30
)

@Singleton
class ScreenTimeService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DiligentRepository
) {
    private val prefs = context.getSharedPreferences("screen_time_prefs", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<ScreenSettings> = _settings

    fun update(transform: (ScreenSettings) -> ScreenSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putStringSet("distracting", next.distracting)
            .putBoolean("nudge", next.nudge)
            .putInt("hour", next.hour)
            .putInt("minute", next.minute)
            .apply()
        _settings.value = next
        ScreenTimeWidgetUpdater.refreshAsync(context)
    }

    fun hasAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun accessIntent(): Intent = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Today's split, computed on the phone from Android's usage events (no network). */
    suspend fun today(now: Long = System.currentTimeMillis()): ScreenSummary {
        val day = LocalDate.now()
        val from = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val focus = UsageCounter.focusMillis(repository.getAllActiveSnapshot(), repository.getTodayProgressSnapshot())
        if (!hasAccess()) return ScreenSummary(false, focus, 0, 0, emptyList(), now)
        val usage = UsageCounter.foregroundTime(readEvents(from, now), from, now)
            .filterKeys { it != context.packageName && !isLauncherOrSystemUi(it) }
        val distracting = _settings.value.distracting
        val apps = usage.entries.sortedByDescending { it.value }.map { (pkg, ms) -> AppUse(pkg, label(pkg), ms, pkg in distracting) }
        return ScreenSummary(
            allowed = true,
            focusMillis = focus,
            distractMillis = apps.filter { it.distracting }.sumOf { it.millis },
            screenMillis = apps.sumOf { it.millis },
            apps = apps,
            at = now
        )
    }

    private fun readEvents(from: Long, to: Long): List<UsageEvent> {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val events = manager.queryEvents(from, to) ?: return emptyList()
        val out = ArrayList<UsageEvent>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val type = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageCounter.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> UsageCounter.PAUSED
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> UsageCounter.SCREEN_OFF
                else -> continue
            }
            out += UsageEvent(e.packageName ?: continue, type, e.timeStamp)
        }
        return out
    }

    private val launchers by lazy {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.toSet()
    }

    private fun isLauncherOrSystemUi(pkg: String) = pkg in launchers || pkg == "com.android.systemui"

    fun label(pkg: String): String = try {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    suspend fun runNudge() {
        val s = _settings.value
        if (!s.nudge) return
        val summary = today()
        if (!summary.allowed) return
        if (summary.distractMillis < 30 * 60_000L || summary.distractMillis <= summary.focusMillis) return
        notify(summary)
    }

    fun nextRun(now: ZonedDateTime): ZonedDateTime? {
        val s = _settings.value
        if (!s.nudge || !hasAccess()) return null
        var next = now.with(LocalTime.of(s.hour, s.minute)).withSecond(0).withNano(0)
        if (!next.isAfter(now.plusSeconds(30))) next = next.plusDays(1)
        return next
    }

    @SuppressLint("MissingPermission")
    private fun notify(summary: ScreenSummary) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen time nudge", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Evening note when distracting apps beat focused work"
            }
        )
        val top = summary.apps.filter { it.distracting }.take(2).joinToString(", ") { "${it.label} ${ToolFormat.duration(it.millis)}" }
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID, openToolIntent(context, ToolRoutes.SCREEN_TIME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Scrolled ${ToolFormat.duration(summary.distractMillis)} vs focused ${ToolFormat.duration(summary.focusMillis)}")
            .setContentText(top.ifBlank { "Distracting apps beat focused work today" })
            .setContentIntent(open)
            .setAutoCancel(true)
            .setColor(0xFFFF375F.toInt())
            .build()
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return
        try {
            compat.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun read(): ScreenSettings = ScreenSettings(
        distracting = prefs.getStringSet("distracting", null)?.toSet() ?: DEFAULT_DISTRACTING,
        nudge = prefs.getBoolean("nudge", true),
        hour = prefs.getInt("hour", 21),
        minute = prefs.getInt("minute", 30)
    )

    companion object {
        private const val CHANNEL = "screen_time_nudge"
        private const val NOTIFICATION_ID = 4001

        val DEFAULT_DISTRACTING = setOf(
            "com.instagram.android",
            "com.google.android.youtube",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.twitter.android",
            "com.reddit.frontpage",
            "com.snapchat.android",
            "com.facebook.katana",
            "com.netflix.mediaclient",
            "in.startv.hotstar",
            "com.amazon.avod.thirdpartyclient",
            "com.instagram.barcelona",
            "tv.twitch.android.app",
            "com.google.android.apps.youtube.kids",
            "com.jio.media.jiobeats"
        )
    }
}
