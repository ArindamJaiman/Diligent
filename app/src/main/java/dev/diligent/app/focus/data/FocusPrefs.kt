package dev.diligent.app.focus.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A running timer. Times are wall-clock epoch millis, so they mean the same thing after the
 * process is killed or the phone restarts. Name and unit are copied in so the notification,
 * tile and widget can draw it without a database read.
 */
data class FocusSession(
    val activityId: Long,
    val activityName: String,
    val unit: String,
    val startedAt: Long,
    val pomodoro: Boolean,
    /** Focus before this moment is already in DailyProgress (finished Pomodoro blocks). */
    val loggedUntil: Long = startedAt
)

data class FocusState(
    val session: FocusSession? = null,
    /** What the widget and tile start: the last activity timed, or the one picked on the widget. */
    val selectedActivityId: Long = 0L,
    val selectedActivityName: String = "",
    /** Mode the widget and tile start in (the app asks each time and remembers the answer). */
    val pomodoro: Boolean = false
)

/** The running session and widget/tile choices. Kept out of Room: nothing here needs a migration. */
@Singleton
class FocusPrefs @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<FocusState> = _state.asStateFlow()

    fun current(): FocusState = _state.value

    /** commit(), not apply(): a start tap must be on disk before the process can be killed. */
    fun save(state: FocusState) {
        prefs.edit().apply {
            val session = state.session
            if (session == null) {
                SESSION_KEYS.forEach { remove(it) }
            } else {
                putLong(KEY_ACTIVITY_ID, session.activityId)
                putString(KEY_ACTIVITY_NAME, session.activityName)
                putString(KEY_UNIT, session.unit)
                putLong(KEY_STARTED_AT, session.startedAt)
                putBoolean(KEY_SESSION_POMODORO, session.pomodoro)
                putLong(KEY_LOGGED_UNTIL, session.loggedUntil)
            }
            putLong(KEY_SELECTED_ID, state.selectedActivityId)
            putString(KEY_SELECTED_NAME, state.selectedActivityName)
            putBoolean(KEY_POMODORO, state.pomodoro)
        }.commit()
        _state.value = state
    }

    private fun read(): FocusState {
        val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
        val session = if (startedAt > 0L && prefs.contains(KEY_ACTIVITY_ID)) {
            FocusSession(
                activityId = prefs.getLong(KEY_ACTIVITY_ID, 0L),
                activityName = prefs.getString(KEY_ACTIVITY_NAME, null).orEmpty(),
                unit = prefs.getString(KEY_UNIT, null) ?: "hrs",
                startedAt = startedAt,
                pomodoro = prefs.getBoolean(KEY_SESSION_POMODORO, false),
                loggedUntil = prefs.getLong(KEY_LOGGED_UNTIL, startedAt)
            )
        } else {
            null
        }
        return FocusState(
            session = session,
            selectedActivityId = prefs.getLong(KEY_SELECTED_ID, 0L),
            selectedActivityName = prefs.getString(KEY_SELECTED_NAME, null).orEmpty(),
            pomodoro = prefs.getBoolean(KEY_POMODORO, false)
        )
    }

    private companion object {
        const val PREFS_NAME = "focus_timer"
        const val KEY_ACTIVITY_ID = "activity_id"
        const val KEY_ACTIVITY_NAME = "activity_name"
        const val KEY_UNIT = "unit"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_SESSION_POMODORO = "session_pomodoro"
        const val KEY_LOGGED_UNTIL = "logged_until"
        const val KEY_SELECTED_ID = "selected_id"
        const val KEY_SELECTED_NAME = "selected_name"
        const val KEY_POMODORO = "pomodoro"

        val SESSION_KEYS = listOf(
            KEY_ACTIVITY_ID, KEY_ACTIVITY_NAME, KEY_UNIT, KEY_STARTED_AT, KEY_SESSION_POMODORO, KEY_LOGGED_UNTIL
        )
    }
}
