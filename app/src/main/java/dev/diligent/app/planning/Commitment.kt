package dev.diligent.app.planning

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.tracking.Goals
import java.time.LocalDate

/**
 * Commitment mode, after Beeminder's "akrasia horizon": making a goal easier (a lower daily
 * goal, or a higher limit) only takes effect [DELAY_DAYS] days later, so a bad evening can't
 * talk you out of it. Making it harder applies at once. Optionally a partner is named, whom
 * the missed-goal notification offers to tell (the message is never sent without a tap).
 */
object Commitment {

    const val DELAY_DAYS = 7L

    private const val PREFS = "commitment"
    private const val KEY_ON = "on"
    private const val KEY_PARTNER = "partner"
    private const val KEY_PARTNER_CONTACT = "partner_contact"

    data class Config(val on: Boolean = false, val partner: String = "", val partnerContact: String = "")

    fun config(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            on = prefs.getBoolean(KEY_ON, false),
            partner = prefs.getString(KEY_PARTNER, null).orEmpty(),
            partnerContact = prefs.getString(KEY_PARTNER_CONTACT, null).orEmpty()
        )
    }

    fun save(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ON, config.on)
            .putString(KEY_PARTNER, config.partner.trim())
            .putString(KEY_PARTNER_CONTACT, config.partnerContact.trim())
            .apply()
    }

    /**
     * A draft message to the partner (SMS for a number, email otherwise), or null when commitment
     * mode is off or no partner is set. The user still has to press send.
     */
    fun tellIntent(config: Config, message: String): Intent? {
        if (!config.on || config.partner.isBlank() || config.partnerContact.isBlank()) return null
        val contact = config.partnerContact.trim()
        val intent = if ('@' in contact) {
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(contact))).apply {
                putExtra(Intent.EXTRA_SUBJECT, "Diligent check-in")
                putExtra(Intent.EXTRA_TEXT, message)
            }
        } else {
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(contact))).putExtra("sms_body", message)
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** True when going from [old] to [newGoal] makes the activity easier. */
    fun isEasier(old: Activity, newGoal: Float, newGoalType: String): Boolean = when {
        old.goalType != newGoalType -> false
        newGoalType == Goals.AT_LEAST -> newGoal < old.dailyGoal
        newGoalType == Goals.AT_MOST -> newGoal > old.dailyGoal
        else -> false
    }

    /**
     * The activity to save. In commitment mode an easier goal is parked in pendingGoal until
     * [today] + [DELAY_DAYS]; anything else (and a harder goal) applies now and clears a
     * pending change.
     */
    fun apply(old: Activity, edited: Activity, on: Boolean, today: LocalDate): Activity =
        if (on && isEasier(old, edited.dailyGoal, edited.goalType)) {
            edited.copy(
                dailyGoal = old.dailyGoal,
                pendingGoal = edited.dailyGoal,
                pendingGoalDate = today.plusDays(DELAY_DAYS).toString()
            )
        } else {
            edited.copy(pendingGoal = 0f, pendingGoalDate = "")
        }
}
