package dev.diligent.app.data.local.dao

import androidx.room.*
import dev.diligent.app.data.local.entity.ActivityReminder
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for ActivityReminder.
 */
@Dao
interface ActivityReminderDao {

    @Query("SELECT * FROM activity_reminders WHERE activityId = :activityId")
    fun getRemindersForActivity(activityId: Long): Flow<List<ActivityReminder>>

    @Query("SELECT * FROM activity_reminders WHERE isEnabled = 1")
    suspend fun getAllEnabledSnapshot(): List<ActivityReminder>

    @Query("SELECT * FROM activity_reminders")
    suspend fun getAllSnapshot(): List<ActivityReminder>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: ActivityReminder): Long

    @Update
    suspend fun update(reminder: ActivityReminder)

    @Delete
    suspend fun delete(reminder: ActivityReminder)

    @Query("DELETE FROM activity_reminders WHERE activityId = :activityId")
    suspend fun deleteAllForActivity(activityId: Long)
}
