package dev.diligent.app.ui.navigation

/**
 * Navigation routes for the Diligent app.
 * Uses sealed class for type-safe navigation.
 */
sealed class DiligentScreen(val route: String) {
    data object Dashboard : DiligentScreen("dashboard")
    data object Statistics : DiligentScreen("statistics")
    data object Settings : DiligentScreen("settings")
    data object ActivityEdit : DiligentScreen("activity_edit/{activityId}") {
        fun createRoute(activityId: Long = 0L) = "activity_edit/$activityId"
    }
    data object ActivityDetail : DiligentScreen("activity_detail/{activityId}") {
        fun createRoute(activityId: Long) = "activity_detail/$activityId"
    }
}
