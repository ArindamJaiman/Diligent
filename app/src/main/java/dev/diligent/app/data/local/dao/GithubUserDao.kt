package dev.diligent.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.diligent.app.data.local.entity.GithubUser
import kotlinx.coroutines.flow.Flow

/** The ordered list of developers shown by the GitHub widgets. */
@Dao
interface GithubUserDao {

    @Query("SELECT * FROM github_users ORDER BY position, username")
    fun observeAll(): Flow<List<GithubUser>>

    @Query("SELECT * FROM github_users ORDER BY position, username")
    suspend fun getAll(): List<GithubUser>

    @Query("DELETE FROM github_users")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(users: List<GithubUser>)

    /** Every edit rewrites the whole (short) list, so positions never collide or leave gaps. */
    @Transaction
    suspend fun replaceAll(users: List<GithubUser>) {
        clear()
        if (users.isNotEmpty()) insertAll(users)
    }
}
