package dev.diligent.app.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.diligent.app.ui.screens.ActivityDetailScreen
import dev.diligent.app.ui.screens.ActivityEditScreen
import dev.diligent.app.ui.screens.DashboardScreen
import dev.diligent.app.ui.screens.SettingsScreen
import dev.diligent.app.ui.screens.StatisticsScreen

/**
 * Navigation graph for the Diligent app.
 * Smooth fade transitions between screens for a premium feel.
 */
@Composable
fun DiligentNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = DiligentScreen.Dashboard.route,
        enterTransition = { fadeIn(animationSpec = tween(300)) + slideInHorizontally(initialOffsetX = { it / 4 }) },
        exitTransition = { fadeOut(animationSpec = tween(200)) },
        popEnterTransition = { fadeIn(animationSpec = tween(300)) + slideInHorizontally(initialOffsetX = { -it / 4 }) },
        popExitTransition = { fadeOut(animationSpec = tween(200)) }
    ) {
        composable(DiligentScreen.Dashboard.route) {
            DashboardScreen(
                onNavigateToEdit = { activityId ->
                    navController.navigate(DiligentScreen.ActivityEdit.createRoute(activityId))
                },
                onNavigateToDetail = { activityId ->
                    navController.navigate(DiligentScreen.ActivityDetail.createRoute(activityId))
                },
                onNavigateToStats = {
                    navController.navigate(DiligentScreen.Statistics.route)
                },
                onNavigateToSettings = {
                    navController.navigate(DiligentScreen.Settings.route)
                }
            )
        }

        composable(
            route = DiligentScreen.ActivityEdit.route,
            arguments = listOf(navArgument("activityId") { type = NavType.LongType })
        ) {
            ActivityEditScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = DiligentScreen.ActivityDetail.route,
            arguments = listOf(navArgument("activityId") { type = NavType.LongType })
        ) {
            ActivityDetailScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEdit = { activityId ->
                    navController.navigate(DiligentScreen.ActivityEdit.createRoute(activityId))
                }
            )
        }

        composable(DiligentScreen.Statistics.route) {
            StatisticsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(DiligentScreen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
