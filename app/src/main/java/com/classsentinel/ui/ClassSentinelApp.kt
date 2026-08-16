package com.classsentinel.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.classsentinel.R
import com.classsentinel.ui.screens.CourseDetailScreen
import com.classsentinel.ui.screens.HistoryScreen
import com.classsentinel.ui.screens.HomeScreen
import com.classsentinel.ui.screens.LiveScreen
import com.classsentinel.ui.screens.OnboardingScreen
import com.classsentinel.ui.screens.SelfTestScreen
import com.classsentinel.ui.screens.SettingsScreen

private data class BottomTab(val route: String, val label: String, val icon: @Composable () -> Unit)

@Composable
fun ClassSentinelApp() {
    val navController = rememberNavController()
    val tabs = listOf(
        BottomTab("home", stringResource(R.string.tab_listen)) { Icon(Icons.Filled.Mic, null) },
        BottomTab("history", stringResource(R.string.tab_history)) { Icon(Icons.Filled.History, null) },
        BottomTab("settings", stringResource(R.string.tab_settings)) { Icon(Icons.Filled.Settings, null) },
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStack by navController.currentBackStackEntryAsState()
                val current = backStack?.destination
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = tab.icon,
                        label = { Text(tab.label) },
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding),
        ) {
            composable("home") { HomeScreen() }
            composable("live") { LiveScreen() }
            composable("history") {
                HistoryScreen(onCourseClick = { id -> navController.navigate("course/$id") })
            }
            composable("course/{id}") { CourseDetailScreen() }
            composable("settings") { SettingsScreen() }
            composable("selftest") { SelfTestScreen() }
            composable("onboarding") { OnboardingScreen() }
        }
    }
}
