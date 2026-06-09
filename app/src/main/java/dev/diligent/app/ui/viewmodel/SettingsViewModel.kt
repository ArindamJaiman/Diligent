package dev.diligent.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.data.local.entity.Settings
import dev.diligent.app.data.repository.DiligentRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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

    private val _message = MutableSharedFlow<String>()
    val message: SharedFlow<String> = _message.asSharedFlow()

    // ─── Settings Updates ───────────────────────────────────────

    fun updateDailyReminder(enabled: Boolean) {
        viewModelScope.launch {
            val current = repository.getSettingsSnapshot()
            repository.updateSettings(current.copy(dailyReminderEnabled = enabled))
        }
    }

    fun updateReminderTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            val current = repository.getSettingsSnapshot()
            repository.updateSettings(current.copy(dailyReminderHour = hour, dailyReminderMinute = minute))
        }
    }

    fun updateMissedGoalNotification(enabled: Boolean) {
        viewModelScope.launch {
            val current = repository.getSettingsSnapshot()
            repository.updateSettings(current.copy(missedGoalNotification = enabled))
        }
    }

    fun updateDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            val current = repository.getSettingsSnapshot()
            repository.updateSettings(current.copy(dynamicColor = enabled))
        }
    }

    // ─── Backup & Restore ───────────────────────────────────────

    /**
     * Data model for JSON backup.
     */
    data class BackupData(
        val activities: List<dev.diligent.app.data.local.entity.Activity>,
        val dailyProgress: List<dev.diligent.app.data.local.entity.DailyProgress>,
        val settings: Settings,
        val reminders: List<dev.diligent.app.data.local.entity.ActivityReminder>,
        val backupTime: String
    )

    fun backupToJson() {
        viewModelScope.launch {
            try {
                val gson = GsonBuilder().setPrettyPrinting().create()
                val backup = BackupData(
                    activities = repository.getAllActiveSnapshot(),
                    dailyProgress = repository.getAllDailyProgressSnapshot(),
                    settings = repository.getSettingsSnapshot(),
                    reminders = repository.getAllRemindersSnapshot(),
                    backupTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                )

                val json = gson.toJson(backup)
                val dir = File(context.getExternalFilesDir(null), "backups")
                dir.mkdirs()
                val filename = "diligent_backup_${System.currentTimeMillis()}.json"
                val file = File(dir, filename)
                file.writeText(json)

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
                val inputStream = context.contentResolver.openInputStream(uri)
                val json = inputStream?.bufferedReader()?.readText() ?: throw Exception("Cannot read file")
                inputStream.close()

                val gson = Gson()
                val backup = gson.fromJson(json, BackupData::class.java)

                // Restore data
                repository.insertAllActivities(backup.activities)
                repository.insertAllProgress(backup.dailyProgress)
                repository.updateSettings(backup.settings)
                repository.insertAllReminders(backup.reminders)

                _message.emit("Restore complete — ${backup.activities.size} activities recovered")
            } catch (e: Exception) {
                _message.emit("Restore failed: ${e.message}")
            }
        }
    }

    // ─── CSV Export ─────────────────────────────────────────────

    fun exportStatsCsv() {
        viewModelScope.launch {
            try {
                val activities = repository.getAllActiveSnapshot()
                val progress = repository.getAllDailyProgressSnapshot()
                val activityMap = activities.associateBy { it.id }

                val csv = buildString {
                    appendLine("Date,Activity,Progress,Goal,Unit,Completed")
                    progress.sortedBy { it.date }.forEach { p ->
                        val a = activityMap[p.activityId]
                        if (a != null) {
                            appendLine("${p.date},\"${a.name}\",${p.progress},${a.dailyGoal},${a.unit},${p.isCompleted}")
                        }
                    }
                }

                val dir = File(context.getExternalFilesDir(null), "exports")
                dir.mkdirs()
                val filename = "diligent_stats_${System.currentTimeMillis()}.csv"
                val file = File(dir, filename)
                file.writeText(csv)

                _message.emit("CSV exported to ${file.absolutePath}")
            } catch (e: Exception) {
                _message.emit("Export failed: ${e.message}")
            }
        }
    }
}
