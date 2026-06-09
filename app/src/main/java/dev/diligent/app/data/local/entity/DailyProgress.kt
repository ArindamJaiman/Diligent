package dev.diligent.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Tracks daily progress for a specific activity.
 * One row per activity per day. The composite index on (activityId, date)
 * ensures uniqueness and fast lookups.
 *
 * @param id Auto-generated primary key.
 * @param activityId FK → Activity.id
 * @param date ISO-8601 date string (yyyy-MM-dd) for the day.
 * @param progress Current progress value (e.g., 3.5 hours).
 * @param isCompleted Whether the daily goal was met or manually marked done.
 * @param lastUpdated Epoch millis of the last progress update.
 * @param notes Optional user note for the day.
 */
@Serializable
@Entity(
    tableName = "daily_progress",
    foreignKeys = [
        ForeignKey(
            entity = Activity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["activityId", "date"], unique = true),
        Index(value = ["date"])
    ]
)
data class DailyProgress(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val activityId: Long,
    val date: String, // "2026-06-08"
    val progress: Float = 0f,
    val isCompleted: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis(),
    val notes: String = ""
)
