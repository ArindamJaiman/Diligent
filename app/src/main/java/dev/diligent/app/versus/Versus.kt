package dev.diligent.app.versus

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.R
import dev.diligent.app.leetcode.data.DifficultyCounts
import dev.diligent.app.leetcode.data.LeetCodeApi
import dev.diligent.app.leetcode.data.LeetCodePrefs
import dev.diligent.app.leetcode.data.LeetCodeSnapshot
import dev.diligent.app.leetcode.data.RecentAc
import dev.diligent.app.leetcode.domain.LeetCodeDay
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.tools.ToolRoutes
import dev.diligent.app.tools.openToolIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalTime
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** One request for you and up to [MAX_FRIENDS] friends, using GraphQL aliases (p0, r0, p1, r1, ...). */
object VersusApi {

    const val MAX_FRIENDS = 3
    private val json = Json { ignoreUnknownKeys = true }

    fun query(count: Int): String = buildString {
        append("query versus(")
        append((0 until count).joinToString(", ") { "\$u$it: String!" })
        append(") {\n")
        repeat(count) { i ->
            append("  p$i: matchedUser(username: \$u$i) { username submitStatsGlobal { acSubmissionNum { difficulty count } } userCalendar { submissionCalendar } }\n")
            append("  r$i: recentAcSubmissionList(username: \$u$i, limit: 20) { title titleSlug timestamp }\n")
        }
        append("}")
    }

    sealed interface Outcome {
        /** One entry per requested name, in order; null where LeetCode has no such user. */
        data class Success(val players: List<LeetCodeSnapshot?>) : Outcome
        data class Failed(val message: String) : Outcome
    }

    fun fetch(usernames: List<String>, now: Long): Outcome {
        val body = buildJsonObject {
            put("query", query(usernames.size))
            putJsonObject("variables") { usernames.forEachIndexed { i, name -> put("u$i", name) } }
        }.toString()
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("https://leetcode.com/graphql").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Referer", "https://leetcode.com")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Diligent")
            }
            connection.outputStream.use { it.write(body.toByteArray()) }
            when (val code = connection.responseCode) {
                in 200..299 -> parse(connection.inputStream.bufferedReader().use { it.readText() }, usernames.size, now)
                429 -> Outcome.Failed("Rate limited by LeetCode — try again later")
                else -> Outcome.Failed("LeetCode returned HTTP $code")
            }
        } catch (e: IOException) {
            Outcome.Failed("Couldn't reach leetcode.com")
        } finally {
            connection?.disconnect()
        }
    }

    fun parse(body: String, count: Int, now: Long): Outcome {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return Outcome.Failed("Unexpected response from LeetCode")
        val data = root["data"] as? JsonObject
            ?: return Outcome.Failed(
                ((root["errors"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message").str()
                    ?: "Unexpected response from LeetCode"
            )
        val players = (0 until count).map { i ->
            val user = data["p$i"] as? JsonObject ?: return@map null
            val counts = ((user["submitStatsGlobal"] as? JsonObject)?.get("acSubmissionNum") as? JsonArray).orEmpty()
                .mapNotNull { item ->
                    val o = item as? JsonObject ?: return@mapNotNull null
                    (o["difficulty"].str()?.lowercase() ?: return@mapNotNull null) to (o["count"].str()?.toIntOrNull() ?: 0)
                }.toMap()
            LeetCodeSnapshot(
                username = user["username"].str() ?: "",
                fetchedAt = now,
                solved = DifficultyCounts(counts["all"] ?: 0, counts["easy"] ?: 0, counts["medium"] ?: 0, counts["hard"] ?: 0),
                calendar = LeetCodeApi.parseCalendar((user["userCalendar"] as? JsonObject)?.get("submissionCalendar").str()),
                recentAc = (data["r$i"] as? JsonArray).orEmpty().mapNotNull { item ->
                    val o = item as? JsonObject ?: return@mapNotNull null
                    val slug = o["titleSlug"].str() ?: return@mapNotNull null
                    val ts = o["timestamp"].str()?.toLongOrNull() ?: return@mapNotNull null
                    RecentAc(o["title"].str() ?: slug, slug, ts)
                }.sortedByDescending { it.timestamp }
            )
        }
        return Outcome.Success(players)
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
}

data class VersusRow(
    val username: String,
    val isYou: Boolean,
    val found: Boolean,
    val solvedToday: Int,
    val activeToday: Boolean,
    val streak: Int,
    val total: Int,
    val weekSubmissions: Int
)

/** Pure leaderboard: today's solves first, then streak, then total solved. */
object VersusBoard {
    fun rows(you: String, players: List<Pair<String, LeetCodeSnapshot?>>, now: Long): List<VersusRow> {
        val today = LeetCodeDay.utcDay(now)
        val weekStart = today.minusDays(6).toString()
        return players.map { (name, snapshot) ->
            VersusRow(
                username = snapshot?.username?.ifBlank { null } ?: name,
                isYou = name.equals(you, ignoreCase = true),
                found = snapshot != null,
                solvedToday = snapshot?.let { LeetCodeDay.solvedToday(it, now) } ?: 0,
                activeToday = snapshot?.let { LeetCodeDay.activeToday(it, now) } ?: false,
                streak = snapshot?.let { LeetCodeDay.currentStreak(it, now) } ?: 0,
                total = snapshot?.solved?.all ?: 0,
                weekSubmissions = snapshot?.calendar?.filterKeys { it >= weekStart }?.values?.sum() ?: 0
            )
        }.sortedWith(
            compareByDescending<VersusRow> { it.found }
                .thenByDescending { it.solvedToday }
                .thenByDescending { it.streak }
                .thenByDescending { it.total }
        )
    }

    /** The friend furthest ahead of you today, if anyone is. */
    fun rivalAhead(rows: List<VersusRow>): VersusRow? {
        val you = rows.firstOrNull { it.isYou } ?: return null
        return rows.filter { !it.isYou && it.found && it.solvedToday > you.solvedToday }.maxByOrNull { it.solvedToday }
    }
}

@Serializable
data class VersusCache(val fetchedAt: Long, val names: List<String>, val players: List<LeetCodeSnapshot?>)

data class VersusState(
    val you: String,
    val friends: List<String>,
    val cache: VersusCache?,
    val error: String?,
    val syncing: Boolean,
    val nudge: Boolean,
    val nudgeHour: Int,
    val nudgeMinute: Int
)

@Singleton
class VersusSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val leetCodePrefs: LeetCodePrefs
) {
    private val prefs = context.getSharedPreferences("versus_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(read(syncing = false))
    val state: StateFlow<VersusState> = _state

    fun current(): VersusState = read(_state.value.syncing)

    fun setFriends(names: List<String>) {
        val clean = names.map { it.trim().removePrefix("@").substringAfterLast("leetcode.com/u/").trimEnd('/') }
            .filter { it.matches(Regex("^[A-Za-z0-9_.-]{1,40}$")) }
            .distinctBy { it.lowercase() }
            .take(VersusApi.MAX_FRIENDS)
        prefs.edit().putString("friends", clean.joinToString(",")).apply()
        publish()
    }

    fun setNudge(enabled: Boolean, hour: Int = _state.value.nudgeHour, minute: Int = _state.value.nudgeMinute) {
        prefs.edit().putBoolean("nudge", enabled).putInt("nudge_h", hour).putInt("nudge_m", minute).apply()
        publish()
    }

    suspend fun sync(force: Boolean = false): String? = mutex.withLock {
        val s = current()
        if (s.you.isBlank()) return@withLock "Set up LeetCode first"
        if (s.friends.isEmpty()) return@withLock "Add a friend to compare with"
        val names = listOf(s.you) + s.friends
        val now = System.currentTimeMillis()
        val cache = s.cache
        if (!force && cache != null && cache.names == names && now - cache.fetchedAt < 30_000) return@withLock null

        _state.value = s.copy(syncing = true)
        VersusWidgetUpdater.refresh(context)
        val outcome = withContext(Dispatchers.IO) { VersusApi.fetch(names, now) }
        val error = when (outcome) {
            is VersusApi.Outcome.Success -> {
                val fresh = VersusCache(System.currentTimeMillis(), names, outcome.players)
                prefs.edit()
                    .putString("cache", json.encodeToString(VersusCache.serializer(), fresh))
                    .remove("error")
                    .apply()
                null
            }
            is VersusApi.Outcome.Failed -> {
                prefs.edit().putString("error", outcome.message).apply()
                outcome.message
            }
        }
        _state.value = read(syncing = false)
        VersusWidgetUpdater.refresh(context)
        error
    }

    fun board(now: Long = System.currentTimeMillis()): List<VersusRow> {
        val s = current()
        val cache = s.cache ?: return emptyList()
        return VersusBoard.rows(s.you, cache.names.zip(cache.players), now)
    }

    /** Evening check: sync once, then nudge if a friend has solved more than you today. */
    suspend fun runEvening() {
        val s = current()
        if (!s.nudge || s.friends.isEmpty() || s.you.isBlank()) return
        sync(force = true)
        val now = System.currentTimeMillis()
        val rows = board(now)
        val rival = VersusBoard.rivalAhead(rows) ?: return
        val you = rows.firstOrNull { it.isYou } ?: return
        notifyRival(rival, you, now)
    }

    fun nextRun(now: ZonedDateTime): ZonedDateTime? {
        val s = current()
        if (!s.nudge || s.friends.isEmpty() || s.you.isBlank()) return null
        var next = now.with(LocalTime.of(s.nudgeHour, s.nudgeMinute)).withSecond(0).withNano(0)
        if (!next.isAfter(now.plusSeconds(30))) next = next.plusDays(1)
        return next
    }

    @SuppressLint("MissingPermission")
    private fun notifyRival(rival: VersusRow, you: VersusRow, now: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "LeetCode Versus", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Evening nudge when a friend is ahead of you today"
            }
        )
        val reset = LeetCodeDay.nextResetMillis(now)
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID, openToolIntent(context, ToolRoutes.VERSUS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Versus · ${rival.username} solved ${rival.solvedToday} today")
            .setContentText("You: ${you.solvedToday} · LeetCode day resets ${ToolFormat.clock(reset)} (${LeetCodeDay.formatLeft(reset - now)} left)")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setColor(0xFFFFA116.toInt())
            .setTimeoutAfter((reset - now).coerceAtLeast(60_000L))
            .build()
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return
        try {
            compat.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun publish() {
        _state.value = read(_state.value.syncing)
        VersusWidgetUpdater.refreshAsync(context)
    }

    private fun read(syncing: Boolean): VersusState {
        val cache = prefs.getString("cache", null)?.let {
            runCatching { json.decodeFromString(VersusCache.serializer(), it) }.getOrNull()
        }
        return VersusState(
            you = leetCodePrefs.current().config.username,
            friends = prefs.getString("friends", "").orEmpty().split(",").filter { it.isNotBlank() },
            cache = cache,
            error = prefs.getString("error", null),
            syncing = syncing,
            nudge = prefs.getBoolean("nudge", true),
            nudgeHour = prefs.getInt("nudge_h", 20),
            nudgeMinute = prefs.getInt("nudge_m", 0)
        )
    }

    companion object {
        private const val CHANNEL = "leetcode_versus"
        private const val NOTIFICATION_ID = 3501
    }
}
