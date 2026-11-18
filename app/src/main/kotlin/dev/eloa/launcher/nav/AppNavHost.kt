package dev.eloa.launcher.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.eloa.launcher.core.designsystem.theme.LocalLauncherExtras
import dev.eloa.launcher.domain.java.content.JavaContentType
import dev.eloa.launcher.feature.addonsui.AddonsScreen
import dev.eloa.launcher.feature.bedrock.BedrockScreen
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.content.ContentScreen
import dev.eloa.launcher.feature.diagnostics.DiagnosticsScreen
import dev.eloa.launcher.feature.files.FilesScreen
import dev.eloa.launcher.feature.home.HomeScreen
import dev.eloa.launcher.feature.javaui.JavaScreen
import dev.eloa.launcher.feature.logs.LogsScreen
import dev.eloa.launcher.feature.modpacks.ModpacksScreen
import dev.eloa.launcher.feature.mods.ModsScreen
import dev.eloa.launcher.feature.profiles.ProfileEditorScreen
import dev.eloa.launcher.feature.profiles.ProfilesScreen
import dev.eloa.launcher.feature.settings.SettingsScreen
import dev.eloa.launcher.feature.versions.VersionsScreen

@Composable
fun AppNavHost(messages: UserMessages, errorBadge: Int) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val current = Destination.of(route)
    val reduce = LocalLauncherExtras.current.reduceMotion
    val navigate: (Destination) -> Unit = { d ->
        nav.navigate(d.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val editProfile: (String?) -> Unit = { id -> nav.navigate("${Destination.PROFILE_EDIT}?id=${id.orEmpty()}") }
    val title = if (route?.startsWith(Destination.PROFILE_EDIT) == true) "Profile" else current?.label ?: "Eloa"
    LauncherScaffold(current, title, messages, errorBadge, navigate) { padding ->
        NavHost(
            nav, startDestination = Destination.HOME.route,
            enterTransition = { if (reduce) fadeIn(tween(0)) else fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 12 } },
            exitTransition = { fadeOut(tween(if (reduce) 0 else 150)) },
        ) {
            composable(Destination.HOME.route) { HomeScreen(padding, navigate) }
            composable(Destination.BEDROCK.route) { BedrockScreen(padding) }
            composable(Destination.JAVA.route) { JavaScreen(padding, navigate) }
            composable(Destination.VERSIONS.route) { VersionsScreen(padding) }
            composable(Destination.MODS.route) { ModsScreen(padding) }
            composable(Destination.MODPACKS.route) { ModpacksScreen(padding) }
            composable(Destination.TEXTURES.route) { ContentScreen(JavaContentType.RESOURCE_PACKS, padding) }
            composable(Destination.SHADERS.route) { ContentScreen(JavaContentType.SHADER_PACKS, padding) }
            composable(Destination.ADDONS.route) { AddonsScreen(padding) }
            composable(Destination.FILES.route) { FilesScreen(padding) }
            composable(Destination.PROFILES.route) { ProfilesScreen(padding, editProfile) }
            composable(Destination.SETTINGS.route) { SettingsScreen(padding) }
            composable(Destination.DIAGNOSTICS.route) { DiagnosticsScreen(padding) }
            composable(Destination.LOGS.route) { LogsScreen(padding) }
            composable("${Destination.PROFILE_EDIT}?id={id}", arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" })) {
                ProfileEditorScreen(padding, onDone = { nav.popBackStack() })
            }
        }
    }
}
