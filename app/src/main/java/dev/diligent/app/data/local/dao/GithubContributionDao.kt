package dev.diligent.app.data.local.dao

import androidx.room.*
import dev.diligent.app.data.local.entity.GithubContribution
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for GithubContribution cache.
 */
@Dao
interface GithubContributionDao {

    @Query("SELECT * FROM github_contributions WHERE username = :username")
    fun getContribution(username: String): Flow<GithubContribution?>

    @Query("SELECT * FROM github_contributions WHERE username = :username")
    suspend fun getContributionSnapshot(username: String): GithubContribution?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contribution: GithubContribution)

    @Delete
    suspend fun delete(contribution: GithubContribution)
}
