package dev.diligent.app.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.quotapulse.data.QuotaPulseDao
import dev.diligent.app.quotapulse.data.QuotaPulsePrefs
import dev.diligent.app.quotapulse.sync.UsageSyncEngine

/**
 * Hilt entry point for widgets and receivers to access app singletons.
 * Glance widgets and plain receivers can't use standard Hilt injection,
 * so we use EntryPointAccessors.fromApplication() instead.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun repository(): DiligentRepository
    fun quotaPulseDao(): QuotaPulseDao
    fun quotaPulsePrefs(): QuotaPulsePrefs
    fun usageSyncEngine(): UsageSyncEngine
}
