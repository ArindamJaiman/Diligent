package dev.diligent.app.automation

import android.net.Uri
import dev.diligent.app.data.local.entity.Activity as Tracked

/**
 * Something another surface asks Diligent to do: an NFC tag, a Tasker task, a launcher
 * shortcut, a geofence. Every surface goes through [ExternalCommand.parse], so they all accept
 * the same words:
 *
 *  - diligent://log?activity=LeetCode&amount=1       add to today's progress
 *  - diligent://set?activity=Reading&value=1.5       set today's progress
 *  - diligent://done?activity=Gym                    mark today done
 *  - diligent://skip?activity=Gym                    skip today
 *  - diligent://focus?activity=Quantum&mode=toggle   start / stop / toggle the focus timer
 *
 * `activity` is an id or a name (case doesn't matter). Broadcasts use the same keys as extras
 * with action dev.diligent.app.action.COMMAND and extra "command" = log|set|done|skip|focus.
 */
sealed interface ExternalCommand {
    val activity: String

    data class Log(override val activity: String, val amount: Float) : ExternalCommand
    data class Set(override val activity: String, val value: Float) : ExternalCommand
    data class Done(override val activity: String) : ExternalCommand
    data class Skip(override val activity: String) : ExternalCommand
    data class Focus(override val activity: String, val mode: String, val pomodoro: Boolean) : ExternalCommand

    companion object {
        const val SCHEME = "diligent"
        const val ACTION = "dev.diligent.app.action.COMMAND"
        val VERBS = listOf("log", "set", "done", "skip", "focus")

        /** [verb] is log|set|done|skip|focus; [params] are the query parameters or extras. */
        fun parse(verb: String?, params: Map<String, String>): ExternalCommand? {
            val activity = params["activity"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            fun number(key: String) = params[key]?.replace(',', '.')?.toFloatOrNull()
            return when (verb?.lowercase()) {
                "log" -> Log(activity, number("amount") ?: 1f)
                "set" -> number("value")?.takeIf { it >= 0f }?.let { Set(activity, it) }
                "done" -> Done(activity)
                "skip" -> Skip(activity)
                "focus" -> Focus(
                    activity,
                    mode = params["mode"]?.lowercase()?.takeIf { it in setOf("start", "stop", "toggle") } ?: "toggle",
                    pomodoro = params["pomodoro"]?.lowercase() in setOf("1", "true", "yes")
                )
                else -> null
            }
        }

        /** The diligent:// link for a command (written to NFC tags, shown for Tasker). */
        fun uri(verb: String, activityId: Long, extra: Map<String, String> = emptyMap()): String =
            "$SCHEME://$verb?activity=$activityId" + extra.entries.joinToString("") { "&${it.key}=${Uri.encode(it.value)}" }

        /** Finds the activity an id or a name points at. */
        fun resolve(activities: List<Tracked>, key: String): Tracked? =
            key.toLongOrNull()?.let { id -> activities.firstOrNull { it.id == id } }
                ?: activities.firstOrNull { it.name.equals(key, ignoreCase = true) }
                ?: activities.firstOrNull { it.name.startsWith(key, ignoreCase = true) }
    }
}
