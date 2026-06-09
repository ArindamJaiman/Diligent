package dev.diligent.app.data.local.dao

import androidx.room.*
import dev.diligent.app.data.local.entity.Settings
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Settings (single-row table).
 */
@Dao
interface SettingsDao {

    @Query("SELECT * FROM settings WHERE id = 1")
    fun getSettings(): Flow<Settings?>

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun getSettingsSnapshot(): Settings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(settings: Settings)

    @Update
    suspend fun update(settings: Settings)
}
