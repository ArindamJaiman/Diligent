package dev.diligent.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Persisted app-level settings.
 * Uses a singleton pattern with a fixed id = 1.
 *
 * @param id Always 1 — single-row table.
 * @param darkMode Whether dark mode is forced (null = follow system).
 * @param dailyReminderEnabled Global daily reminder toggle.
 * @param dailyReminderHour Hour of day for the global reminder (0-23).
 * @param dailyReminderMinute Minute of hour for the global reminder (0-59).
 * @param missedGoalNotification Whether to notify when a goal is missed at end of day.
 * @param dynamicColor Whether to use Material You dynamic color (false = strict monochrome).
 * @param lastBackupTime Epoch millis of the last JSON backup.
 */
@Serializable
@Entity(tableName = "settings")
data class Settings(
    @PrimaryKey
    val id: Int = 1,
    val darkMode: Boolean? = null,
    val dailyReminderEnabled: Boolean = true,
    val dailyReminderHour: Int = 9,
    val dailyReminderMinute: Int = 0,
    val missedGoalNotification: Boolean = true,
    val dynamicColor: Boolean = false,
    val lastBackupTime: Long = 0L
)
