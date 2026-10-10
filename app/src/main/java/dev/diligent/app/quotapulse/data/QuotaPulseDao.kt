package dev.diligent.app.quotapulse.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface QuotaPulseDao {

    @Query("SELECT * FROM usage_snapshots ORDER BY provider, metricKey")
    fun observeSnapshots(): Flow<List<UsageSnapshot>>

    @Query("SELECT * FROM usage_snapshots ORDER BY provider, metricKey")
    suspend fun snapshots(): List<UsageSnapshot>

    @Query("SELECT * FROM usage_source_status")
    fun observeStatuses(): Flow<List<UsageSourceStatus>>

    @Query("SELECT * FROM usage_source_status")
    suspend fun statuses(): List<UsageSourceStatus>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshots(snapshots: List<UsageSnapshot>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStatus(status: UsageSourceStatus)

    @Query("DELETE FROM usage_snapshots WHERE source = :source AND provider = :provider")
    suspend fun deleteSnapshots(provider: String, source: String)

    @Query("DELETE FROM usage_snapshots WHERE source = :source")
    suspend fun deleteSnapshotsFromSource(source: String)

    @Query("DELETE FROM usage_source_status WHERE source = :source")
    suspend fun deleteStatusesFromSource(source: String)

    /**
     * Replaces everything one source reported for one provider, so metrics the
     * source stopped reporting don't linger as if they were current.
     */
    @Transaction
    suspend fun replaceFromSource(provider: String, source: String, snapshots: List<UsageSnapshot>) {
        deleteSnapshots(provider, source)
        if (snapshots.isNotEmpty()) upsertSnapshots(snapshots)
    }

    @Query("DELETE FROM usage_snapshots")
    suspend fun clearSnapshots()

    @Query("DELETE FROM usage_source_status")
    suspend fun clearStatuses()
}
