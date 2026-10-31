package dev.diligent.app.tracking

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Today" for tracking. The day starts at midnight unless Settings → Day starts at moves it
 * later (up to 6 AM), so a 1 AM session still counts for the evening it belongs to.
 *
 * A plain object rather than an injected class because widgets, receivers and workers all
 * need it; [init] runs in Application.onCreate before any of them.
 */
object DayClock {

    private const val PREFS = "day_clock"
    private const val KEY_START_MINUTES = "start_minutes"
    private const val KEY_FROM_SLEEP = "from_sleep"

    @Volatile
    private var startMinutes = 0

    @Volatile
    private var fromSleep = false

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        startMinutes = prefs.getInt(KEY_START_MINUTES, 0).coerceIn(0, DayBoundary.MAX_START_MINUTES)
        fromSleep = prefs.getBoolean(KEY_FROM_SLEEP, false)
    }

    fun startMinutes(): Int = startMinutes

    /** The day start follows Health Connect sleep (see HealthSync) instead of a fixed time. */
    fun followsSleep(): Boolean = fromSleep

    fun set(context: Context, minutes: Int, followSleep: Boolean = fromSleep) {
        startMinutes = minutes.coerceIn(0, DayBoundary.MAX_START_MINUTES)
        fromSleep = followSleep
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_START_MINUTES, startMinutes)
            .putBoolean(KEY_FROM_SLEEP, fromSleep)
            .apply()
    }

    fun today(): LocalDate = dateOf(System.currentTimeMillis())

    fun todayString(): String = today().format(DateTimeFormatter.ISO_LOCAL_DATE)

    fun dateOf(millis: Long): LocalDate = DayBoundary.logicalDate(millis, startMinutes, ZoneId.systemDefault())

    /** Epoch millis when the next tracking day begins. */
    fun nextDayStart(now: Long = System.currentTimeMillis()): Long =
        DayBoundary.nextStart(now, startMinutes, ZoneId.systemDefault())

    /** Epoch millis when [date]'s tracking day began. */
    fun startOf(date: LocalDate): Long = DayBoundary.startOf(date, startMinutes, ZoneId.systemDefault())

    /** Shift that makes midnight-based date maths land on tracking days. */
    fun offsetMillis(): Long = startMinutes * 60_000L
}
