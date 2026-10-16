package dev.diligent.app.leetcode.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class LeetCodeState(
    val config: LeetCodeConfig = LeetCodeConfig(),
    val snapshot: LeetCodeSnapshot? = null,
    val sync: LeetCodeSyncStatus = LeetCodeSyncStatus(),
    /** UTC day the user dismissed the streak guard for ("done for today"). */
    val mutedUtcDay: LocalDate? = null
)

/**
 * LeetCode settings, the cached snapshot and sync status. Kept out of Room: the snapshot
 * is a re-fetchable cache, so it needs no migration or backup.
 */
@Singleton
class LeetCodePrefs @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<LeetCodeState> = _state.asStateFlow()

    fun current(): LeetCodeState = _state.value

    fun updateConfig(transform: (LeetCodeConfig) -> LeetCodeConfig) {
        val next = transform(current().config)
        edit {
            putString(KEY_USERNAME, next.username.trim())
            putBoolean(KEY_DAILY, next.dailyAlert)
            putInt(KEY_DAILY_H, next.dailyHour)
            putInt(KEY_DAILY_M, next.dailyMinute)
            putBoolean(KEY_GUARD, next.streakGuard)
            putInt(KEY_GUARD_H, next.guardHour)
            putInt(KEY_GUARD_M, next.guardMinute)
            putBoolean(KEY_REPEAT, next.repeatGuard)
            putBoolean(KEY_DAILY_ONLY, next.guardDailyOnly)
            putBoolean(KEY_CONTEST, next.contestAlert)
            putInt(KEY_CONTEST_LEAD, next.contestLeadMin)
            putBoolean(KEY_AUTO_LOG, next.autoLog)
            putLong(KEY_AUTO_LOG_ID, next.autoLogActivityId)
        }
    }

    fun saveSnapshot(snapshot: LeetCodeSnapshot, now: Long) = edit {
        putString(KEY_SNAPSHOT, json.encodeToString(LeetCodeSnapshot.serializer(), snapshot))
        putLong(KEY_LAST_ATTEMPT, now)
        remove(KEY_LAST_ERROR)
    }

    fun clearSnapshot() = edit {
        remove(KEY_SNAPSHOT)
        remove(KEY_LAST_ATTEMPT)
        remove(KEY_LAST_ERROR)
        remove(KEY_SYNC_STARTED)
        remove(KEY_MUTED_DAY)
    }

    fun markSyncStarted(now: Long) = edit { putLong(KEY_SYNC_STARTED, now) }

    fun markSyncFailed(now: Long, message: String) = edit {
        putLong(KEY_LAST_ATTEMPT, now)
        putString(KEY_LAST_ERROR, message)
    }

    fun muteGuard(utcDay: LocalDate) = edit { putString(KEY_MUTED_DAY, utcDay.toString()) }

    private fun read(): LeetCodeState {
        val defaults = LeetCodeConfig()
        val config = LeetCodeConfig(
            username = prefs.getString(KEY_USERNAME, "") ?: "",
            dailyAlert = prefs.getBoolean(KEY_DAILY, defaults.dailyAlert),
            dailyHour = prefs.getInt(KEY_DAILY_H, defaults.dailyHour),
            dailyMinute = prefs.getInt(KEY_DAILY_M, defaults.dailyMinute),
            streakGuard = prefs.getBoolean(KEY_GUARD, defaults.streakGuard),
            guardHour = prefs.getInt(KEY_GUARD_H, defaults.guardHour),
            guardMinute = prefs.getInt(KEY_GUARD_M, defaults.guardMinute),
            repeatGuard = prefs.getBoolean(KEY_REPEAT, defaults.repeatGuard),
            guardDailyOnly = prefs.getBoolean(KEY_DAILY_ONLY, defaults.guardDailyOnly),
            contestAlert = prefs.getBoolean(KEY_CONTEST, defaults.contestAlert),
            contestLeadMin = prefs.getInt(KEY_CONTEST_LEAD, defaults.contestLeadMin),
            autoLog = prefs.getBoolean(KEY_AUTO_LOG, defaults.autoLog),
            autoLogActivityId = prefs.getLong(KEY_AUTO_LOG_ID, defaults.autoLogActivityId)
        )
        val snapshot = prefs.getString(KEY_SNAPSHOT, null)?.let {
            try {
                json.decodeFromString(LeetCodeSnapshot.serializer(), it)
            } catch (e: Exception) {
                null
            }
        }
        return LeetCodeState(
            config = config,
            snapshot = snapshot,
            sync = LeetCodeSyncStatus(
                syncStartedAt = prefs.getLong(KEY_SYNC_STARTED, 0L),
                lastAttemptAt = prefs.getLong(KEY_LAST_ATTEMPT, 0L),
                lastError = prefs.getString(KEY_LAST_ERROR, null)
            ),
            mutedUtcDay = prefs.getString(KEY_MUTED_DAY, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        )
    }

    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = read()
    }

    companion object {
        private const val PREFS_NAME = "leetcode_prefs"
        private const val KEY_USERNAME = "username"
        private const val KEY_DAILY = "daily_alert"
        private const val KEY_DAILY_H = "daily_hour"
        private const val KEY_DAILY_M = "daily_minute"
        private const val KEY_GUARD = "streak_guard"
        private const val KEY_GUARD_H = "guard_hour"
        private const val KEY_GUARD_M = "guard_minute"
        private const val KEY_REPEAT = "repeat_guard"
        private const val KEY_DAILY_ONLY = "guard_daily_only"
        private const val KEY_CONTEST = "contest_alert"
        private const val KEY_CONTEST_LEAD = "contest_lead_min"
        private const val KEY_AUTO_LOG = "auto_log"
        private const val KEY_AUTO_LOG_ID = "auto_log_activity_id"
        private const val KEY_SNAPSHOT = "snapshot_json"
        private const val KEY_SYNC_STARTED = "sync_started_at"
        private const val KEY_LAST_ATTEMPT = "last_attempt_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_MUTED_DAY = "muted_utc_day"
    }
}
