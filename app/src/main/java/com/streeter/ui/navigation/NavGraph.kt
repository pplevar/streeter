package com.streeter.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.streeter.ui.history.HistoryScreen
import com.streeter.ui.home.HomeScreen
import com.streeter.ui.recording.RecordingScreen

@Composable
fun StreeterNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Screen.Home.route) {

        composable(Screen.Home.route) {
            HomeScreen(
                onStartWalk = { navController.navigate(Screen.Recording.route) },
                onViewHistory = { navController.navigate(Screen.History.route) },
                onCreateManual = { navController.navigate(Screen.ManualCreate.route) }
            )
        }

        composable(Screen.Recording.route) {
            RecordingScreen(
                onStopAndNavigate = { walkId ->
                    navController.navigate(Screen.WalkDetail.createRoute(walkId)) {
                        popUpTo(Screen.Home.route)
                    }
                }
            )
        }

        composable(Screen.History.route) {
            HistoryScreen(
                onWalkSelected = { walkId ->
                    navController.navigate(Screen.WalkDetail.createRoute(walkId))
                }
            )
        }

        composable(
            route = Screen.WalkDetail.route,
            arguments = listOf(navArgument("walkId") { type = NavType.LongType }),
            deepLinks = listOf(navDeepLink { uriPattern = "streeter://walk/{walkId}" })
        ) { backStackEntry ->
            val walkId = backStackEntry.arguments?.getLong("walkId") ?: return@composable
            // WalkDetailScreen placeholder — implemented in Phase 7
            HomeScreen(
                onStartWalk = {},
                onViewHistory = { navController.popBackStack() },
                onCreateManual = {}
            )
        }

        composable(
            route = Screen.RouteEdit.route,
            arguments = listOf(navArgument("walkId") { type = NavType.LongType }),
            deepLinks = listOf(navDeepLink { uriPattern = "streeter://walk/{walkId}/edit" })
        ) { backStackEntry ->
            // RouteEditScreen placeholder — implemented in Phase 5
            HomeScreen(
                onStartWalk = {},
                onViewHistory = { navController.popBackStack() },
                onCreateManual = {}
            )
        }

        composable(Screen.ManualCreate.route) {
            // ManualCreateScreen placeholder — implemented in Phase 6
            HomeScreen(
                onStartWalk = {},
                onViewHistory = { navController.popBackStack() },
                onCreateManual = {}
            )
        }
    }
}
