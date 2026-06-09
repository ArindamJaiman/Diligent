package dev.diligent.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.diligent.app.data.local.dao.ActivityDao
import dev.diligent.app.data.local.dao.ActivityReminderDao
import dev.diligent.app.data.local.dao.DailyProgressDao
import dev.diligent.app.data.local.dao.SettingsDao
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.ActivityReminder
import dev.diligent.app.data.local.entity.DailyProgress
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
        ActivityReminder::class
    ],
    version = 1,
    exportSchema = true
)
abstract class DiligentDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
    abstract fun dailyProgressDao(): DailyProgressDao
    abstract fun settingsDao(): SettingsDao
    abstract fun activityReminderDao(): ActivityReminderDao

    companion object {
        const val DATABASE_NAME = "diligent_db"
    }
}
