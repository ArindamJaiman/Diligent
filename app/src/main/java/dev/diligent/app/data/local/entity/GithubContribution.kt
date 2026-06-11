package dev.diligent.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Caches contribution calendar data fetched from GitHub.
 *
 * @param username The developer's GitHub username (PrimaryKey).
 * @param contributionsJson A JSON-serialized list of contribution day pairs (date -> level).
 * @param lastUpdated Timestamp of the last successful fetch.
 * @param totalAnnual Total number of active days or contributions in the last year.
 * @param currentStreak The current consecutive day commit streak.
 */
@Serializable
@Entity(tableName = "github_contributions")
data class GithubContribution(
    @PrimaryKey
    val username: String,
    val contributionsJson: String,
    val lastUpdated: Long = System.currentTimeMillis(),
    val totalAnnual: Int = 0,
    val currentStreak: Int = 0
)
