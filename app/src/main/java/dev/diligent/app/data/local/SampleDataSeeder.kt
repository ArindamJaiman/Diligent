package dev.diligent.app.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.repository.DiligentRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seeds the database with default activities on first launch.
 * Only runs if no activities exist.
 */
@Singleton
class SampleDataSeeder @Inject constructor(
    private val repository: DiligentRepository
) {
    suspend fun seedIfEmpty() {
        val existing = repository.getAllActiveActivities().first()
        if (existing.isNotEmpty()) return

        val defaults = listOf(
            Activity(name = "Quantum Computing", unit = "hrs", dailyGoal = 5f, icon = "science", sortOrder = 0),
            Activity(name = "LeetCode", unit = "qs", dailyGoal = 3f, icon = "code", sortOrder = 1),
            Activity(name = "HFT Preparation", unit = "hrs", dailyGoal = 2f, icon = "trending_up", sortOrder = 2),
            Activity(name = "Research Papers", unit = "hrs", dailyGoal = 2f, icon = "article", sortOrder = 3),
            Activity(name = "Gym", unit = "hrs", dailyGoal = 1f, icon = "fitness_center", sortOrder = 4),
            Activity(name = "Reading", unit = "hrs", dailyGoal = 1f, icon = "menu_book", sortOrder = 5),
            Activity(name = "Coding", unit = "hrs", dailyGoal = 3f, icon = "terminal", sortOrder = 6)
        )

        repository.insertAllActivities(defaults)

        // Initialize settings
        repository.updateSettings(dev.diligent.app.data.local.entity.Settings())
    }
}
