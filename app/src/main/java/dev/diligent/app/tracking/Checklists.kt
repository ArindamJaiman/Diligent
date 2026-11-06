package dev.diligent.app.tracking

import android.content.Context
import dev.diligent.app.data.local.entity.Activity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** Checklists in a backup: items by activity id, ticked indices by "activityId|date". */
@Serializable
data class ChecklistBackup(
    val items: Map<String, List<String>> = emptyMap(),
    val done: Map<String, List<Int>> = emptyMap()
)

/** One day of an activity's checklist. */
data class ChecklistDay(val items: List<String>, val done: Set<Int>) {
    val count: Int get() = done.count { it in items.indices }
}

/**
 * Sub-tasks inside an activity ("LeetCode: 1 easy, 1 medium, 1 hard"). Each item is worth an
 * equal share of the daily goal: ticking one logs its share, unticking takes it back, and the
 * last tick fills the goal exactly. Kept in SharedPreferences (and in backups), so adding
 * them needed no database change. Ticks older than [KEEP_DAYS] are dropped.
 */
object Checklists {
    const val MAX_ITEMS = 12
    const val KEEP_DAYS = 120L
    private const val PREFS = "checklists"
    private const val ITEMS = "items_"
    private const val DONE = "done_"
    private val list = ListSerializer(String.serializer())

    /** One item per line (commas and semicolons split too), trimmed, no repeats. */
    fun parse(text: String): List<String> =
        text.split('\n', ',', ';').map { it.trim().take(60) }.filter { it.isNotEmpty() }.distinct().take(MAX_ITEMS)

    /** The share of the daily goal one item is worth; 0 for limits, which have nothing to tick off. */
    fun step(activity: Activity, count: Int): Float =
        if (count <= 0 || activity.dailyGoal <= 0f || Goals.isLimit(activity)) 0f else activity.dailyGoal / count

    // ─── Storage ────────────────────────────────────────────────

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun items(context: Context, activityId: Long): List<String> =
        prefs(context).getString(ITEMS + activityId, null)
            ?.let { runCatching { Json.decodeFromString(list, it) }.getOrNull() }
            .orEmpty()

    fun setItems(context: Context, activityId: Long, items: List<String>) {
        val edit = prefs(context).edit()
        if (items.isEmpty()) edit.remove(ITEMS + activityId) else edit.putString(ITEMS + activityId, Json.encodeToString(list, items.take(MAX_ITEMS)))
        edit.apply()
    }

    fun done(context: Context, activityId: Long, date: String): Set<Int> =
        prefs(context).getString("$DONE${activityId}_$date", null)
            ?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet()
            .orEmpty()

    fun setDone(context: Context, activityId: Long, date: String, done: Set<Int>, today: LocalDate = DayClock.today()) {
        val p = prefs(context)
        val edit = p.edit()
        val key = "$DONE${activityId}_$date"
        if (done.isEmpty()) edit.remove(key) else edit.putString(key, done.sorted().joinToString(","))
        // Drop ticks from long ago.
        val oldest = today.minusDays(KEEP_DAYS).toString()
        p.all.keys.filter { it.startsWith(DONE) && it.substringAfterLast('_') < oldest }.forEach { edit.remove(it) }
        edit.apply()
    }

    fun day(context: Context, activityId: Long, date: String): ChecklistDay =
        ChecklistDay(items(context, activityId), done(context, activityId, date))

    fun export(context: Context): ChecklistBackup {
        val all = prefs(context).all
        val items = all.filterKeys { it.startsWith(ITEMS) }.mapNotNull { (k, v) ->
            val parsed = (v as? String)?.let { runCatching { Json.decodeFromString(list, it) }.getOrNull() } ?: return@mapNotNull null
            k.removePrefix(ITEMS) to parsed
        }.toMap()
        val done = all.filterKeys { it.startsWith(DONE) }.mapNotNull { (k, v) ->
            val rest = k.removePrefix(DONE)
            val id = rest.substringBefore('_')
            val date = rest.substringAfter('_')
            val ticks = (v as? String)?.split(',')?.mapNotNull { it.toIntOrNull() } ?: return@mapNotNull null
            "$id|$date" to ticks
        }.toMap()
        return ChecklistBackup(items, done)
    }

    /** Restores what a backup holds, over what's here (the backup's activity ids are kept on restore). */
    fun restore(context: Context, backup: ChecklistBackup) {
        val edit = prefs(context).edit()
        backup.items.forEach { (id, items) -> if (id.toLongOrNull() != null) edit.putString(ITEMS + id, Json.encodeToString(list, items.take(MAX_ITEMS))) }
        backup.done.forEach { (key, ticks) ->
            val id = key.substringBefore('|').toLongOrNull() ?: return@forEach
            val date = key.substringAfter('|')
            edit.putString("$DONE${id}_$date", ticks.sorted().joinToString(","))
        }
        edit.apply()
    }
}
