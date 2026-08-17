package com.kawaiipet.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kawaiipet.app.ui.screens.CustomizeScreen
import com.kawaiipet.app.ui.screens.HomeScreen
import com.kawaiipet.app.ui.screens.MemoryScreen
import com.kawaiipet.app.ui.screens.SettingsScreen
import com.kawaiipet.app.ui.screens.UsageReminderScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val CUSTOMIZE = "customize"
    const val MEMORY = "memory"
    const val USAGE_REMINDER = "usage_reminder"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(navController = navController)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(navController = navController)
        }
        composable(Routes.CUSTOMIZE) {
            CustomizeScreen(navController = navController)
        }
        composable(Routes.MEMORY) {
            MemoryScreen(navController = navController)
        }
        composable(Routes.USAGE_REMINDER) {
            UsageReminderScreen(navController = navController)
        }
    }
}
