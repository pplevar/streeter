package com.streeter.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.streeter.ui.detail.WalkDetailScreen
import com.streeter.ui.edit.RouteEditScreen
import com.streeter.ui.history.HistoryScreen
import com.streeter.ui.home.HomeScreen
import com.streeter.ui.manual.ManualCreateScreen
import com.streeter.ui.privacy.PrivacyDisclosureScreen
import com.streeter.ui.recording.RecordingScreen
import com.streeter.ui.settings.SettingsScreen

@Composable
fun StreeterNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Screen.Home.route) {

        composable(Screen.Home.route) {
            HomeScreen(
                onStartWalk = { navController.navigate(Screen.Recording.route) },
                onViewHistory = { navController.navigate(Screen.History.route) },
                onCreateManual = { navController.navigate(Screen.ManualCreate.route) },
                onOpenSettings = { navController.navigate(Screen.Settings.route) }
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
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.WalkDetail.route,
            arguments = listOf(navArgument("walkId") { type = NavType.LongType }),
            deepLinks = listOf(navDeepLink { uriPattern = "streeter://walk/{walkId}" })
        ) {
            WalkDetailScreen(
                onNavigateBack = { navController.popBackStack() },
                onEditRoute = { walkId ->
                    navController.navigate(Screen.RouteEdit.createRoute(walkId))
                }
            )
        }

        composable(
            route = Screen.RouteEdit.route,
            arguments = listOf(navArgument("walkId") { type = NavType.LongType }),
            deepLinks = listOf(navDeepLink { uriPattern = "streeter://walk/{walkId}/edit" })
        ) {
            RouteEditScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.ManualCreate.route) {
            ManualCreateScreen(
                onNavigateBack = { navController.popBackStack() },
                onWalkCreated = { walkId ->
                    navController.navigate(Screen.WalkDetail.createRoute(walkId)) {
                        popUpTo(Screen.ManualCreate.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Privacy.route) {
            PrivacyDisclosureScreen(
                onAccept = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Privacy.route) { inclusive = true }
                    }
                }
            )
        }
    }
}
