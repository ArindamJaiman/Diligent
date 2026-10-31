package dev.diligent.app.cloud

import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.ActivityReminder
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.data.local.entity.DayLog
import dev.diligent.app.data.local.entity.FocusRecord
import dev.diligent.app.data.local.entity.GithubUser
import dev.diligent.app.data.local.entity.Settings
import dev.diligent.app.data.repository.DiligentRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Data model for JSON backup. Missing fields fall back to defaults, so backups
 * made by older versions (or containing fields added later) still restore.
 *
 * [githubUsers] is null in backups from before the developer list; those carry the
 * three old slots inside settings instead (see GithubUsers.fromLegacySettings).
 */
@Serializable
data class BackupData(
    val activities: List<Activity> = emptyList(),
    val dailyProgress: List<DailyProgress> = emptyList(),
    val settings: Settings = Settings(),
    val reminders: List<ActivityReminder> = emptyList(),
    val githubUsers: List<GithubUser>? = null,
    val focusRecords: List<FocusRecord> = emptyList(),
    val dayLogs: List<DayLog> = emptyList(),
    val backupTime: String = ""
) {
    companion object {
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }

        /** Everything in the database right now (archived activities too: their rows reference them). */
        suspend fun snapshot(repository: DiligentRepository) = BackupData(
            activities = repository.getAllActivitiesSnapshot(),
            dailyProgress = repository.getAllDailyProgressSnapshot(),
            settings = repository.getSettingsSnapshot(),
            reminders = repository.getAllRemindersSnapshot(),
            githubUsers = repository.getGithubUsersSnapshot(),
            focusRecords = repository.getAllFocusRecords(),
            dayLogs = repository.getAllDayLogs(),
            backupTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
    }
}
