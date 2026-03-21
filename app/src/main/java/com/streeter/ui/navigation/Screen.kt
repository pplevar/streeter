package com.streeter.ui.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Recording : Screen("recording")
    data object History : Screen("history")
    data object WalkDetail : Screen("walk_detail/{walkId}") {
        fun createRoute(walkId: Long) = "walk_detail/$walkId"
    }
    data object RouteEdit : Screen("route_edit/{walkId}") {
        fun createRoute(walkId: Long) = "route_edit/$walkId"
    }
    data object ManualCreate : Screen("manual_create")
    data object Settings : Screen("settings")
    data object Privacy : Screen("privacy")
}
