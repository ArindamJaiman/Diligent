package dev.diligent.app.data.local.dao

import androidx.room.*
import dev.diligent.app.data.local.entity.FocusRecord
import kotlinx.coroutines.flow.Flow

/** Finished focus sessions, newest first. */
@Dao
interface FocusRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: FocusRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<FocusRecord>)

    @Update
    suspend fun update(record: FocusRecord)

    @Query("SELECT * FROM focus_sessions WHERE id = :id")
    suspend fun getById(id: Long): FocusRecord?

    @Query("SELECT * FROM focus_sessions WHERE startedAt >= :from ORDER BY startedAt DESC")
    fun observeSince(from: Long): Flow<List<FocusRecord>>

    @Query("SELECT * FROM focus_sessions WHERE startedAt >= :from AND startedAt < :to ORDER BY startedAt ASC")
    suspend fun between(from: Long, to: Long): List<FocusRecord>

    @Query("SELECT * FROM focus_sessions ORDER BY startedAt DESC")
    suspend fun getAll(): List<FocusRecord>
}
