package dev.diligent.app.leetcode.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import dev.diligent.app.leetcode.domain.ReviewSchedule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A solved problem in the spaced-repetition queue (see [ReviewSchedule]).
 *
 * Times are epoch millis. [stage] counts the reviews done: 0 waits for the 1-day review and
 * [ReviewSchedule.DONE] means retired (all five done, or skipped as too easy). Retired rows
 * stay in the table so the problem isn't re-added the next time it shows up in the recent
 * accepted list. [difficulty] is only known when the problem was a daily challenge — the
 * recent-accepts list doesn't carry it, and looking it up would cost a request per problem.
 */
@Entity(tableName = "leetcode_reviews")
data class LeetCodeReview(
    @PrimaryKey val slug: String,
    val title: String,
    val difficulty: String?,
    /** The first accept Diligent saw (LeetCode only shares the last 20). */
    val firstSolvedAt: Long,
    val stage: Int,
    /** Start of the local day the next review is due; [ReviewSchedule.NEVER] once retired. */
    val dueAt: Long,
    /** The accept, skip or reset that set the current stage. Older accepts are ignored. */
    val lastReviewedAt: Long
) {
    val url: String get() = LeetCodeLinks.problem(slug)
    val retired: Boolean get() = stage >= ReviewSchedule.DONE

    fun isDue(now: Long): Boolean = !retired && dueAt <= now
}

@Dao
interface LeetCodeReviewDao {

    /** Everything still in rotation, soonest first. */
    @Query("SELECT * FROM leetcode_reviews WHERE stage < ${ReviewSchedule.DONE} ORDER BY dueAt, slug")
    fun observeActive(): Flow<List<LeetCodeReview>>

    @Query("SELECT * FROM leetcode_reviews WHERE stage < ${ReviewSchedule.DONE} ORDER BY dueAt, slug")
    suspend fun active(): List<LeetCodeReview>

    @Query("SELECT * FROM leetcode_reviews WHERE slug IN (:slugs)")
    suspend fun bySlugs(slugs: List<String>): List<LeetCodeReview>

    @Query("SELECT * FROM leetcode_reviews WHERE slug = :slug")
    suspend fun get(slug: String): LeetCodeReview?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reviews: List<LeetCodeReview>)

    @Query("DELETE FROM leetcode_reviews")
    suspend fun clear()
}

/**
 * The review queue. Fed by [record] after each successful sync — it never fetches anything
 * itself — and changed by the screen's skip / reset buttons.
 */
@Singleton
class LeetCodeReviewStore @Inject constructor(private val dao: LeetCodeReviewDao) {

    /** Sync and the screen's buttons both read-modify-write rows. */
    private val mutex = Mutex()

    fun observeActive(): Flow<List<LeetCodeReview>> = dao.observeActive()

    suspend fun active(): List<LeetCodeReview> = dao.active()

    /** For widgets and notifications: a database error shows as "nothing due", never a crash. */
    suspend fun activeOrEmpty(): List<LeetCodeReview> = try {
        dao.active()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /** Adds newly seen accepted problems and advances due ones the sync found re-solved. */
    suspend fun record(snapshot: LeetCodeSnapshot, zone: ZoneId = ZoneId.systemDefault()): Int = mutex.withLock {
        val slugs = snapshot.recentAc.map { it.slug }.distinct()
        if (slugs.isEmpty()) return@withLock 0
        val existing = dao.bySlugs(slugs).associateBy { it.slug }
        val changed = ReviewSchedule.apply(existing, snapshot.recentAc, snapshot.daily, zone)
        if (changed.isNotEmpty()) dao.upsert(changed)
        changed.size
    }

    /** "Too easy, skip": retires the problem. */
    suspend fun skip(slug: String, now: Long): Boolean = change(slug) { ReviewSchedule.skip(it, now) }

    /** "Reset": back to the 1-day review, counted from today. */
    suspend fun reset(slug: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        change(slug) { ReviewSchedule.reset(it, now, zone) }

    suspend fun clear() = mutex.withLock { dao.clear() }

    private suspend fun change(slug: String, transform: (LeetCodeReview) -> LeetCodeReview): Boolean = mutex.withLock {
        val review = dao.get(slug) ?: return@withLock false
        dao.upsert(listOf(transform(review)))
        true
    }
}
