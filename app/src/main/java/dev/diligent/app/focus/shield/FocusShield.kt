package dev.diligent.app.focus.shield

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import dev.diligent.app.data.local.entity.FocusRecord
import java.util.TimeZone

/**
 * Focus Shield settings and the system hooks around a focus session.
 *
 * - [Config.shieldDuringFocus]: opening an app marked distracting (Screen vs Focus) during a
 *   session shows the shield instead (Opal, Freedom). Opening it anyway breaks the session's
 *   stake (Forest) unless hard mode is on, where it isn't offered at all.
 * - [Config.mindfulPause]: outside sessions, a short pause with today's progress before a
 *   distracting app opens (one sec).
 * - [Config.hardMode]: sessions with a planned length can't be stopped early.
 * - [Config.dnd]: Do Not Disturb (priority only) while a session runs.
 *
 * Detecting the foreground app needs Diligent's accessibility service ([ShieldService]),
 * which only looks at which app's window opened, never at screen content.
 */
object FocusShield {

    data class Config(
        val shieldDuringFocus: Boolean = false,
        val mindfulPause: Boolean = false,
        val pauseSeconds: Int = 10,
        val hardMode: Boolean = false,
        val dnd: Boolean = false,
        /** Write finished sessions to this calendar (0 = off). */
        val calendarId: Long = 0L
    )

    private const val PREFS = "focus_shield"
    private const val ALLOW_PREFIX = "allow_"
    private const val KEY_DND_SET = "dnd_set_by_us"

    fun config(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            shieldDuringFocus = p.getBoolean("shield", false),
            mindfulPause = p.getBoolean("pause", false),
            pauseSeconds = p.getInt("pause_seconds", 10),
            hardMode = p.getBoolean("hard", false),
            dnd = p.getBoolean("dnd", false),
            calendarId = p.getLong("calendar", 0L)
        )
    }

    fun save(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("shield", config.shieldDuringFocus)
            .putBoolean("pause", config.mindfulPause)
            .putInt("pause_seconds", config.pauseSeconds.coerceIn(3, 60))
            .putBoolean("hard", config.hardMode)
            .putBoolean("dnd", config.dnd)
            .putLong("calendar", config.calendarId)
            .apply()
    }

    // ─── Accessibility service ──────────────────────────────────

    fun serviceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val ours = ComponentName(context, ShieldService::class.java)
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        return splitter.any { ComponentName.unflattenFromString(it) == ours }
    }

    fun accessibilitySettings(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ─── "Open anyway" allowances ───────────────────────────────

    /** Lets [pkg] open without the shield or pause until [until]. */
    fun allow(context: Context, pkg: String, until: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(ALLOW_PREFIX + pkg, until).apply()
    }

    fun isAllowed(context: Context, pkg: String, now: Long = System.currentTimeMillis()): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(ALLOW_PREFIX + pkg, 0L) > now

    // ─── Do Not Disturb ─────────────────────────────────────────

    fun dndAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    fun dndSettings(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Priority-only while focusing; only when DND was fully off, so we never undo the user's own. */
    fun dndOn(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.isNotificationPolicyAccessGranted) return
        if (manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DND_SET, true).apply()
    }

    /** Back to all notifications, if we turned DND on and it's still as we left it. */
    fun dndOff(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_DND_SET, false)) return
        prefs.edit().putBoolean(KEY_DND_SET, false).apply()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.isNotificationPolicyAccessGranted) return
        if (manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
    }
}

/**
 * Finished focus sessions as calendar events (Session, Toggl), so the calendar doubles as a
 * time log. Off until a calendar is picked in Focus; needs WRITE_CALENDAR.
 */
object FocusCalendar {

    data class WritableCalendar(val id: Long, val name: String, val account: String)

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun calendars(context: Context): List<WritableCalendar> {
        if (!hasPermission(context)) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        val out = mutableListOf<WritableCalendar>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(3) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                out += WritableCalendar(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty())
            }
        }
        return out
    }

    /** Adds the session to the chosen calendar. Never throws: the calendar is a bonus. */
    fun onFinished(context: Context, record: FocusRecord, activityName: String) {
        val calendarId = FocusShield.config(context).calendarId
        if (calendarId == 0L || !hasPermission(context)) return
        runCatching {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, "◆ $activityName · ${record.focusMinutes} min")
                put(CalendarContract.Events.DESCRIPTION, listOfNotNull(
                    record.intention.takeIf { it.isNotBlank() },
                    if (record.pomodoro) "Pomodoro" else null,
                    if (record.broken) "stake broken" else null,
                    "Logged by Diligent"
                ).joinToString(" · "))
                put(CalendarContract.Events.DTSTART, record.startedAt)
                put(CalendarContract.Events.DTEND, record.endedAt)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE)
            }
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        }
    }
}
