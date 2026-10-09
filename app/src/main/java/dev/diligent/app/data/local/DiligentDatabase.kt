package dev.diligent.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.diligent.app.data.local.dao.ActivityDao
import dev.diligent.app.data.local.dao.ActivityReminderDao
import dev.diligent.app.data.local.dao.DailyProgressDao
import dev.diligent.app.data.local.dao.GithubContributionDao
import dev.diligent.app.data.local.dao.SettingsDao
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.ActivityReminder
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.data.local.entity.GithubContribution
import dev.diligent.app.data.local.entity.Settings

/**
 * Room database for the Diligent app.
 * Contains all entities and provides DAO access.
 */
@Database(
    entities = [
        Activity::class,
        DailyProgress::class,
        Settings::class,
        ActivityReminder::class,
        GithubContribution::class
    ],
    version = 3,
    exportSchema = true
)
abstract class DiligentDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
    abstract fun dailyProgressDao(): DailyProgressDao
    abstract fun settingsDao(): SettingsDao
    abstract fun activityReminderDao(): ActivityReminderDao
    abstract fun githubContributionDao(): GithubContributionDao

    companion object {
        const val DATABASE_NAME = "diligent_db"

        /** v2 added the GitHub contribution cache and the GitHub settings columns. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `github_contributions` (`username` TEXT NOT NULL, " +
                        "`contributionsJson` TEXT NOT NULL, `lastUpdated` INTEGER NOT NULL, " +
                        "`totalAnnual` INTEGER NOT NULL, `currentStreak` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`username`))"
                )
                listOf(
                    "githubUser1" to "LennyDany-03",
                    "githubUser1Color" to "emerald",
                    "githubUser2" to "Quadr1on",
                    "githubUser2Color" to "crimson",
                    "githubUser3" to "SidhanthBibi",
                    "githubUser3Color" to "blue"
                ).forEach { (column, default) ->
                    db.execSQL("ALTER TABLE `settings` ADD COLUMN `$column` TEXT NOT NULL DEFAULT '$default'")
                }
            }
        }

        /** v3 added the widget quote preset selection. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `settings` ADD COLUMN `quotePreset` TEXT NOT NULL DEFAULT 'wall_street'")
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
