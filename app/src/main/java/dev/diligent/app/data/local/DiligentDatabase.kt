package dev.diligent.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.diligent.app.data.local.dao.ActivityDao
import dev.diligent.app.data.local.dao.ActivityReminderDao
import dev.diligent.app.data.local.dao.DailyProgressDao
import dev.diligent.app.data.local.dao.GithubContributionDao
import dev.diligent.app.data.local.dao.GithubUserDao
import dev.diligent.app.data.local.dao.SettingsDao
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.ActivityReminder
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.data.local.entity.GithubContribution
import dev.diligent.app.data.local.entity.GithubUser
import dev.diligent.app.data.local.entity.Settings
import dev.diligent.app.leetcode.data.LeetCodeReview
import dev.diligent.app.leetcode.data.LeetCodeReviewDao
import dev.diligent.app.quotapulse.data.QuotaPulseDao
import dev.diligent.app.quotapulse.data.UsageSnapshot
import dev.diligent.app.quotapulse.data.UsageSourceStatus

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
        GithubContribution::class,
        UsageSnapshot::class,
        UsageSourceStatus::class,
        LeetCodeReview::class,
        GithubUser::class
    ],
    version = 6,
    exportSchema = true
)
abstract class DiligentDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
    abstract fun dailyProgressDao(): DailyProgressDao
    abstract fun settingsDao(): SettingsDao
    abstract fun activityReminderDao(): ActivityReminderDao
    abstract fun githubContributionDao(): GithubContributionDao
    abstract fun quotaPulseDao(): QuotaPulseDao
    abstract fun leetCodeReviewDao(): LeetCodeReviewDao
    abstract fun githubUserDao(): GithubUserDao

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

        /** v4 added the QuotaPulse usage snapshot and source status tables. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `usage_snapshots` (`provider` TEXT NOT NULL, " +
                        "`metricKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `percentLeft` REAL, `amount` REAL, `unit` TEXT, `detail` TEXT, " +
                        "`resetsAt` INTEGER, `measuredAt` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`provider`, `metricKey`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `usage_source_status` (`provider` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, `state` TEXT NOT NULL, `message` TEXT, " +
                        "`lastAttemptAt` INTEGER NOT NULL, `lastSuccessAt` INTEGER NOT NULL, " +
                        "`retryAfter` INTEGER NOT NULL, PRIMARY KEY(`provider`, `source`))"
                )
            }
        }

        /** Same SQL as the exported schema 5.json; DatabaseSchemaTest keeps the two in step. */
        internal const val CREATE_LEETCODE_REVIEWS =
            "CREATE TABLE IF NOT EXISTS `leetcode_reviews` (`slug` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`difficulty` TEXT, `firstSolvedAt` INTEGER NOT NULL, `stage` INTEGER NOT NULL, " +
                "`dueAt` INTEGER NOT NULL, `lastReviewedAt` INTEGER NOT NULL, PRIMARY KEY(`slug`))"

        /** v5 added the LeetCode spaced-repetition review queue. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_LEETCODE_REVIEWS)
            }
        }

        /** Same SQL as the exported schema 6.json; DatabaseSchemaTest keeps the two in step. */
        internal const val CREATE_GITHUB_USERS =
            "CREATE TABLE IF NOT EXISTS `github_users` (`username` TEXT NOT NULL COLLATE NOCASE, " +
                "`color` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`username`))"

        /** The v6 settings table: v5 minus the three githubUser columns. */
        internal fun createSettingsV6(table: String) =
            "CREATE TABLE IF NOT EXISTS `$table` (`id` INTEGER NOT NULL, `darkMode` INTEGER, " +
                "`dailyReminderEnabled` INTEGER NOT NULL, `dailyReminderHour` INTEGER NOT NULL, " +
                "`dailyReminderMinute` INTEGER NOT NULL, `missedGoalNotification` INTEGER NOT NULL, " +
                "`dynamicColor` INTEGER NOT NULL, `lastBackupTime` INTEGER NOT NULL, " +
                "`quotePreset` TEXT NOT NULL, PRIMARY KEY(`id`))"

        private const val SETTINGS_V6_COLUMNS = "`id`, `darkMode`, `dailyReminderEnabled`, `dailyReminderHour`, " +
            "`dailyReminderMinute`, `missedGoalNotification`, `dynamicColor`, `lastBackupTime`, `quotePreset`"

        /**
         * v6 moved GitHub developers from three fixed settings columns to their own ordered
         * list, so there can be more than three. Filled slots are copied in order (a name in
         * two slots once); SQLite can't drop columns on older Android, so settings is rebuilt.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_GITHUB_USERS)
                (1..3).forEach { slot ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO `github_users` (`username`, `color`, `position`) " +
                            "SELECT TRIM(`githubUser$slot`), `githubUser${slot}Color`, ${slot - 1} FROM `settings` " +
                            "WHERE TRIM(`githubUser$slot`) != ''"
                    )
                }
                db.execSQL(createSettingsV6("settings_new"))
                db.execSQL("INSERT INTO `settings_new` ($SETTINGS_V6_COLUMNS) SELECT $SETTINGS_V6_COLUMNS FROM `settings`")
                db.execSQL("DROP TABLE `settings`")
                db.execSQL("ALTER TABLE `settings_new` RENAME TO `settings`")
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
    }
}
