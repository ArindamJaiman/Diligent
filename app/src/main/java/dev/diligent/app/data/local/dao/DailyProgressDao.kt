package dev.diligent.app.data.local.dao

import androidx.room.*
import dev.diligent.app.data.local.entity.DailyProgress
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for DailyProgress.
 * Provides queries for today's progress, date ranges, and streak calculation.
 */
@Dao
interface DailyProgressDao {

    @Query("SELECT * FROM daily_progress WHERE date = :date ORDER BY activityId ASC")
    fun getProgressForDate(date: String): Flow<List<DailyProgress>>

    @Query("SELECT * FROM daily_progress WHERE date = :date")
    suspend fun getProgressForDateSnapshot(date: String): List<DailyProgress>

    @Query("SELECT * FROM daily_progress WHERE activityId = :activityId AND date = :date LIMIT 1")
    suspend fun getProgressForActivityOnDate(activityId: Long, date: String): DailyProgress?

    @Query("SELECT * FROM daily_progress WHERE activityId = :activityId AND date = :date LIMIT 1")
    fun getProgressForActivityOnDateFlow(activityId: Long, date: String): Flow<DailyProgress?>

    @Query("""
        SELECT * FROM daily_progress 
        WHERE activityId = :activityId AND date BETWEEN :startDate AND :endDate 
        ORDER BY date ASC
    """)
    fun getProgressRange(activityId: Long, startDate: String, endDate: String): Flow<List<DailyProgress>>

    @Query("""
        SELECT * FROM daily_progress 
        WHERE date BETWEEN :startDate AND :endDate 
        ORDER BY date ASC, activityId ASC
    """)
    fun getAllProgressRange(startDate: String, endDate: String): Flow<List<DailyProgress>>

    @Query("""
        SELECT * FROM daily_progress 
        WHERE date BETWEEN :startDate AND :endDate 
        ORDER BY date ASC, activityId ASC
    """)
    suspend fun getAllProgressRangeSnapshot(startDate: String, endDate: String): List<DailyProgress>

    @Query("""
        SELECT COUNT(DISTINCT date) FROM daily_progress 
        WHERE activityId = :activityId AND isCompleted = 1 AND date <= :endDate
        ORDER BY date DESC
    """)
    suspend fun getCompletedDaysCount(activityId: Long, endDate: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(progress: DailyProgress): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(progressList: List<DailyProgress>)

    @Update
    suspend fun update(progress: DailyProgress)

    @Query("DELETE FROM daily_progress WHERE activityId = :activityId")
    suspend fun deleteAllForActivity(activityId: Long)

    @Query("SELECT * FROM daily_progress ORDER BY date DESC")
    suspend fun getAllSnapshot(): List<DailyProgress>

    /**
     * Gets the consecutive days an activity was completed ending at the given date.
     * Used for streak calculation.
     */
    @Query("""
        SELECT date FROM daily_progress 
        WHERE activityId = :activityId AND isCompleted = 1 
        ORDER BY date DESC
    """)
    suspend fun getCompletedDates(activityId: Long): List<String>
}
