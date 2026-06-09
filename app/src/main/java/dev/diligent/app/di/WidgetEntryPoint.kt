package dev.diligent.app.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.diligent.app.data.repository.DiligentRepository

/**
 * Hilt entry point for the widget to access the repository.
 * Widgets run in a separate process and can't use standard Hilt injection,
 * so we use EntryPointAccessors.fromApplication() instead.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun repository(): DiligentRepository
}
