package dev.diligent.app.papers

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.R
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.repository.DiligentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.w3c.dom.Element
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.random.Random

@Serializable
data class Paper(
    val id: String,
    val title: String,
    val authors: String,
    val abstract: String,
    val url: String,
    val categories: List<String>,
    val announceType: String
) {
    val pdfUrl: String get() = url.replace("/abs/", "/pdf/")
    val primaryCategory: String get() = categories.firstOrNull().orEmpty()
}

/** arXiv's daily RSS feed (rss.arxiv.org), the source arXiv recommends for "what's new today". */
object ArxivFeed {

    val CATEGORY_CHOICES = listOf(
        "quant-ph" to "Quantum physics",
        "q-fin.TR" to "Trading & microstructure",
        "q-fin.CP" to "Computational finance",
        "q-fin.ST" to "Statistical finance",
        "cs.DS" to "Data structures & algorithms",
        "cs.LG" to "Machine learning"
    )

    fun url(categories: List<String>) = "https://rss.arxiv.org/rss/" + categories.joinToString("+")

    fun fetch(categories: List<String>): String {
        val connection = URL(url(categories)).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "Diligent/1.0 (Android productivity app)")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException(if (code == 429) "arXiv is rate limiting — try later" else "arXiv returned HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Pure RSS parser (javax DOM, so it runs the same on Android and in unit tests). */
    fun parse(xml: String): List<Paper> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val items = doc.getElementsByTagName("item")
        return (0 until items.length).mapNotNull { i ->
            val item = items.item(i) as? Element ?: return@mapNotNull null
            fun child(name: String) = item.getElementsByTagName(name).let { if (it.length > 0) it.item(0).textContent.trim() else null }
            val link = child("link") ?: return@mapNotNull null
            val id = link.substringAfterLast("/abs/").ifBlank { return@mapNotNull null }
            val description = child("description").orEmpty()
            val categories = item.getElementsByTagName("category").let { list -> (0 until list.length).map { list.item(it).textContent.trim() } }
            Paper(
                id = id,
                title = child("title").orEmpty().replace(Regex("\\s+"), " "),
                authors = child("dc:creator").orEmpty().replace(Regex("\\s+"), " "),
                abstract = description.substringAfter("Abstract:", description).trim().replace(Regex("\\s+"), " "),
                url = link,
                categories = categories,
                announceType = child("arxiv:announce_type") ?: "new"
            )
        }
    }

    /**
     * Same pick all day: new submissions first, chosen with a seed from the date so reopening
     * the app doesn't reshuffle it.
     */
    fun pickOfDay(papers: List<Paper>, date: LocalDate, exclude: Set<String> = emptySet()): Paper? {
        val pool = papers.filter { it.id !in exclude }
        val fresh = pool.filter { it.announceType == "new" }.ifEmpty { pool }
        if (fresh.isEmpty()) return null
        return fresh[Random(date.toEpochDay()).nextInt(fresh.size)]
    }
}

@Serializable
data class PaperStore(
    val fetchedAt: Long = 0L,
    val papers: List<Paper> = emptyList(),
    val pickDate: String? = null,
    val pickId: String? = null,
    val queue: List<Paper> = emptyList(),
    val read: List<String> = emptyList()
)

data class PaperSettings(
    val categories: List<String> = listOf("quant-ph", "q-fin.TR"),
    val notify: Boolean = false,
    val hour: Int = 8,
    val minute: Int = 30,
    val creditMinutes: Int = 30,
    val activityId: Long = 0L
)

data class PaperState(val settings: PaperSettings, val store: PaperStore, val syncing: Boolean, val error: String?) {
    val today: Paper? get() = store.papers.firstOrNull { it.id == store.pickId } ?: store.queue.firstOrNull { it.id == store.pickId }
}

@Singleton
class PaperService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DiligentRepository
) {
    private val prefs = context.getSharedPreferences("papers_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(PaperState(readSettings(), readStore(), false, null))
    val state: StateFlow<PaperState> = _state

    fun current(): PaperState = _state.value

    fun updateSettings(transform: (PaperSettings) -> PaperSettings) {
        val next = transform(_state.value.settings)
        prefs.edit()
            .putString("categories", next.categories.joinToString(","))
            .putBoolean("notify", next.notify)
            .putInt("hour", next.hour)
            .putInt("minute", next.minute)
            .putInt("credit", next.creditMinutes)
            .putLong("activity", next.activityId)
            .apply()
        val categoriesChanged = next.categories != _state.value.settings.categories
        _state.value = _state.value.copy(settings = next)
        if (categoriesChanged) saveStore(_state.value.store.copy(fetchedAt = 0L, pickDate = null))
    }

    /** Fetches the feed if it wasn't fetched today (local day), then fixes today's pick. */
    suspend fun sync(force: Boolean = false): String? = mutex.withLock {
        val s = _state.value
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val fetchedToday = s.store.fetchedAt > 0 &&
            java.time.Instant.ofEpochMilli(s.store.fetchedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == today
        if (!force && fetchedToday && s.store.pickDate == today.toString()) return@withLock null
        if (s.settings.categories.isEmpty()) return@withLock "Pick at least one category"

        _state.value = s.copy(syncing = true)
        PaperWidgetUpdater.refresh(context)
        val error = try {
            val papers = withContext(Dispatchers.IO) { ArxivFeed.parse(ArxivFeed.fetch(s.settings.categories)) }
            // Keep the store small: new papers first, at most 60.
            val kept = (papers.filter { it.announceType == "new" } + papers.filter { it.announceType != "new" }).take(MAX_PAPERS)
            val store = _state.value.store.copy(fetchedAt = now, papers = kept)
            saveStore(withPick(store, today, force = !fetchedToday))
            null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Still pick from what's cached so the widget isn't empty.
            saveStore(withPick(_state.value.store, today, force = false))
            e.message ?: "Couldn't reach arXiv"
        }
        _state.value = _state.value.copy(syncing = false, error = error)
        PaperWidgetUpdater.refresh(context)
        error
    }

    private fun withPick(store: PaperStore, today: LocalDate, force: Boolean): PaperStore {
        if (!force && store.pickDate == today.toString() && store.pickId != null) return store
        val pick = ArxivFeed.pickOfDay(store.papers, today, exclude = store.read.toSet())
        return store.copy(pickDate = today.toString(), pickId = pick?.id ?: store.pickId)
    }

    fun save(paper: Paper) {
        val store = _state.value.store
        if (store.queue.any { it.id == paper.id }) return
        saveStore(store.copy(queue = store.queue + paper))
    }

    fun unsave(paper: Paper) {
        val store = _state.value.store
        saveStore(store.copy(queue = store.queue.filterNot { it.id == paper.id }))
    }

    /** Marks a paper read and credits reading time to the Research Papers activity. Returns the activity name. */
    suspend fun markRead(paper: Paper): String? {
        val store = _state.value.store
        saveStore(store.copy(queue = store.queue.filterNot { it.id == paper.id }, read = (store.read + paper.id).takeLast(500)))
        val activity = linkedActivity(repository.getAllActiveSnapshot()) ?: return null
        repository.incrementProgress(activity.id, credit(activity))
        return activity.name
    }

    fun credit(activity: Activity): Float {
        val minutes = _state.value.settings.creditMinutes
        return when (activity.unit.trim().lowercase()) {
            "hrs", "hr", "hours", "hour", "h" -> minutes / 60f
            "mins", "min", "minutes" -> minutes.toFloat()
            else -> 1f
        }
    }

    fun linkedActivity(active: List<Activity>): Activity? {
        val id = _state.value.settings.activityId
        return active.firstOrNull { it.id == id }
            ?: active.firstOrNull { it.name.contains("paper", true) || it.name.contains("research", true) || it.name.contains("reading", true) }
    }

    suspend fun runDaily() {
        sync()
        val s = _state.value
        val paper = s.today ?: return
        if (s.settings.notify) notify(paper)
    }

    fun nextRun(now: ZonedDateTime): ZonedDateTime? {
        val s = _state.value.settings
        if (!s.notify) return null
        var next = now.with(LocalTime.of(s.hour, s.minute)).withSecond(0).withNano(0)
        if (!next.isAfter(now.plusSeconds(30))) next = next.plusDays(1)
        return next
    }

    @SuppressLint("MissingPermission")
    private fun notify(paper: Paper) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Paper of the day", NotificationManager.IMPORTANCE_LOW).apply {
                description = "One arXiv paper each morning"
            }
        )
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(Intent.ACTION_VIEW, Uri.parse(paper.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Paper of the day · ${paper.primaryCategory}")
            .setContentText(paper.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${paper.title}\n${paper.authors}"))
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

    private fun saveStore(store: PaperStore) {
        prefs.edit().putString("store", json.encodeToString(PaperStore.serializer(), store)).apply()
        _state.value = _state.value.copy(store = store)
        PaperWidgetUpdater.refreshAsync(context)
    }

    private fun readSettings(): PaperSettings {
        val d = PaperSettings()
        return PaperSettings(
            categories = prefs.getString("categories", null)?.split(",")?.filter { it.isNotBlank() } ?: d.categories,
            notify = prefs.getBoolean("notify", d.notify),
            hour = prefs.getInt("hour", d.hour),
            minute = prefs.getInt("minute", d.minute),
            creditMinutes = prefs.getInt("credit", d.creditMinutes),
            activityId = prefs.getLong("activity", 0L)
        )
    }

    private fun readStore(): PaperStore = prefs.getString("store", null)?.let {
        runCatching { json.decodeFromString(PaperStore.serializer(), it) }.getOrNull()
    } ?: PaperStore()

    companion object {
        private const val CHANNEL = "paper_of_the_day"
        private const val NOTIFICATION_ID = 3801
        private const val MAX_PAPERS = 60
        val CREDIT_CHOICES = listOf(15, 30, 60)
    }
}
