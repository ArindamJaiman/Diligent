package dev.diligent.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.ActivityReminder
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.data.local.entity.GithubUser
import dev.diligent.app.data.local.entity.GithubUsers
import dev.diligent.app.data.local.entity.Settings
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.notifications.ReminderScheduler
import dev.diligent.app.widget.WidgetUpdater
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 * Handles app preferences, backup/restore, and CSV export.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: DiligentRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val settings: StateFlow<Settings> =
        repository.getSettings()
            .map { it ?: Settings() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Settings())

    /** GitHub developers, in widget order. */
    val githubUsers: StateFlow<List<GithubUser>> =
        repository.getGithubUsers()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _message = MutableSharedFlow<String>()
    val message: SharedFlow<String> = _message.asSharedFlow()

    // ─── Settings Updates ───────────────────────────────────────

    fun updateDailyReminder(enabled: Boolean) {
        updateRemindersWith { it.copy(dailyReminderEnabled = enabled) }
    }

    fun updateReminderTime(hour: Int, minute: Int) {
        updateRemindersWith { it.copy(dailyReminderHour = hour, dailyReminderMinute = minute) }
    }

    fun updateMissedGoalNotification(enabled: Boolean) {
        updateRemindersWith { it.copy(missedGoalNotification = enabled) }
    }

    /** Saves a reminder-related change and reschedules the jobs to match it. */
    private fun updateRemindersWith(change: (Settings) -> Settings) {
        viewModelScope.launch {
            val updated = change(repository.getSettingsSnapshot())
            repository.updateSettings(updated)
            ReminderScheduler.sync(context, updated, replaceExisting = true)
        }
    }

    fun updateDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            val current = repository.getSettingsSnapshot()
            repository.updateSettings(current.copy(dynamicColor = enabled))
        }
    }

    fun updateQuotePreset(presetId: String) {
        viewModelScope.launch {
            val current = repository.getSettingsSnapshot()
            if (current.quotePreset == presetId) return@launch
            repository.updateSettings(current.copy(quotePreset = presetId))
            WidgetUpdater.refreshTracker(context)
        }
    }

    // ─── GitHub Developers ──────────────────────────────────────

    fun addGithubUser(raw: String) = editGithubUsers { GithubUsers.add(it, raw) }

    fun renameGithubUser(username: String, raw: String) = editGithubUsers { GithubUsers.rename(it, username, raw) }

    fun removeGithubUser(username: String) = editGithubUsers { GithubUsers.remove(it, username) }

    /** [delta] -1 moves up one place, +1 down; the first two are the Versus pair. */
    fun moveGithubUser(username: String, delta: Int) = editGithubUsers { GithubUsers.move(it, username, delta) }

    fun updateGithubColor(username: String, colorName: String) =
        editGithubUsers { GithubUsers.recolor(it, username, colorName) }

    private fun editGithubUsers(edit: (List<GithubUser>) -> GithubUsers.Edit) {
        viewModelScope.launch {
            when (val result = repository.editGithubUsers(edit)) {
                is GithubUsers.Edit.Done -> {
                    result.message?.let { _message.emit(it) }
                    WidgetUpdater.refreshGithub(context)
                }
                is GithubUsers.Edit.Rejected -> _message.emit(result.message)
            }
        }
    }

    /**
     * Manually sync all configured GitHub contribution graphs.
     * Triggered only by the user pressing "Update Contribution Graphs" in Settings.
     */
    fun syncAllGithubContributions() {
        viewModelScope.launch {
            val count = repository.getGithubUsersSnapshot().size
            if (count == 0) {
                _message.emit("Add a GitHub username first.")
                return@launch
            }

            _message.emit("Syncing $count contribution graphs...")

            val (successCount, total) = repository.syncAllGithubContributions()

            if (successCount == total) {
                _message.emit("All $successCount graphs updated successfully!")
            } else {
                _message.emit("$successCount/$total synced. Check usernames and connection.")
            }

            WidgetUpdater.refreshGithub(context)
        }
    }

    // ─── Backup & Restore ───────────────────────────────────────

    /**
     * Data model for JSON backup. Missing fields fall back to defaults, so backups
     * made by older versions (or containing fields added later) still restore.
     *
     * [githubUsers] is null in backups from before the developer list; those carry the
     * three old slots inside settings instead (see [GithubUsers.fromLegacySettings]).
     */
    @Serializable
    data class BackupData(
        val activities: List<Activity> = emptyList(),
        val dailyProgress: List<DailyProgress> = emptyList(),
        val settings: Settings = Settings(),
        val reminders: List<ActivityReminder> = emptyList(),
        val githubUsers: List<GithubUser>? = null,
        val backupTime: String = ""
    )

    private val backupJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    fun backupToJson() {
        viewModelScope.launch {
            try {
                val backup = BackupData(
                    // Include archived activities too: their progress rows reference them.
                    activities = repository.getAllActivitiesSnapshot(),
                    dailyProgress = repository.getAllDailyProgressSnapshot(),
                    settings = repository.getSettingsSnapshot(),
                    reminders = repository.getAllRemindersSnapshot(),
                    githubUsers = repository.getGithubUsersSnapshot(),
                    backupTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                )

                val json = backupJson.encodeToString(backup)
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.getExternalFilesDir(null), "backups")
                    dir.mkdirs()
                    File(dir, "diligent_backup_${System.currentTimeMillis()}.json").also { it.writeText(json) }
                }

                repository.updateSettings(
                    repository.getSettingsSnapshot().copy(lastBackupTime = System.currentTimeMillis())
                )

                _message.emit("Backup saved to ${file.absolutePath}")
            } catch (e: Exception) {
                _message.emit("Backup failed: ${e.message}")
            }
        }
    }

    fun restoreFromJson(uri: Uri) {
        viewModelScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                } ?: throw IllegalStateException("Cannot read file")

                val backup = backupJson.decodeFromString<BackupData>(json)

                // Restore data
                repository.insertAllActivities(backup.activities)

                // Skip rows whose activity no longer exists (older backups left archived
                // activities out), instead of failing the whole restore on a foreign key.
                val knownIds = repository.getAllActivitiesSnapshot().mapTo(HashSet()) { it.id }
                val progress = backup.dailyProgress.filter { it.activityId in knownIds }
                val reminders = backup.reminders.filter { it.activityId in knownIds }
                repository.insertAllProgress(progress)
                repository.updateSettings(backup.settings)
                repository.insertAllReminders(reminders)
                val legacySettings = backupJson.parseToJsonElement(json).jsonObject["settings"] as? JsonObject
                (backup.githubUsers ?: GithubUsers.fromLegacySettings(legacySettings))
                    ?.let { repository.replaceGithubUsers(it) }

                ReminderScheduler.sync(context, backup.settings, replaceExisting = true)
                WidgetUpdater.refreshAll(context)

                val skipped = (backup.dailyProgress.size - progress.size) + (backup.reminders.size - reminders.size)
                val suffix = if (skipped > 0) " ($skipped orphaned rows skipped)" else ""
                _message.emit("Restore complete — ${backup.activities.size} activities recovered$suffix")
            } catch (e: Exception) {
                _message.emit("Restore failed: ${e.message}")
            }
        }
    }

    // ─── CSV Export ─────────────────────────────────────────────

    fun exportStatsCsv() {
        viewModelScope.launch {
            try {
                val activityMap = repository.getAllActivitiesSnapshot().associateBy { it.id }
                val progress = repository.getAllDailyProgressSnapshot()

                val csv = buildString {
                    appendLine("Date,Activity,Progress,Goal,Unit,Completed")
                    progress.sortedBy { it.date }.forEach { p ->
                        val a = activityMap[p.activityId]
                        if (a != null) {
                            appendLine("${p.date},${csvField(a.name)},${p.progress},${a.dailyGoal},${csvField(a.unit)},${p.isCompleted}")
                        }
                    }
                }

                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.getExternalFilesDir(null), "exports")
                    dir.mkdirs()
                    File(dir, "diligent_stats_${System.currentTimeMillis()}.csv").also { it.writeText(csv) }
                }

                _message.emit("CSV exported to ${file.absolutePath}")
            } catch (e: Exception) {
                _message.emit("Export failed: ${e.message}")
            }
        }
    }

    /** Quotes a CSV field and escapes embedded quotes (RFC 4180). */
    private fun csvField(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
