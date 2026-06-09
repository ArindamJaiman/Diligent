package dev.diligent.app.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.diligent.app.data.local.DiligentDatabase
import dev.diligent.app.data.local.dao.ActivityDao
import dev.diligent.app.data.local.dao.ActivityReminderDao
import dev.diligent.app.data.local.dao.DailyProgressDao
import dev.diligent.app.data.local.dao.SettingsDao
import javax.inject.Singleton

/**
 * Hilt module providing database and DAO singletons.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DiligentDatabase {
        return Room.databaseBuilder(
            context,
            DiligentDatabase::class.java,
            DiligentDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideActivityDao(db: DiligentDatabase): ActivityDao = db.activityDao()

    @Provides
    fun provideDailyProgressDao(db: DiligentDatabase): DailyProgressDao = db.dailyProgressDao()

    @Provides
    fun provideSettingsDao(db: DiligentDatabase): SettingsDao = db.settingsDao()

    @Provides
    fun provideActivityReminderDao(db: DiligentDatabase): ActivityReminderDao = db.activityReminderDao()
}
