package dev.eloa.launcher.nav

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.eloa.launcher.feature.bedrock.BedrockScreen
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.home.HomeScreen

@Composable
fun AppNavHost(messages: UserMessages, errorBadge: Int) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = Destination.of(entry?.destination?.route)
    val navigate: (Destination) -> Unit = { d ->
        nav.navigate(d.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    LauncherScaffold(current, current?.label ?: "Eloa", messages, errorBadge, navigate) { padding ->
        NavHost(nav, startDestination = Destination.HOME.route, enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) {
            composable(Destination.HOME.route) { HomeScreen(padding, navigate) }
            composable(Destination.BEDROCK.route) { BedrockScreen(padding) }
        }
    }
}
