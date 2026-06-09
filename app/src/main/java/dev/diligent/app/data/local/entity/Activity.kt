package dev.diligent.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Represents a trackable activity (e.g., "Quantum Computing", "LeetCode").
 *
 * @param id Unique identifier, auto-generated.
 * @param name Display name of the activity.
 * @param unit Measurement unit — "hrs", "qs" (questions), "reps", "pages", "mins", etc.
 * @param dailyGoal Target value per day (e.g., 5 hours, 3 questions).
 * @param icon Material icon name for display.
 * @param sortOrder Position in the list for manual ordering.
 * @param isArchived Soft-delete flag; archived activities are hidden but data preserved.
 * @param createdAt Epoch millis when the activity was first created.
 * @param color Optional hex color override (defaults to monochrome white).
 */
@Serializable
@Entity(tableName = "activities")
data class Activity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val unit: String = "hrs",
    val dailyGoal: Float = 1f,
    val icon: String = "radio_button_unchecked",
    val sortOrder: Int = 0,
    val isArchived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val color: String = "#FFFFFF"
)
