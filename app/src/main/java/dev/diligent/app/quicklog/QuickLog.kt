package dev.diligent.app.quicklog

import dev.diligent.app.data.local.entity.Activity
import java.time.LocalDate

/** What a typed or spoken line asks for. */
sealed interface QuickCommand {
    val activity: Activity

    /** Add [amount] (in the activity's unit) on [date]. */
    data class Add(override val activity: Activity, val amount: Float, val date: LocalDate) : QuickCommand

    /** Set the day's progress to [value]. */
    data class Set(override val activity: Activity, val value: Float, val date: LocalDate) : QuickCommand
    data class Done(override val activity: Activity, val date: LocalDate) : QuickCommand
    data class Skip(override val activity: Activity, val date: LocalDate) : QuickCommand

    /** Start the focus timer, optionally for a planned length. */
    data class Focus(override val activity: Activity, val minutes: Int, val pomodoro: Boolean) : QuickCommand
}

/**
 * Natural-language logging (Todoist, Things, Fantastical quick add), all on the phone:
 *
 *  "2h quantum" · "+3 lc" · "reading 30 min yesterday" · "quantum 1.5" · "set gym 1"
 *  "gym done" · "skip gym" · "focus quantum 50m" · "pomodoro hft" · "log 2 hours of reading"
 *
 * Durations are converted to the activity's unit (hrs or mins); a bare number is in the
 * activity's own unit. Activities match by name, prefix, initials ("hft") or letters in order
 * ("lc" → LeetCode).
 */
object QuickLog {

    private val durationRe = Regex("""(\d+(?:[.,]\d+)?)\s*(h|hr|hrs|hour|hours|m|min|mins|minute|minutes)\b""")
    private val numberRe = Regex("""[+]?(\d+(?:[.,]\d+)?)""")
    private val fillers = setOf("log", "add", "of", "for", "on", "to", "the", "a", "an", "i", "did", "do", "and", "please", "hey", "my")

    fun parse(input: String, activities: List<Activity>, today: LocalDate): QuickCommand? {
        var text = " " + input.trim().lowercase().replace('’', '\'') + " "
        if (text.isBlank() || activities.isEmpty()) return null

        val date = when {
            " yesterday " in text -> today.minusDays(1).also { text = text.replace(" yesterday ", " ") }
            " today " in text -> today.also { text = text.replace(" today ", " ") }
            else -> today
        }
        val focus = Regex("""\b(focus|start|timer|pomodoro)\b""").find(text)?.value
        val pomodoro = focus == "pomodoro"
        val verb = when {
            focus != null -> "focus"
            Regex("""\b(done|finished|complete|completed)\b""").containsMatchIn(text) -> "done"
            Regex("""\bskip(ped)?\b""").containsMatchIn(text) -> "skip"
            Regex("""\bset\b""").containsMatchIn(text) -> "set"
            else -> "add"
        }
        text = text.replace(Regex("""\b(focus|start|timer|pomodoro|done|finished|complete|completed|skip|skipped|set)\b"""), " ")

        // Duration in minutes, or a bare number in the activity's unit
        var minutes: Float? = null
        durationRe.find(text)?.let { m ->
            val value = m.groupValues[1].replace(',', '.').toFloat()
            minutes = if (m.groupValues[2].startsWith("h")) value * 60 else value
            text = text.removeRange(m.range)
        }
        var number: Float? = null
        if (minutes == null) {
            numberRe.find(text)?.let { m ->
                number = m.groupValues[1].replace(',', '.').toFloat()
                text = text.removeRange(m.range)
            }
        }

        val words = text.split(Regex("""[^a-z0-9']+""")).filter { it.isNotBlank() && it !in fillers }
        val activity = match(words.joinToString(" "), activities) ?: return null

        return when (verb) {
            "focus" -> QuickCommand.Focus(activity, minutes?.toInt() ?: 0, pomodoro)
            "done" -> QuickCommand.Done(activity, date)
            "skip" -> QuickCommand.Skip(activity, date)
            "set" -> amount(activity, minutes, number)?.let { QuickCommand.Set(activity, it, date) }
            else -> QuickCommand.Add(activity, amount(activity, minutes, number) ?: 1f, date)
        }
    }

    /** [minutes] in the activity's unit, else the bare [number]; null when neither applies. */
    private fun amount(activity: Activity, minutes: Float?, number: Float?): Float? = when {
        minutes != null && activity.unit == "hrs" -> minutes / 60f
        minutes != null && activity.unit == "mins" -> minutes
        minutes != null -> number ?: minutes
        else -> number
    }

    /** Best activity for [query]: exact, prefix, word prefix, initials, then letters in order. */
    fun match(query: String, activities: List<Activity>): Activity? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val compact = q.replace(" ", "")
        fun name(a: Activity) = a.name.lowercase()
        fun initials(a: Activity) = name(a).split(Regex("""[^a-z0-9]+""")).filter { it.isNotEmpty() }.joinToString("") { it.take(1) }
        return activities.firstOrNull { name(it) == q }
            ?: activities.firstOrNull { name(it).startsWith(q) }
            ?: activities.firstOrNull { a -> name(a).split(' ').any { it.startsWith(q) } }
            ?: activities.firstOrNull { initials(it) == compact }
            ?: activities.firstOrNull { name(it).replace(" ", "").startsWith(compact) }
            ?: activities.filter { subsequence(compact, name(it).replace(" ", "")) }.minByOrNull { it.name.length }
    }

    private fun subsequence(needle: String, hay: String): Boolean {
        var i = 0
        for (c in hay) if (i < needle.length && c == needle[i]) i++
        return i == needle.length && needle.isNotEmpty()
    }

    /** A short confirmation: "+2 hrs · Quantum Computing (yesterday)". */
    fun describe(command: QuickCommand, today: LocalDate): String {
        fun day(date: LocalDate) = if (date == today) "" else " (${if (date == today.minusDays(1)) "yesterday" else date.toString()})"
        fun amount(v: Float, unit: String) = (if (v % 1f == 0f) v.toInt().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')) + " $unit"
        return when (command) {
            is QuickCommand.Add -> "+${amount(command.amount, command.activity.unit)} · ${command.activity.name}${day(command.date)}"
            is QuickCommand.Set -> "${command.activity.name} = ${amount(command.value, command.activity.unit)}${day(command.date)}"
            is QuickCommand.Done -> "${command.activity.name} done${day(command.date)}"
            is QuickCommand.Skip -> "${command.activity.name} skipped${day(command.date)}"
            is QuickCommand.Focus -> (if (command.pomodoro) "Pomodoro" else "Focus") + " · ${command.activity.name}" +
                (if (command.minutes > 0) " for ${command.minutes} min" else "")
        }
    }
}
