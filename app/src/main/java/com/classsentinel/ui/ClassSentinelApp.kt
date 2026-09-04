package com.classsentinel.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.classsentinel.R
import com.classsentinel.service.ListenService
import com.classsentinel.ui.screens.AnswerDetailScreen
import com.classsentinel.ui.screens.HistoryScreen
import com.classsentinel.ui.screens.HomeScreen
import com.classsentinel.ui.screens.LiveScreen
import com.classsentinel.ui.screens.OnboardingScreen
import com.classsentinel.ui.screens.SettingsScreen
import kotlinx.coroutines.flow.map

private data class BottomTab(val route: String, val label: String, val icon: @Composable () -> Unit)

@Composable
fun ClassSentinelApp(initialEventId: Long? = null) {
    val context = LocalContext.current
    val settings = remember { com.classsentinel.data.SettingsRepositoryHolder.get(context) }
    val onboardingFlow = remember(settings) {
        settings.onboardingCompletedFlow.map { completed -> completed as Boolean? }
    }
    val onboardingCompleted by onboardingFlow
        .collectAsState(initial = null)
    val navController = rememberNavController()
    val tabs = listOf(
        BottomTab(HOME_ROUTE, stringResource(R.string.tab_listen)) { Icon(Icons.Filled.Mic, null) },
        BottomTab("history", stringResource(R.string.tab_history)) { Icon(Icons.Filled.History, null) },
        BottomTab("settings", stringResource(R.string.tab_settings)) { Icon(Icons.Filled.Settings, null) },
    )

    Scaffold(
        bottomBar = {
            val backStack by navController.currentBackStackEntryAsState()
            val current = backStack?.destination
            if (onboardingCompleted != null && isBottomBarRoute(current?.route)) {
                NavigationBar {
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
        }
    ) { padding ->
        if (onboardingCompleted == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("正在读取设置…")
            }
        } else {
            LaunchedEffect(onboardingCompleted, initialEventId) {
                if (onboardingCompleted == true && initialEventId != null) {
                    navController.navigate(answerDetailRoute(initialEventId)) {
                        launchSingleTop = true
                    }
                }
            }
            NavHost(
                navController = navController,
                startDestination = startDestinationForOnboarding(onboardingCompleted == true),
                modifier = Modifier.padding(padding),
            ) {
                composable(HOME_ROUTE) {
                    HomeScreen(onOpenLive = { navController.navigate("live") })
                }
                composable("live") { LiveScreen() }
                composable("history") {
                    HistoryScreen(
                        onAnswerClick = { id -> navController.navigate(answerDetailRoute(id)) },
                        onRetry = { id -> ListenService.retryAnswer(context, id) },
                    )
                }
                composable("answer/{eventId}") { backStackEntry ->
                    AnswerDetailScreen(
                        eventId = parseAnswerEventId(backStackEntry.arguments?.getString("eventId")),
                        onRetry = { id -> ListenService.retryAnswer(context, id) },
                        onIgnore = { navController.popBackStack() },
                    )
                }
                composable("settings") { SettingsScreen() }
                composable(ONBOARDING_ROUTE) {
                    OnboardingScreen(
                        onDone = {
                            navController.navigate(HOME_ROUTE) {
                                popUpTo(ONBOARDING_ROUTE) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                    )
                }
            }
        }
    }
}

internal const val HOME_ROUTE = "home"
internal const val ONBOARDING_ROUTE = "onboarding"

internal fun startDestinationForOnboarding(completed: Boolean): String =
    if (completed) HOME_ROUTE else ONBOARDING_ROUTE

internal fun isBottomBarRoute(route: String?): Boolean =
    route == HOME_ROUTE || route == "history" || route == "settings"

internal fun answerDetailRoute(eventId: Long): String {
    require(eventId > 0L) { "eventId must be positive" }
    return "answer/$eventId"
}

internal fun parseAnswerEventId(raw: String?): Long? =
    raw?.takeIf { it.matches(Regex("[1-9][0-9]*")) }?.toLongOrNull()
