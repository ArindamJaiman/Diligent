package dev.diligent.app.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.diligent.app.leetcode.ui.LeetCodeScreen
import dev.diligent.app.quotapulse.data.Provider
import dev.diligent.app.quotapulse.ui.QuotaPulseProviderScreen
import dev.diligent.app.quotapulse.ui.QuotaPulseScreen
import dev.diligent.app.tools.ToolRoutes
import dev.diligent.app.tools.toolsGraph
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
fun DiligentNavGraph(
    navController: NavHostController,
    pendingRoute: String? = null,
    onRouteConsumed: () -> Unit = {}
) {
    // Routes requested from outside the app (e.g. QuotaPulse widget taps)
    LaunchedEffect(pendingRoute) {
        if (pendingRoute != null) {
            navController.navigate(pendingRoute) { launchSingleTop = true }
            onRouteConsumed()
        }
    }

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
                },
                onNavigateToQuotaPulse = {
                    navController.navigate(DiligentScreen.QuotaPulse.route)
                },
                onNavigateToLeetCode = {
                    navController.navigate(DiligentScreen.LeetCode.route)
                },
                onNavigateToHub = {
                    navController.navigate(ToolRoutes.HUB)
                }
            )
        }

        composable(DiligentScreen.LeetCode.route) {
            LeetCodeScreen(onNavigateBack = { navController.popBackStack() })
        }

        // Hub and productivity tools (Contest Radar, Papers, Top 3, Screen time, ...)
        toolsGraph(navController)

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

        composable(DiligentScreen.QuotaPulse.route) {
            QuotaPulseScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenProvider = { provider ->
                    navController.navigate(DiligentScreen.QuotaPulseProvider.createRoute(provider.name))
                }
            )
        }

        composable(
            route = DiligentScreen.QuotaPulseProvider.route,
            arguments = listOf(navArgument("provider") { type = NavType.StringType })
        ) { entry ->
            val provider = Provider.from(entry.arguments?.getString("provider")) ?: Provider.CLAUDE
            QuotaPulseProviderScreen(
                provider = provider,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
