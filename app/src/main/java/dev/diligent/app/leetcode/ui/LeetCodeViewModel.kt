package dev.diligent.app.leetcode.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.leetcode.data.LeetCodeConfig
import dev.diligent.app.leetcode.data.LeetCodePrefs
import dev.diligent.app.leetcode.data.LeetCodeState
import dev.diligent.app.leetcode.notify.LeetCodeAlarms
import dev.diligent.app.leetcode.notify.LeetCodeNotifier
import dev.diligent.app.leetcode.notify.LeetCodeReminders
import dev.diligent.app.leetcode.presentation.LeetCodePresenter
import dev.diligent.app.leetcode.presentation.LeetCodeView
import dev.diligent.app.leetcode.sync.LeetCodeSync
import dev.diligent.app.leetcode.sync.LeetCodeSyncResult
import dev.diligent.app.leetcode.widget.LeetCodeWidgetUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LeetCodeUiState(
    val view: LeetCodeView,
    val state: LeetCodeState,
    val activities: List<Activity>,
    val linkedActivity: Activity?,
    val now: Long
)

@HiltViewModel
class LeetCodeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: LeetCodePrefs,
    private val sync: LeetCodeSync,
    repository: DiligentRepository
) : ViewModel() {

    /** Re-evaluates "time left" while the screen is visible (stops with the UI). */
    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }

    val ui: StateFlow<LeetCodeUiState?> = combine(
        prefs.state,
        repository.getAllActiveActivities(),
        ticker
    ) { state, activities, now ->
        val config = state.config
        LeetCodeUiState(
            view = LeetCodePresenter.build(state, now),
            state = state,
            activities = activities,
            linkedActivity = activities.firstOrNull { it.id == config.autoLogActivityId }
                ?: activities.firstOrNull { it.name.contains("leetcode", ignoreCase = true) },
            now = now
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        // Opening the screen refreshes data that is more than a few minutes old.
        val current = prefs.current()
        val stale = current.snapshot?.let { System.currentTimeMillis() - it.fetchedAt > AUTO_REFRESH_MS } ?: true
        if (current.config.configured && stale) syncNow(quiet = true)
    }

    fun syncNow(quiet: Boolean = false) {
        viewModelScope.launch {
            when (val result = sync.sync(force = !quiet)) {
                is LeetCodeSyncResult.Failed -> _messages.emit(result.message)
                LeetCodeSyncResult.NotConfigured -> if (!quiet) _messages.emit("Add your LeetCode username first")
                is LeetCodeSyncResult.Synced -> if (!quiet) _messages.emit("Synced")
            }
        }
    }

    fun setUsername(raw: String) {
        val username = raw.trim().removePrefix("@").substringAfterLast("leetcode.com/u/").trimEnd('/')
        if (!USERNAME.matches(username)) {
            say("That doesn't look like a LeetCode username")
            return
        }
        val changed = !username.equals(prefs.current().config.username, ignoreCase = true)
        prefs.updateConfig { it.copy(username = username) }
        if (changed) prefs.clearSnapshot()
        LeetCodeReminders.sync(context, prefs.current(), replace = true)
        viewModelScope.launch {
            when (val result = sync.sync(force = true)) {
                is LeetCodeSyncResult.Synced -> _messages.emit("Tracking ${result.snapshot.username}")
                is LeetCodeSyncResult.Failed -> _messages.emit(result.message)
                LeetCodeSyncResult.NotConfigured -> Unit
            }
        }
    }

    fun removeAccount() {
        prefs.updateConfig { it.copy(username = "") }
        prefs.clearSnapshot()
        LeetCodeReminders.cancelAll(context)
        LeetCodeNotifier.cancelAll(context)
        LeetCodeWidgetUpdater.refreshAsync(context)
        say("LeetCode tracking removed")
    }

    fun update(transform: (LeetCodeConfig) -> LeetCodeConfig) {
        prefs.updateConfig(transform)
        val state = prefs.current()
        LeetCodeReminders.sync(context, state, replace = true)
        LeetCodeAlarms.armContests(context, state.snapshot, state.config, System.currentTimeMillis())
        LeetCodeWidgetUpdater.refreshAsync(context)
    }

    private fun say(message: String) {
        viewModelScope.launch { _messages.emit(message) }
    }

    companion object {
        private const val AUTO_REFRESH_MS = 5 * 60_000L
        /** LeetCode usernames: letters, digits, '_' and '-' (we keep '.' for older accounts). */
        private val USERNAME = Regex("^[A-Za-z0-9_.-]{1,40}$")
    }
}
