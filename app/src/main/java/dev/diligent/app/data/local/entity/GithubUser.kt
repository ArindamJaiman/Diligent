package dev.diligent.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A developer whose contribution graph the GitHub widgets show, in the user's order.
 * The first two are compared in the Versus widget. Usernames are unique ignoring case,
 * as they are on GitHub.
 *
 * @param color one of [GithubUsers.COLORS] (see GithubTheme)
 * @param position 0-based order in the list
 */
@Serializable
@Entity(tableName = "github_users")
data class GithubUser(
    @PrimaryKey
    @ColumnInfo(collate = ColumnInfo.NOCASE)
    val username: String,
    val color: String,
    val position: Int
)

/** Pure edits of the developer list, so every rule is unit tested. */
object GithubUsers {

    /** Each sync is one request per developer; this keeps a tap to a few seconds of network. */
    const val MAX_USERS = 50

    val COLORS = listOf("emerald", "crimson", "blue", "gold", "purple")

    /** What a fresh install starts with (the three slots older versions shipped with). */
    val DEFAULTS: List<GithubUser> = listOf(
        GithubUser("LennyDany-03", "emerald", 0),
        GithubUser("Quadr1on", "crimson", 1),
        GithubUser("SidhanthBibi", "blue", 2)
    )

    /** GitHub usernames: letters, digits and hyphens, at most 39, not starting with a hyphen. */
    private val USERNAME = Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,38}$")

    sealed interface Edit {
        data class Done(val users: List<GithubUser>, val message: String? = null) : Edit
        data class Rejected(val message: String) : Edit
    }

    /** "octocat", "@octocat" or a github.com/octocat link → "octocat"; null if it can't be a username. */
    fun normalize(raw: String): String? {
        val name = raw.trim()
            .substringAfter("github.com/")
            .removePrefix("@")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        return name.takeIf { USERNAME.matches(it) }
    }

    /** Sorted by position, renumbered 0..n-1. */
    fun ordered(users: List<GithubUser>): List<GithubUser> =
        users.sortedBy { it.position }.mapIndexed { index, user -> user.copy(position = index) }

    fun add(users: List<GithubUser>, raw: String): Edit {
        val name = normalize(raw) ?: return Edit.Rejected("That doesn't look like a GitHub username")
        users.firstOrNull { it.username.equals(name, ignoreCase = true) }
            ?.let { return Edit.Rejected("${it.username} is already on the list") }
        if (users.size >= MAX_USERS) return Edit.Rejected("Up to $MAX_USERS developers")
        val list = ordered(users)
        return Edit.Done(list + GithubUser(name, nextColor(list), list.size), "Added $name")
    }

    fun rename(users: List<GithubUser>, username: String, raw: String): Edit {
        val name = normalize(raw) ?: return Edit.Rejected("That doesn't look like a GitHub username")
        val list = ordered(users)
        val index = list.indexOfFirst { it.username == username }
        if (index < 0) return Edit.Rejected("$username is no longer on the list")
        list.filterIndexed { i, _ -> i != index }.firstOrNull { it.username.equals(name, ignoreCase = true) }
            ?.let { return Edit.Rejected("${it.username} is already on the list") }
        return Edit.Done(list.toMutableList().apply { this[index] = this[index].copy(username = name) }, "Saved $name")
    }

    fun remove(users: List<GithubUser>, username: String): Edit =
        Edit.Done(ordered(users.filterNot { it.username == username }), "Removed $username")

    /** Moves a developer [delta] places (negative = up); clamped at both ends. */
    fun move(users: List<GithubUser>, username: String, delta: Int): Edit {
        val list = ordered(users).toMutableList()
        val from = list.indexOfFirst { it.username == username }
        if (from < 0) return Edit.Done(list)
        val to = (from + delta).coerceIn(0, list.lastIndex)
        list.add(to, list.removeAt(from))
        return Edit.Done(list.mapIndexed { index, user -> user.copy(position = index) })
    }

    fun recolor(users: List<GithubUser>, username: String, color: String): Edit {
        if (color !in COLORS) return Edit.Rejected("Unknown colour $color")
        return Edit.Done(ordered(users).map { if (it.username == username) it.copy(color = color) else it })
    }

    /** The least-used colour, so a long list stays varied; ties go to palette order. */
    fun nextColor(users: List<GithubUser>): String {
        val counts = users.groupingBy { it.color }.eachCount()
        return COLORS.minBy { counts[it] ?: 0 }
    }

    /**
     * The developers in a backup made before the list existed, when settings had three
     * fixed slots (githubUser1..3 + colours). Null when the backup has no such fields.
     */
    fun fromLegacySettings(settings: JsonObject?): List<GithubUser>? {
        if (settings == null || (1..3).none { "githubUser$it" in settings }) return null
        val users = mutableListOf<GithubUser>()
        for (slot in 1..3) {
            val name = (settings["githubUser$slot"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (name.isBlank() || users.any { it.username.equals(name, ignoreCase = true) }) continue
            val color = (settings["githubUser${slot}Color"] as? JsonPrimitive)?.content?.takeIf { it in COLORS }
                ?: nextColor(users)
            users += GithubUser(name, color, users.size)
        }
        return users
    }
}
