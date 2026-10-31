package dev.diligent.app.automation

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.contests.ContestRadar
import dev.diligent.app.contests.Platform
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.data.repository.GithubContributionFetcher
import dev.diligent.app.quotapulse.data.SecretStore
import dev.diligent.app.screentime.ScreenTimeService
import dev.diligent.app.tracking.DayClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalTime
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What logs itself. Each source raises today's progress on the activity it's linked to and
 * never lowers it, the same rule as the LeetCode and Health auto-logs.
 *
 * @param externalCommands NFC tags, Tasker, shortcuts and geofences may run [ExternalCommand]s.
 * @param wakaActivityId Coding time from WakaTime (the API key is in [SecretStore]).
 * @param githubUser Your GitHub username: a day with contributions completes [githubActivityId].
 * @param appLinks Activity id → app packages whose foreground time counts towards it.
 * @param cpActivityId Problems accepted today on Codeforces + AtCoder (handles from Contest Radar).
 */
@Serializable
data class AutoLogSettings(
    val externalCommands: Boolean = false,
    val wakaActivityId: Long = 0L,
    val githubUser: String = "",
    val githubActivityId: Long = 0L,
    val appLinks: Map<Long, Set<String>> = emptyMap(),
    val cpActivityId: Long = 0L,
    val lastSyncAt: Long = 0L,
    /** Source → what the last sync found, or its error. */
    val lastResults: Map<String, String> = emptyMap()
)

class AutoLogPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("auto_log", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun current(): AutoLogSettings =
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString<AutoLogSettings>(it) }.getOrNull() }
            ?: AutoLogSettings()

    fun save(settings: AutoLogSettings) {
        prefs.edit().putString(KEY, json.encodeToString(settings)).apply()
    }

    private companion object {
        const val KEY = "settings"
    }
}

@Singleton
class AutoLogService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DiligentRepository,
    private val screenTime: ScreenTimeService,
    private val radar: ContestRadar,
    private val secrets: SecretStore
) {
    private val prefs = AutoLogPrefs(context)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(prefs.current())
    val state: StateFlow<AutoLogSettings> = _state.asStateFlow()

    fun update(change: (AutoLogSettings) -> AutoLogSettings) {
        val next = change(_state.value)
        prefs.save(next)
        _state.value = next
    }

    fun hasWakaKey(): Boolean = secrets.has(WAKA_KEY)

    fun setWakaKey(key: String?) = secrets.put(WAKA_KEY, key?.trim())

    /**
     * Reads every linked source and raises today's progress. [network] false runs only the
     * on-phone sources (app usage), for the offline evening job.
     */
    suspend fun sync(network: Boolean = true): AutoLogSettings = mutex.withLock {
        val settings = _state.value
        val results = settings.lastResults.toMutableMap()
        val activities = repository.getAllActiveSnapshot().associateBy { it.id }
        val now = System.currentTimeMillis()
        val dayStart = DayClock.startOf(DayClock.today())

        // App time → activities, and the day's distracting minutes for insights
        if (screenTime.hasAccess()) {
            val usage = withContext(Dispatchers.IO) { screenTime.appUsage(dayStart, now) }
            val links = settings.appLinks.filterKeys { it in activities }
            AutoLogSources.usagePerActivity(usage, links).forEach { (id, millis) ->
                val activity = activities.getValue(id)
                AutoLogSources.timeAmount(activity.unit, millis)?.let { repository.raiseProgressTo(activity, it) }
            }
            if (links.isNotEmpty()) results["apps"] = "${links.size} linked · read ${hhmm(now)}"
            val distracting = screenTime.settings.value.distracting
            val minutes = (usage.filterKeys { it in distracting }.values.sum() / 60_000).toInt()
            repository.updateDayLog { it.copy(distractingMinutes = minutes) }
        }

        if (network) {
            activities[settings.wakaActivityId]?.let { activity ->
                results["wakatime"] = attempt {
                    val key = secrets.get(WAKA_KEY) ?: throw IOException("Add your WakaTime API key")
                    val day = DayClock.todayString()
                    val auth = "Basic " + Base64.encodeToString(key.toByteArray(), Base64.NO_WRAP)
                    val body = get("https://wakatime.com/api/v1/users/current/summaries?start=$day&end=$day", mapOf("Authorization" to auth))
                    val seconds = AutoLogSources.wakaSeconds(body)
                    AutoLogSources.timeAmount(activity.unit, (seconds * 1000).toLong())?.let { repository.raiseProgressTo(activity, it) }
                    "${(seconds / 60).toInt()} min coded today"
                }
            }

            val github = settings.githubUser.trim()
            activities[settings.githubActivityId]?.takeIf { github.isNotEmpty() }?.let { activity ->
                results["github"] = attempt {
                    repository.syncGithubContributions(github)
                    val cached = repository.getGithubContributionSnapshot(github) ?: throw IOException("No graph for $github")
                    val today = DayClock.todayString()
                    val level = GithubContributionFetcher.decode(cached.contributionsJson).firstOrNull { it.date == today }?.level ?: 0
                    if (level > 0) {
                        repository.raiseProgressTo(activity, activity.dailyGoal)
                        "contributed today ✓"
                    } else "no contributions yet today"
                }
            }

            activities[settings.cpActivityId]?.let { activity ->
                results["cp"] = attempt {
                    val handles = radar.state.value.settings.handles
                    val from = dayStart / 1000
                    val to = now / 1000 + 60
                    val solved = HashSet<String>()
                    handles[Platform.CODEFORCES]?.trim()?.takeIf { it.isNotEmpty() }?.let { handle ->
                        val body = get("https://codeforces.com/api/user.status?handle=${enc(handle)}&from=1&count=100")
                        solved += AutoLogSources.codeforcesSolved(body, from, to)
                    }
                    handles[Platform.ATCODER]?.trim()?.takeIf { it.isNotEmpty() }?.let { handle ->
                        val body = get("https://kenkoooo.com/atcoder/atcoder-api/v3/user/submissions?user=${enc(handle)}&from_second=$from")
                        solved += AutoLogSources.atcoderSolved(body, from, to)
                    }
                    if (handles[Platform.CODEFORCES].isNullOrBlank() && handles[Platform.ATCODER].isNullOrBlank()) {
                        throw IOException("Add your Codeforces or AtCoder handle in Contest Radar")
                    }
                    if (solved.isNotEmpty()) repository.raiseProgressTo(activity, solved.size.toFloat())
                    "${solved.size} solved today"
                }
            }
        }

        val next = _state.value.copy(lastSyncAt = now, lastResults = results)
        prefs.save(next)
        _state.value = next
        next
    }

    /** The evening job: on-phone sources always, network ones when there's a connection. */
    suspend fun runEvening() {
        sync(network = true)
    }

    fun nextRun(now: ZonedDateTime): ZonedDateTime? {
        val s = _state.value
        val any = s.appLinks.isNotEmpty() || s.wakaActivityId != 0L || s.githubActivityId != 0L || s.cpActivityId != 0L
        if (!any) return null
        // Ten minutes before the missed-goal check, so auto-logged work counts.
        var next = now.with(LocalTime.of(20, 45)).withSecond(0).withNano(0)
        if (!next.isAfter(now.plusSeconds(30))) next = next.plusDays(1)
        return next
    }

    private suspend fun attempt(block: suspend () -> String): String = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "⚠ " + (e.message ?: "failed")
    }

    private suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "Diligent (Android)")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            val code = connection.responseCode
            if (code == 401 || code == 403) throw IOException("Not authorised (HTTP $code)")
            if (code !in 200..299) throw IOException("HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun hhmm(millis: Long) = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
        .toLocalTime().withSecond(0).withNano(0).toString()

    companion object {
        const val WAKA_KEY = "wakatime_api_key"
    }
}
