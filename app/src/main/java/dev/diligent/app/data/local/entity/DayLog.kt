package dev.diligent.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Whole-day measurements next to the per-activity progress, used for correlations, the
 * readiness score and the evening shutdown. Null means "not known", which isn't the same as 0.
 *
 * @param date yyyy-MM-dd tracking day.
 * @param mood Self-rated 1–5; 0 when not rated.
 * @param energy Self-rated 1–5; 0 when not rated.
 * @param sleepMinutes Sleep that ended on this day (Health Connect).
 * @param sleepStart Epoch millis that sleep began.
 * @param steps Steps on this day (Health Connect).
 * @param mindfulMinutes Mindfulness sessions (Health Connect).
 * @param distractingMinutes Time in apps marked distracting (Screen vs Focus).
 * @param note A line written at the evening shutdown.
 * @param shutdownAt Epoch millis the shutdown ritual was finished; 0 when it wasn't.
 */
@Serializable
@Entity(tableName = "day_logs")
data class DayLog(
    @PrimaryKey
    val date: String,
    val mood: Int = 0,
    val energy: Int = 0,
    val sleepMinutes: Int? = null,
    val sleepStart: Long? = null,
    val steps: Int? = null,
    val mindfulMinutes: Int? = null,
    val distractingMinutes: Int? = null,
    val note: String = "",
    val shutdownAt: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)
