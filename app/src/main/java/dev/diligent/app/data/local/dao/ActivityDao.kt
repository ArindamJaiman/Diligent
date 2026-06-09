package dev.diligent.app.data.local.dao

import androidx.room.*
import dev.diligent.app.data.local.entity.Activity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the Activity entity.
 * All queries return Flow for reactive UI updates.
 */
@Dao
interface ActivityDao {

    @Query("SELECT * FROM activities WHERE isArchived = 0 ORDER BY sortOrder ASC")
    fun getAllActive(): Flow<List<Activity>>

    @Query("SELECT * FROM activities ORDER BY sortOrder ASC")
    fun getAll(): Flow<List<Activity>>

    @Query("SELECT * FROM activities WHERE isArchived = 0 ORDER BY sortOrder ASC")
    suspend fun getAllActiveSnapshot(): List<Activity>

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun getById(id: Long): Activity?

    @Query("SELECT * FROM activities WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Activity?>

    @Query("SELECT * FROM activities WHERE name LIKE '%' || :query || '%' AND isArchived = 0")
    fun search(query: String): Flow<List<Activity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(activity: Activity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(activities: List<Activity>)

    @Update
    suspend fun update(activity: Activity)

    @Delete
    suspend fun delete(activity: Activity)

    @Query("UPDATE activities SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM activities")
    suspend fun getNextSortOrder(): Int
}
