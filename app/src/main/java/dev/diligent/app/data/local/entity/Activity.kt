package dev.diligent.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.diligent.app.tracking.Goals
import dev.diligent.app.tracking.Schedule
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
 * @param scheduleType When it's due: [Schedule.DAILY], [Schedule.WEEKDAYS] (on [scheduleDays]),
 *   [Schedule.WEEKLY] ([scheduleCount] times a week) or [Schedule.INTERVAL] (every [scheduleCount] days).
 * @param scheduleDays Weekday bitmask for WEEKDAYS: Mon=1 … Sun=64, like ActivityReminder.daysOfWeek.
 * @param scheduleCount Times a week for WEEKLY, days between for INTERVAL.
 * @param goalType [Goals.AT_LEAST] (reach the goal), [Goals.AT_MOST] (stay under it) or [Goals.QUIT].
 * @param targetTotal Long-term target in the activity's unit, 0 for none (e.g. 200 hrs).
 * @param targetDate Deadline for [targetTotal], yyyy-MM-dd.
 * @param pendingGoal Commitment mode: a lower daily goal that applies from [pendingGoalDate].
 * @param pendingGoalDate yyyy-MM-dd, empty when no change is waiting.
 * @param isPrivate Name shown as dots on widgets and the lock screen.
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
    val color: String = "#FFFFFF",
    val scheduleType: String = Schedule.DAILY,
    val scheduleDays: Int = Schedule.ALL_DAYS,
    val scheduleCount: Int = 1,
    val goalType: String = Goals.AT_LEAST,
    val targetTotal: Float = 0f,
    val targetDate: String = "",
    val pendingGoal: Float = 0f,
    val pendingGoalDate: String = "",
    val isPrivate: Boolean = false
)
