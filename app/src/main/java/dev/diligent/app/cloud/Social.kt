package dev.diligent.app.cloud

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.focus.FocusTimer
import dev.diligent.app.focus.domain.FocusMath
import dev.diligent.app.tracking.DayClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** Someone in a study room (YPT, Forest "plant together"). */
data class RoomMember(
    val name: String,
    val activity: String?,
    val since: Long,
    val until: Long,
    val broken: Boolean,
    val updatedAt: Long
) {
    /** Seen in the last 10 minutes and timing something. */
    fun focusing(now: Long) = activity != null && now - updatedAt < 10 * 60_000
}

@Serializable
data class Challenge(
    val code: String,
    val title: String,
    /** Counts every activity whose name contains this (case-insensitive). */
    val keyword: String,
    val target: Double,
    val unit: String,
    val start: String,
    val end: String
)

data class Standing(val name: String, val total: Double)

@Serializable
private data class SocialData(val room: String = "", val challenges: List<Challenge> = emptyList())

/**
 * Study rooms and challenges over the user's Supabase project. A room or challenge code is
 * the shared secret: anyone with it can see the members, nobody else can (see docs/CLOUD.md).
 * Presence is pushed when the focus timer changes and while the Rooms screen is open; nothing
 * polls in the background.
 */
@Singleton
class SocialService @Inject constructor(
    @ApplicationContext context: Context,
    private val cloud: CloudService,
    private val repository: DiligentRepository,
    private val focusTimer: FocusTimer
) {
    private val prefs = context.getSharedPreferences("social", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _data = MutableStateFlow(read())
    val room: String get() = _data.value.room
    val challengesFlow: StateFlow<List<Challenge>> get() = _challenges
    private val _challenges = MutableStateFlow(_data.value.challenges)

    private val _roomState = MutableStateFlow(room)
    val roomFlow: StateFlow<String> = _roomState.asStateFlow()

    private fun name(): String = cloud.state.value.displayName.ifBlank { "Me" }

    /**
     * Your id in rooms and challenges. Derived from the owner id but not reversible, so other
     * members never learn the owner id that guards your synced rows.
     */
    private fun memberId(): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(("member:" + cloud.state.value.ownerId).toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(20)

    private fun db(code: String) = cloud.supabase(mapOf("x-diligent-room" to code))

    fun newCode(): String = (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[Random.nextInt(32)] }.joinToString("")

    // ─── Rooms ──────────────────────────────────────────────────

    suspend fun join(code: String) {
        save(_data.value.copy(room = code.trim().uppercase()))
        pushPresence()
    }

    suspend fun leave() = withContext(Dispatchers.IO) {
        val code = room
        if (code.isNotBlank()) runCatching { db(code)?.delete("diligent_presence", "room=eq.$code&member=eq.${memberId()}") }
        save(_data.value.copy(room = ""))
    }

    /** What you're doing now, for the room. Called on every focus start/stop. */
    suspend fun pushPresence() = withContext(Dispatchers.IO) {
        val code = room
        if (code.isBlank()) return@withContext
        val db = db(code) ?: return@withContext
        val session = focusTimer.current().session
        runCatching {
            db.upsert("diligent_presence", "room,member", buildJsonArray {
                add(buildJsonObject {
                    put("room", code)
                    put("member", memberId())
                    put("name", name())
                    put("activity", session?.activityName)
                    put("started_at", session?.startedAt ?: 0L)
                    put("until_at", session?.let { if (it.targetMinutes > 0) it.startedAt + it.targetMinutes * FocusMath.MINUTE else 0L } ?: 0L)
                    put("broken", session?.broken == true)
                    put("updated_at", System.currentTimeMillis())
                })
            })
        }
    }

    /** Fire-and-forget for callers that can't suspend (the focus timer). */
    fun pushPresenceAsync() {
        if (room.isNotBlank()) scope.launch { pushPresence() }
    }

    suspend fun members(): List<RoomMember> = withContext(Dispatchers.IO) {
        val code = room
        val db = db(code) ?: return@withContext emptyList()
        db.select("diligent_presence", "room=eq.$code&select=name,activity,started_at,until_at,broken,updated_at&order=updated_at.desc").map { e ->
            val o = e.jsonObject
            RoomMember(
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: "?",
                activity = o["activity"]?.jsonPrimitive?.contentOrNull,
                since = o["started_at"]?.jsonPrimitive?.longOrNull ?: 0L,
                until = o["until_at"]?.jsonPrimitive?.longOrNull ?: 0L,
                broken = o["broken"]?.jsonPrimitive?.booleanOrNull == true,
                updatedAt = o["updated_at"]?.jsonPrimitive?.longOrNull ?: 0L
            )
        }
    }

    // ─── Challenges ─────────────────────────────────────────────

    suspend fun create(title: String, keyword: String, target: Double, unit: String, days: Int): Challenge = withContext(Dispatchers.IO) {
        val today = DayClock.today()
        val challenge = Challenge(newCode(), title.trim(), keyword.trim(), target, unit, today.toString(), today.plusDays(days - 1L).toString())
        val db = db(challenge.code) ?: error("Set up Supabase in Hub → Cloud first")
        db.upsert("diligent_challenges", "code", buildJsonArray {
            add(buildJsonObject {
                put("code", challenge.code); put("title", challenge.title); put("keyword", challenge.keyword)
                put("target", challenge.target); put("unit", challenge.unit); put("start_date", challenge.start); put("end_date", challenge.end)
            })
        })
        save(_data.value.copy(challenges = _data.value.challenges + challenge))
        pushScore(challenge)
        challenge
    }

    suspend fun joinChallenge(code: String): Challenge? = withContext(Dispatchers.IO) {
        val c = code.trim().uppercase()
        val db = db(c) ?: error("Set up Supabase in Hub → Cloud first")
        val row = db.select("diligent_challenges", "code=eq.$c&select=*").firstOrNull()?.jsonObject ?: return@withContext null
        val challenge = Challenge(
            code = c,
            title = row["title"]?.jsonPrimitive?.contentOrNull ?: c,
            keyword = row["keyword"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            target = row["target"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            unit = row["unit"]?.jsonPrimitive?.contentOrNull ?: "hrs",
            start = row["start_date"]?.jsonPrimitive?.contentOrNull ?: DayClock.todayString(),
            end = row["end_date"]?.jsonPrimitive?.contentOrNull ?: DayClock.todayString()
        )
        save(_data.value.copy(challenges = _data.value.challenges.filterNot { it.code == c } + challenge))
        pushScore(challenge)
        challenge
    }

    fun leaveChallenge(code: String) = save(_data.value.copy(challenges = _data.value.challenges.filterNot { it.code == code }))

    /** Your total for [challenge]: every matching activity, inside its dates. */
    suspend fun myTotal(challenge: Challenge): Double {
        val matching = repository.getAllActivitiesSnapshot().filter { it.name.contains(challenge.keyword, ignoreCase = true) }.map { it.id }.toSet()
        val end = minOf(LocalDate.parse(challenge.end), DayClock.today()).toString()
        return repository.getAllProgressRangeSnapshot(challenge.start, end).filter { it.activityId in matching }.sumOf { it.progress.toDouble() }
    }

    suspend fun pushScore(challenge: Challenge) = withContext(Dispatchers.IO) {
        val db = db(challenge.code) ?: return@withContext
        val total = myTotal(challenge)
        db.upsert("diligent_scores", "code,member", buildJsonArray {
            add(buildJsonObject {
                put("code", challenge.code); put("member", memberId()); put("name", name())
                put("total", total); put("updated_at", System.currentTimeMillis())
            })
        })
    }

    suspend fun standings(challenge: Challenge): List<Standing> = withContext(Dispatchers.IO) {
        val db = db(challenge.code) ?: return@withContext emptyList()
        db.select("diligent_scores", "code=eq.${challenge.code}&select=name,total&order=total.desc").map { e ->
            val o = e.jsonObject
            Standing(o["name"]?.jsonPrimitive?.contentOrNull ?: "?", o["total"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
        }
    }

    /** Nightly / on sync: refresh your score in every challenge still running. */
    suspend fun pushAllScores() {
        val today = DayClock.todayString()
        _data.value.challenges.filter { it.end >= today || it.end >= DayClock.today().minusDays(1).toString() }.forEach { runCatching { pushScore(it) } }
    }

    private fun save(data: SocialData) {
        prefs.edit().putString("data", json.encodeToString(data)).apply()
        _data.value = data
        _challenges.value = data.challenges
        _roomState.value = data.room
    }

    private fun read(): SocialData = prefs.getString("data", null)?.let { runCatching { json.decodeFromString<SocialData>(it) }.getOrNull() } ?: SocialData()
}
