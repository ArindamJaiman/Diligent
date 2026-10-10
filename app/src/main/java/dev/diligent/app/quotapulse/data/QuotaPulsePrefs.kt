package dev.diligent.app.quotapulse.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Requested monitoring mode. MANUAL is the default after install, reboot, and app restart. */
enum class LiveMode { MANUAL, LIVE, INTERRUPTED }

/**
 * Small, non-secret runtime state that the widget reads on every render.
 */
data class QuotaPulseRuntime(
    val liveMode: LiveMode = LiveMode.MANUAL,
    val liveIntervalSec: Int = DEFAULT_INTERVAL_SEC,
    val liveStartedAt: Long = 0L,
    val interruptReason: String? = null,
    val powerSaveSlowdown: Boolean = false,
    val syncStartedAt: Long = 0L,
    val lastSyncAt: Long = 0L,
    val lastSyncSummary: String? = null,
    val connectorHost: String? = null
) {
    /** A sync counts as running only for a bounded time, so a killed process can't leave it "stuck". */
    fun isSyncing(now: Long): Boolean = syncStartedAt > lastSyncAt && now - syncStartedAt < SYNC_TIMEOUT_MS

    companion object {
        const val DEFAULT_INTERVAL_SEC = 120
        val INTERVAL_CHOICES = listOf(60, 90, 120, 300)
        const val SYNC_TIMEOUT_MS = 90_000L
    }
}

@Singleton
class QuotaPulsePrefs @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _runtime = MutableStateFlow(read())
    val runtime: StateFlow<QuotaPulseRuntime> = _runtime.asStateFlow()

    // Held strongly: SharedPreferences only keeps weak references to listeners.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _runtime.value = read()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun current(): QuotaPulseRuntime = read()

    fun setLiveInterval(seconds: Int) = edit { putInt(KEY_INTERVAL, seconds) }

    fun markLiveStarted(now: Long) = edit {
        putString(KEY_LIVE_MODE, LiveMode.LIVE.name)
        putLong(KEY_LIVE_STARTED, now)
        remove(KEY_INTERRUPT_REASON)
        putBoolean(KEY_POWER_SAVE, false)
    }

    fun markManual() = edit {
        putString(KEY_LIVE_MODE, LiveMode.MANUAL.name)
        remove(KEY_INTERRUPT_REASON)
        putBoolean(KEY_POWER_SAVE, false)
    }

    fun markInterrupted(reason: String) = edit {
        putString(KEY_LIVE_MODE, LiveMode.INTERRUPTED.name)
        putString(KEY_INTERRUPT_REASON, reason)
        putBoolean(KEY_POWER_SAVE, false)
    }

    fun setPowerSaveSlowdown(active: Boolean) {
        if (prefs.getBoolean(KEY_POWER_SAVE, false) != active) edit { putBoolean(KEY_POWER_SAVE, active) }
    }

    fun markSyncStarted(now: Long) = edit { putLong(KEY_SYNC_STARTED, now) }

    fun markSyncFinished(now: Long, summary: String) = edit {
        putLong(KEY_LAST_SYNC, now)
        putString(KEY_LAST_SUMMARY, summary)
    }

    var connectorEtag: String?
        get() = prefs.getString(KEY_CONNECTOR_ETAG, null)
        set(value) = edit { if (value == null) remove(KEY_CONNECTOR_ETAG) else putString(KEY_CONNECTOR_ETAG, value) }

    fun setConnectorHost(host: String?) = edit {
        if (host == null) remove(KEY_CONNECTOR_HOST) else putString(KEY_CONNECTOR_HOST, host)
    }

    fun clearSyncHistory() = edit {
        remove(KEY_LAST_SYNC)
        remove(KEY_LAST_SUMMARY)
        remove(KEY_SYNC_STARTED)
        remove(KEY_CONNECTOR_ETAG)
    }

    private fun read() = QuotaPulseRuntime(
        liveMode = LiveMode.entries.firstOrNull { it.name == prefs.getString(KEY_LIVE_MODE, null) } ?: LiveMode.MANUAL,
        liveIntervalSec = prefs.getInt(KEY_INTERVAL, QuotaPulseRuntime.DEFAULT_INTERVAL_SEC),
        liveStartedAt = prefs.getLong(KEY_LIVE_STARTED, 0L),
        interruptReason = prefs.getString(KEY_INTERRUPT_REASON, null),
        powerSaveSlowdown = prefs.getBoolean(KEY_POWER_SAVE, false),
        syncStartedAt = prefs.getLong(KEY_SYNC_STARTED, 0L),
        lastSyncAt = prefs.getLong(KEY_LAST_SYNC, 0L),
        lastSyncSummary = prefs.getString(KEY_LAST_SUMMARY, null),
        connectorHost = prefs.getString(KEY_CONNECTOR_HOST, null)
    )

    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        // apply() updates the in-memory map immediately; the disk write happens off-thread.
        prefs.edit().apply(block).apply()
        _runtime.value = read()
    }

    companion object {
        private const val PREFS_NAME = "quotapulse_prefs"
        private const val KEY_LIVE_MODE = "live_mode"
        private const val KEY_INTERVAL = "live_interval_sec"
        private const val KEY_LIVE_STARTED = "live_started_at"
        private const val KEY_INTERRUPT_REASON = "interrupt_reason"
        private const val KEY_POWER_SAVE = "power_save_slowdown"
        private const val KEY_SYNC_STARTED = "sync_started_at"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_LAST_SUMMARY = "last_sync_summary"
        private const val KEY_CONNECTOR_ETAG = "connector_etag"
        private const val KEY_CONNECTOR_HOST = "connector_host"
    }
}
