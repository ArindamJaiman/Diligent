package dev.diligent.app.data.local.dao

import androidx.room.*
import dev.diligent.app.data.local.entity.DayLog
import kotlinx.coroutines.flow.Flow

@Dao
interface DayLogDao {

    @Query("SELECT * FROM day_logs WHERE date = :date")
    suspend fun get(date: String): DayLog?

    @Query("SELECT * FROM day_logs WHERE date = :date")
    fun observe(date: String): Flow<DayLog?>

    @Query("SELECT * FROM day_logs WHERE date BETWEEN :start AND :end ORDER BY date ASC")
    suspend fun range(start: String, end: String): List<DayLog>

    @Query("SELECT * FROM day_logs WHERE date BETWEEN :start AND :end ORDER BY date ASC")
    fun observeRange(start: String, end: String): Flow<List<DayLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: DayLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<DayLog>)

    @Query("SELECT * FROM day_logs ORDER BY date ASC")
    suspend fun getAll(): List<DayLog>
}
