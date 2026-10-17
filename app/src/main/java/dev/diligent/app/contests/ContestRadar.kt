package dev.diligent.app.contests

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.R
import dev.diligent.app.leetcode.data.LeetCodePrefs
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.tools.ToolsReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalTime
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class RadarCache(
    val fetchedAt: Long = 0L,
    val contests: List<RadarContest> = emptyList(),
    val ratings: List<RadarRating> = emptyList(),
    val ratingsAt: Long = 0L,
    val errors: Map<String, String> = emptyMap()
)

data class RadarSettings(
    val platforms: Set<Platform> = Platform.FETCHED.toSet(),
    val handles: Map<Platform, String> = emptyMap(),
    val alerts: Boolean = true,
    val leadMin: Int = 30,
    val skipLong: Boolean = true
) {
    val enabled: Boolean get() = platforms.isNotEmpty()

    companion object {
        val LEAD_CHOICES = listOf(10, 30, 60)
        /** Contests longer than this (heuristic marathons, long challenges) are hidden when skipLong is on. */
        const val LONG_SECONDS = 5 * 3600L
    }
}

data class RadarState(val settings: RadarSettings, val cache: RadarCache, val syncing: Boolean)

/**
 * Contest Radar: one list of upcoming contests across Codeforces, AtCoder, CodeChef and
 * LeetCode, your ratings, and a heads-up before each start.
 *
 * Fetches only when you open the screen (data over 6 h old), tap SYNC, or once a day at
 * 08:00. Ratings change only after contests, so they refresh at most every 3 days unless
 * you sync by hand. Heads-ups use setAndAllowWhileIdle alarms, re-armed on every sync.
 */
@Singleton
class ContestRadar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val leetCodePrefs: LeetCodePrefs
) {
    private val prefs = context.getSharedPreferences("contest_radar_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(RadarState(readSettings(), readCache(), false))
    val state: StateFlow<RadarState> = _state

    fun current(): RadarState = _state.value

    fun updateSettings(transform: (RadarSettings) -> RadarSettings) {
        val next = transform(_state.value.settings)
        prefs.edit()
            .putString("platforms", next.platforms.joinToString(",") { it.name })
            .putString("handles", next.handles.entries.joinToString(";") { "${it.key.name}=${it.value.trim()}" })
            .putBoolean("alerts", next.alerts)
            .putInt("lead", next.leadMin)
            .putBoolean("skip_long", next.skipLong)
            .apply()
        _state.value = _state.value.copy(settings = next)
        armAlarms()
        ContestWidgetUpdater.refreshAsync(context)
    }

    /** Upcoming and running contests on enabled platforms, LeetCode included, soonest first. */
    fun contests(now: Long = System.currentTimeMillis()): List<RadarContest> {
        val s = _state.value
        val leetcode = leetCodePrefs.current().snapshot?.upcoming.orEmpty().map {
            RadarContest(Platform.LEETCODE, it.slug, it.title, it.startTime * 1000, it.durationSec.toLong(), it.url)
        }
        return (s.cache.contests.filter { it.platform in s.settings.platforms } + leetcode)
            .filter { it.endMillis > now }
            .filter { !s.settings.skipLong || it.durationSec <= RadarSettings.LONG_SECONDS }
            .distinctBy { it.platform to it.id }
            .sortedBy { it.startMillis }
    }

    /** Ratings for platforms with a handle, plus your LeetCode contest rating. */
    fun ratings(): List<RadarRating> {
        val s = _state.value
        val lc = leetCodePrefs.current().snapshot?.let { snap ->
            snap.contest?.let { RadarRating(Platform.LEETCODE, snap.username, it.rating.toInt(), null) }
        }
        return s.cache.ratings.filter { it.platform in s.settings.platforms && s.settings.handles[it.platform].equals(it.handle, true) } + listOfNotNull(lc)
    }

    suspend fun sync(force: Boolean = false, ratings: Boolean = force): String? = mutex.withLock {
        val s = _state.value
        if (!s.settings.enabled) return@withLock "Turn on at least one platform"
        val now = System.currentTimeMillis()
        if (!force && now - s.cache.fetchedAt < 60_000) return@withLock null
        _state.value = s.copy(syncing = true)
        ContestWidgetUpdater.refresh(context)

        val refreshRatings = ratings || now - s.cache.ratingsAt > RATINGS_EVERY_MS
        val result = withContext(Dispatchers.IO) { fetchAll(s.settings, s.cache, now, refreshRatings) }
        prefs.edit().putString("cache", json.encodeToString(RadarCache.serializer(), result)).apply()
        _state.value = RadarState(_state.value.settings, result, false)
        armAlarms()
        ContestWidgetUpdater.refresh(context)
        result.errors.takeIf { it.isNotEmpty() }?.entries?.joinToString(" · ") { "${it.key}: ${it.value}" }
    }

    private suspend fun fetchAll(settings: RadarSettings, previous: RadarCache, now: Long, withRatings: Boolean): RadarCache = coroutineScope {
        val errors = mutableMapOf<String, String>()
        val contestJobs = settings.platforms.filter { it in Platform.FETCHED }.map { platform ->
            async {
                platform to runCatching {
                    ContestSources.parseContests(platform, ContestSources.get(ContestSources.contestsUrl(platform)!!), now, HORIZON_MS)
                }
            }
        }
        val ratingJobs = if (!withRatings) emptyList() else settings.handles.filter { it.value.isNotBlank() && it.key in settings.platforms }.map { (platform, handle) ->
            async {
                platform to runCatching {
                    ContestSources.ratingUrl(platform, handle)?.let { ContestSources.parseRating(platform, ContestSources.get(it), handle) }
                }
            }
        }
        val contests = contestJobs.awaitAll().flatMap { (platform, result) ->
            result.getOrElse { e ->
                if (e is CancellationException) throw e
                errors[platform.label] = e.message ?: e.javaClass.simpleName
                // Keep what we knew for this platform rather than showing nothing.
                previous.contests.filter { it.platform == platform && it.endMillis > now }
            }
        }
        val ratings = if (!withRatings) previous.ratings else ratingJobs.awaitAll().mapNotNull { (platform, result) ->
            result.getOrElse {
                errors["${platform.label} rating"] = it.message ?: "failed"
                previous.ratings.firstOrNull { r -> r.platform == platform }
            }
        }
        RadarCache(
            fetchedAt = System.currentTimeMillis(),
            contests = contests.sortedBy { it.startMillis },
            ratings = ratings,
            ratingsAt = if (withRatings) System.currentTimeMillis() else previous.ratingsAt,
            errors = errors
        )
    }

    // ─── Heads-up alarms ────────────────────────────────────────

    /** Arms one alarm per upcoming contest (next 7 days, LeetCode excluded — it has its own). */
    fun armAlarms(now: Long = System.currentTimeMillis()) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        repeat(MAX_ALARMS) { alarmManager.cancel(alarmIntent(it, null)) }
        val s = _state.value.settings
        if (!s.enabled || !s.alerts) return
        contests(now)
            .filter { it.platform != Platform.LEETCODE && it.startMillis - s.leadMin * 60_000L > now + 5_000 && it.startMillis < now + 7 * DAY_MS }
            .take(MAX_ALARMS)
            .forEachIndexed { index, contest ->
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    contest.startMillis - s.leadMin * 60_000L,
                    alarmIntent(index, contest)
                )
            }
    }

    private fun alarmIntent(index: Int, contest: RadarContest?): PendingIntent {
        val intent = Intent(context, ToolsReceiver::class.java).setAction(ToolsReceiver.ACTION_CONTEST_ALERT)
        if (contest != null) {
            intent.putExtra(ToolsReceiver.EXTRA_PAYLOAD, json.encodeToString(RadarContest.serializer(), contest))
        }
        return PendingIntent.getBroadcast(context, REQUEST_ALARM + index, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @SuppressLint("MissingPermission")
    fun onAlarm(payload: String?) {
        val contest = payload?.let { runCatching { json.decodeFromString(RadarContest.serializer(), it) }.getOrNull() } ?: return
        val s = _state.value.settings
        if (!s.alerts || contest.platform !in s.platforms) return
        val now = System.currentTimeMillis()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Contest Radar", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Heads-up before Codeforces, AtCoder and CodeChef contests"
            }
        )
        val open = PendingIntent.getActivity(
            context, REQUEST_ALARM + 50 + (contest.id.hashCode() and 0x3f),
            Intent(Intent.ACTION_VIEW, Uri.parse(contest.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val minutes = ((contest.startMillis - now) / 60_000).coerceAtLeast(0)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${contest.platform.label} · ${contest.name}")
            .setContentText("Starts ${ToolFormat.clock(contest.startMillis)} (in $minutes min) · ${ToolFormat.duration(contest.durationSec * 1000)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setColor(contest.platform.color.toInt())
            .setTimeoutAfter((contest.endMillis - now).coerceAtLeast(60_000L))
            .build()
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return
        try {
            compat.notify(NOTIFICATION_BASE + (contest.platform.ordinal * 100) + (contest.id.hashCode() and 0x3f), notification)
        } catch (_: SecurityException) {
        }
    }

    fun nextRun(now: ZonedDateTime): ZonedDateTime? {
        if (!_state.value.settings.enabled) return null
        var next = now.with(LocalTime.of(8, 0)).withSecond(0).withNano(0)
        if (!next.isAfter(now.plusSeconds(30))) next = next.plusDays(1)
        return next
    }

    /** Opening the app refreshes data older than 6 hours (no-op otherwise). */
    suspend fun refreshIfStale() {
        val s = _state.value
        if (s.settings.enabled && System.currentTimeMillis() - s.cache.fetchedAt > STALE_MS) {
            try {
                sync(force = true, ratings = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ContestRadar", "refresh failed", e)
            }
        }
    }

    private fun readSettings(): RadarSettings {
        val platforms = prefs.getString("platforms", null)?.split(",")
            ?.mapNotNull { name -> Platform.entries.firstOrNull { it.name == name } }?.toSet()
            ?: Platform.FETCHED.toSet()
        val handles = prefs.getString("handles", "").orEmpty().split(";").mapNotNull { entry ->
            val (key, value) = entry.split("=", limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val platform = Platform.entries.firstOrNull { it.name == key } ?: return@mapNotNull null
            if (value.isBlank()) null else platform to value
        }.toMap()
        return RadarSettings(
            platforms = platforms,
            handles = handles,
            alerts = prefs.getBoolean("alerts", true),
            leadMin = prefs.getInt("lead", 30),
            skipLong = prefs.getBoolean("skip_long", true)
        )
    }

    private fun readCache(): RadarCache = prefs.getString("cache", null)?.let {
        runCatching { json.decodeFromString(RadarCache.serializer(), it) }.getOrNull()
    } ?: RadarCache()

    companion object {
        private const val CHANNEL = "contest_radar"
        private const val REQUEST_ALARM = 3601
        private const val NOTIFICATION_BASE = 3700
        private const val MAX_ALARMS = 8
        private const val DAY_MS = 86_400_000L
        const val HORIZON_MS = 14 * DAY_MS
        private const val STALE_MS = 6 * 3_600_000L
        private const val RATINGS_EVERY_MS = 3 * DAY_MS
    }
}
