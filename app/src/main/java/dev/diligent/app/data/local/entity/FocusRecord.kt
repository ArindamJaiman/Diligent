package dev.diligent.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * One finished focus session, kept for time-of-day analysis, session ratings and the calendar
 * log. The minutes themselves are also added to [DailyProgress] as before.
 *
 * @param startedAt Epoch millis the timer started.
 * @param endedAt Epoch millis it stopped.
 * @param focusMinutes Minutes logged (Pomodoro breaks left out).
 * @param intention What the session was for, typed before starting (optional).
 * @param rating Self-rated focus 1–5 after the session; 0 when not rated.
 * @param interruptions Times "interrupted" was tapped during the session.
 * @param broken The session's stake broke: a blocked app was opened before it ended.
 */
@Serializable
@Entity(
    tableName = "focus_sessions",
    foreignKeys = [
        ForeignKey(
            entity = Activity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["activityId"]), Index(value = ["startedAt"])]
)
data class FocusRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val activityId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val focusMinutes: Int,
    val pomodoro: Boolean = false,
    val intention: String = "",
    val rating: Int = 0,
    val interruptions: Int = 0,
    val broken: Boolean = false
)
