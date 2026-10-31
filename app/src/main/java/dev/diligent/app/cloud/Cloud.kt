package dev.diligent.app.cloud

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.quotapulse.data.SecretStore
import dev.diligent.app.tracking.DayClock
import dev.diligent.app.tracking.Goals
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where Diligent's data may go, all opt-in and all under the user's own accounts:
 * a backup folder (any Storage Access Framework folder: Google Drive, Dropbox, the phone), a
 * private GitHub repo, a Supabase project (sync, web dashboard, rooms, challenges, the laptop
 * companion), a webhook (Zapier, Make, Google Apps Script) and a Notion database.
 *
 * Secrets (tokens, keys) are in [SecretStore]; the rest is here.
 */
@Serializable
data class CloudSettings(
    val backupFolder: String = "",
    val githubRepo: String = "",
    val supabaseUrl: String = "",
    /** Random id that scopes your rows in Supabase; also the web dashboard's login. */
    val ownerId: String = "",
    val displayName: String = "",
    val webhookUrl: String = "",
    val notionDatabase: String = "",
    val lastBackupAt: Long = 0L,
    val lastSyncAt: Long = 0L,
    val lastPullAt: Long = 0L,
    val lastExportDate: String = "",
    val lastResults: Map<String, String> = emptyMap()
) {
    val supabaseReady: Boolean get() = supabaseUrl.isNotBlank() && ownerId.isNotBlank()
}

/** A tiny PostgREST client for Supabase: the anon key plus an owner/room header the row policies check. */
class Supabase(private val baseUrl: String, private val anonKey: String, private val headers: Map<String, String>) {

    private fun open(path: String, method: String): HttpURLConnection =
        (URL(baseUrl.trimEnd('/') + "/rest/v1/" + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("apikey", anonKey)
            setRequestProperty("Authorization", "Bearer $anonKey")
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    fun select(table: String, query: String): JsonArray {
        val c = open("$table?$query", "GET")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("Supabase HTTP $code: " + (c.errorStream?.bufferedReader()?.readText()?.take(160) ?: ""))
            return Json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonArray
        } finally {
            c.disconnect()
        }
    }

    /** Insert or update on [conflict] columns. */
    fun upsert(table: String, conflict: String, rows: JsonArray) {
        if (rows.isEmpty()) return
        val c = open("$table?on_conflict=$conflict", "POST")
        try {
            c.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
            c.doOutput = true
            c.outputStream.use { it.write(rows.toString().toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("Supabase HTTP $code: " + (c.errorStream?.bufferedReader()?.readText()?.take(160) ?: ""))
        } finally {
            c.disconnect()
        }
    }

    fun delete(table: String, query: String) {
        val c = open("$table?$query", "DELETE")
        try {
            c.responseCode
        } finally {
            c.disconnect()
        }
    }
}

@Singleton
class CloudService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DiligentRepository,
    private val secrets: SecretStore
) {
    private val prefs = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private val _state = MutableStateFlow(read())
    val state: StateFlow<CloudSettings> = _state.asStateFlow()

    fun update(change: (CloudSettings) -> CloudSettings) {
        val next = change(_state.value)
        prefs.edit().putString(KEY, json.encodeToString(next)).apply()
        _state.value = next
    }

    fun secret(name: String): String? = secrets.get(name)
    fun hasSecret(name: String): Boolean = secrets.has(name)
    fun setSecret(name: String, value: String?) = secrets.put(name, value?.trim())

    /** A Supabase client for your own rows, or null when it isn't set up. */
    fun supabase(extra: Map<String, String> = emptyMap()): Supabase? {
        val s = _state.value
        val key = secrets.get(SUPABASE_KEY) ?: return null
        if (!s.supabaseReady) return null
        return Supabase(s.supabaseUrl, key, mapOf("x-diligent-owner" to s.ownerId) + extra)
    }

    /** Backup, sync and export, each only if set up; one line per destination. */
    suspend fun runAll(): Map<String, String> = mutex.withLock {
        val results = _state.value.lastResults.toMutableMap()
        val s = _state.value
        if (s.backupFolder.isNotBlank()) results["folder"] = attempt { backupToFolder(Uri.parse(s.backupFolder)) }
        if (s.githubRepo.isNotBlank() && secrets.has(GITHUB_TOKEN)) results["github"] = attempt { backupToGithub(s.githubRepo) }
        if (supabase() != null) results["sync"] = attempt { sync() }
        val yesterday = DayClock.today().minusDays(1).toString()
        if (s.lastExportDate != yesterday && (s.webhookUrl.isNotBlank() || (s.notionDatabase.isNotBlank() && secrets.has(NOTION_TOKEN)))) {
            results["export"] = attempt { exportDay(yesterday) }
        }
        update { it.copy(lastResults = results, lastBackupAt = System.currentTimeMillis()) }
        results
    }

    private suspend fun attempt(block: suspend () -> String): String = try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "⚠ " + (e.message ?: "failed")
    }

    // ─── Backups ────────────────────────────────────────────────

    private suspend fun backupJson(): String = BackupData.json.encodeToString(BackupData.snapshot(repository))

    /** Seven rolling files, one per weekday (diligent-backup-mon.json …), in the chosen folder. */
    private suspend fun backupToFolder(tree: Uri): String {
        val name = "diligent-backup-${DayClock.today().dayOfWeek.name.take(3).lowercase()}.json"
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var existing: Uri? = null
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) existing = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        val target = existing ?: DocumentsContract.createDocument(resolver, parent, "application/json", name)
            ?: throw IOException("Couldn't create $name")
        resolver.openOutputStream(target, "wt")?.use { it.write(backupJson().toByteArray()) } ?: throw IOException("Couldn't write $name")
        return "saved $name"
    }

    /** One file in a private repo, replaced each night (the repo's history keeps the old ones). */
    private suspend fun backupToGithub(repo: String): String {
        val token = secrets.get(GITHUB_TOKEN) ?: throw IOException("Add a GitHub token")
        val url = "https://api.github.com/repos/${repo.trim()}/contents/diligent-backup.json"
        fun request(method: String) = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        val get = request("GET")
        val sha = try {
            if (get.responseCode == 200) Json.parseToJsonElement(get.inputStream.bufferedReader().readText()).jsonObject["sha"]?.jsonPrimitive?.contentOrNull else null
        } finally {
            get.disconnect()
        }
        val body = buildJsonObject {
            put("message", "Diligent backup ${DayClock.todayString()}")
            put("content", Base64.encodeToString(backupJson().toByteArray(), Base64.NO_WRAP))
            sha?.let { put("sha", it) }
        }
        val put = request("PUT")
        try {
            put.doOutput = true
            put.setRequestProperty("Content-Type", "application/json")
            put.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = put.responseCode
            if (code !in 200..299) throw IOException("GitHub HTTP $code")
        } finally {
            put.disconnect()
        }
        return "pushed to $repo"
    }

    // ─── Sync ───────────────────────────────────────────────────

    /**
     * Pushes the last 35 days and pulls what other devices changed since the last pull. Rows
     * are matched by activity name; the newer edit wins. Also pulls laptop minutes.
     */
    suspend fun sync(): String {
        val db = supabase() ?: throw IOException("Set up Supabase first")
        val owner = _state.value.ownerId
        val activities = repository.getAllActiveSnapshot()
        val byId = activities.associateBy { it.id }
        val byName = activities.associateBy { it.name.lowercase() }
        val from = DayClock.today().minusDays(35).toString()
        val rows = repository.getAllProgressRangeSnapshot(from, DayClock.todayString())
        db.upsert("diligent_days", "owner,date,activity", buildJsonArray {
            rows.forEach { row ->
                val a = byId[row.activityId] ?: return@forEach
                add(buildJsonObject {
                    put("owner", owner)
                    put("date", row.date)
                    put("activity", a.name)
                    put("unit", a.unit)
                    put("progress", row.progress.toDouble())
                    put("goal", a.dailyGoal.toDouble())
                    put("completed", row.isCompleted)
                    put("updated_at", row.lastUpdated)
                })
            }
        })

        val since = _state.value.lastPullAt
        var pulled = 0
        db.select("diligent_days", "owner=eq.$owner&updated_at=gt.$since&select=date,activity,progress,updated_at").forEach { element ->
            val o = element.jsonObject
            val activity = byName[o["activity"]?.jsonPrimitive?.contentOrNull?.lowercase()] ?: return@forEach
            val date = o["date"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val updated = o["updated_at"]?.jsonPrimitive?.longOrNull ?: return@forEach
            val progress = o["progress"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: return@forEach
            val local = repository.getProgressOn(activity.id, date)
            if (local == null || local.lastUpdated < updated && local.progress != progress) {
                repository.setProgress(activity.id, date, progress)
                pulled++
            }
        }

        // Laptop companion: minutes per activity name for today
        val today = DayClock.todayString()
        var laptop = 0
        db.select("diligent_desktop", "owner=eq.$owner&date=eq.$today&select=activity,minutes").forEach { element ->
            val o = element.jsonObject
            val activity = byName[o["activity"]?.jsonPrimitive?.contentOrNull?.lowercase()] ?: return@forEach
            if (Goals.isLimit(activity)) return@forEach
            val minutes = o["minutes"]?.jsonPrimitive?.doubleOrNull ?: return@forEach
            val amount = when (activity.unit) {
                "hrs" -> (minutes / 60).toFloat()
                "mins" -> minutes.toFloat()
                else -> return@forEach
            }
            repository.raiseProgressTo(activity, amount)
            laptop++
        }
        val now = System.currentTimeMillis()
        update { it.copy(lastSyncAt = now, lastPullAt = now) }
        return "${rows.size} rows up · $pulled changed here" + if (laptop > 0) " · $laptop from laptop" else ""
    }

    // ─── Nightly export ─────────────────────────────────────────

    /** Yesterday as one JSON object (webhook) and one Notion row. */
    private suspend fun exportDay(date: String): String {
        val activities = repository.getAllActiveSnapshot()
        val rows: Map<Long, DailyProgress> = repository.getAllProgressRangeSnapshot(date, date).associateBy { it.activityId }
        val log = repository.getDayLog(date)
        val done = activities.count { rows[it.id]?.isCompleted == true }
        val hours = activities.filterNot { Goals.isLimit(it) }.sumOf { a ->
            val p = rows[a.id]?.progress?.toDouble() ?: 0.0
            when (a.unit) { "hrs" -> p; "mins" -> p / 60; else -> 0.0 }
        }
        val payload = buildJsonObject {
            put("date", date)
            put("done", done)
            put("total", activities.size)
            put("hours", (hours * 100).toInt() / 100.0)
            if (log.mood > 0) put("mood", log.mood)
            if (log.energy > 0) put("energy", log.energy)
            log.sleepMinutes?.let { put("sleep_minutes", it) }
            if (log.note.isNotBlank()) put("note", log.note)
            putJsonArray("activities") {
                activities.forEach { a ->
                    add(buildJsonObject {
                        put("name", a.name)
                        put("unit", a.unit)
                        put("progress", (rows[a.id]?.progress ?: 0f).toDouble())
                        put("goal", a.dailyGoal.toDouble())
                        put("done", rows[a.id]?.isCompleted == true)
                    })
                }
            }
        }
        val parts = mutableListOf<String>()
        val s = _state.value
        if (s.webhookUrl.isNotBlank()) {
            post(s.webhookUrl, payload, emptyMap())
            parts += "webhook"
        }
        val notion = secrets.get(NOTION_TOKEN)
        if (s.notionDatabase.isNotBlank() && notion != null) {
            post(
                "https://api.notion.com/v1/pages",
                buildJsonObject {
                    putJsonObject("parent") { put("database_id", s.notionDatabase.trim()) }
                    putJsonObject("properties") {
                        putJsonObject("Name") { putJsonArray("title") { add(buildJsonObject { putJsonObject("text") { put("content", date) } }) } }
                        putJsonObject("Date") { putJsonObject("date") { put("start", date) } }
                        putJsonObject("Done") { put("number", done) }
                        putJsonObject("Hours") { put("number", (hours * 100).toInt() / 100.0) }
                        putJsonObject("Note") { putJsonArray("rich_text") { add(buildJsonObject { putJsonObject("text") { put("content", log.note.take(1900)) } }) } }
                    }
                },
                mapOf("Authorization" to "Bearer $notion", "Notion-Version" to "2022-06-28")
            )
            parts += "Notion"
        }
        update { it.copy(lastExportDate = date) }
        return "$date → " + parts.joinToString(" + ")
    }

    private fun post(url: String, body: JsonObject, headers: Map<String, String>) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}")
        } finally {
            c.disconnect()
        }
    }

    fun nextRun(now: ZonedDateTime): ZonedDateTime? {
        val s = _state.value
        val any = s.backupFolder.isNotBlank() || s.githubRepo.isNotBlank() || s.supabaseReady || s.webhookUrl.isNotBlank() || s.notionDatabase.isNotBlank()
        if (!any) return null
        var next = now.with(LocalTime.of(23, 40)).withSecond(0).withNano(0)
        if (!next.isAfter(now.plusSeconds(30))) next = next.plusDays(1)
        return next
    }

    private fun read(): CloudSettings {
        val stored = prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString<CloudSettings>(it) }.getOrNull() } ?: CloudSettings()
        if (stored.ownerId.isNotBlank()) return stored
        // A random owner id on first use; it's the "account" for Supabase rows and the web dashboard.
        val withId = stored.copy(ownerId = UUID.randomUUID().toString())
        prefs.edit().putString(KEY, json.encodeToString(withId)).apply()
        return withId
    }

    companion object {
        private const val KEY = "settings"
        const val SUPABASE_KEY = "supabase_anon_key"
        const val GITHUB_TOKEN = "github_backup_token"
        const val NOTION_TOKEN = "notion_token"
    }
}
