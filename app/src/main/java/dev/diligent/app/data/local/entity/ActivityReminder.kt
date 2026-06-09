package dev.diligent.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Per-activity reminder configuration.
 * Allows users to set custom reminders for specific activities.
 *
 * @param id Auto-generated primary key.
 * @param activityId FK → Activity.id
 * @param hour Hour of day (0-23).
 * @param minute Minute of hour (0-59).
 * @param daysOfWeek Bitmask: Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64. All=127.
 * @param isEnabled Whether this reminder is currently active.
 */
@Serializable
@Entity(
    tableName = "activity_reminders",
    foreignKeys = [
        ForeignKey(
            entity = Activity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["activityId"])]
)
data class ActivityReminder(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val activityId: Long,
    val hour: Int = 9,
    val minute: Int = 0,
    val daysOfWeek: Int = 127, // All days
    val isEnabled: Boolean = true
)
