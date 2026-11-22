package dev.eloa.launcher.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.eloa.launcher.R
import dev.eloa.launcher.core.designsystem.theme.LocalLauncherExtras
import dev.eloa.launcher.domain.java.content.JavaContentType
import dev.eloa.launcher.feature.accounts.AccountsScreen
import dev.eloa.launcher.feature.addonsui.AddonsScreen
import dev.eloa.launcher.feature.bedrock.BedrockScreen
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.content.ContentScreen
import dev.eloa.launcher.feature.controls.ControlEditorScreen
import dev.eloa.launcher.feature.controls.ControlsScreen
import dev.eloa.launcher.feature.diagnostics.DiagnosticsScreen
import dev.eloa.launcher.feature.downloads.DownloadsScreen
import dev.eloa.launcher.feature.files.FilesScreen
import dev.eloa.launcher.feature.gamesettings.GameSettingsScreen
import dev.eloa.launcher.feature.home.HomeScreen
import dev.eloa.launcher.feature.javaui.JavaScreen
import dev.eloa.launcher.feature.library.LibraryScreen
import dev.eloa.launcher.feature.logs.LogsScreen
import dev.eloa.launcher.feature.modpacks.ModpacksScreen
import dev.eloa.launcher.feature.mods.ModsScreen
import dev.eloa.launcher.feature.notifications.NotificationsScreen
import dev.eloa.launcher.feature.performance.PerformanceScreen
import dev.eloa.launcher.feature.privacy.PrivacyScreen
import dev.eloa.launcher.feature.profiles.ProfileEditorScreen
import dev.eloa.launcher.feature.profiles.ProfilesScreen
import dev.eloa.launcher.feature.runtimes.RuntimesScreen
import dev.eloa.launcher.feature.settings.SettingsScreen
import dev.eloa.launcher.feature.skins.SkinEditorScreen
import dev.eloa.launcher.feature.skins.SkinsScreen
import dev.eloa.launcher.feature.updates.UpdatesScreen
import dev.eloa.launcher.feature.versions.VersionsScreen
import dev.eloa.launcher.feature.worlds.WorldsScreen

@Composable
fun AppNavHost(messages: UserMessages, chrome: ChromeState, requestedRoute: String?, onRouteHandled: () -> Unit) {
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
    LaunchedEffect(requestedRoute) {
        requestedRoute?.let { r -> Destination.entries.firstOrNull { it.route == r }?.let(navigate); onRouteHandled() }
    }
    val editProfile: (String?) -> Unit = { id -> nav.navigate("${Destination.PROFILE_EDIT}?id=${id.orEmpty()}") }
    val editSkin: (String) -> Unit = { id -> nav.navigate("${Destination.SKIN_EDIT}?id=${android.net.Uri.encode(id)}") }
    val editControls: (String) -> Unit = { name -> nav.navigate("${Destination.CONTROL_EDIT}?name=${android.net.Uri.encode(name)}") }
    val title = when {
        route?.startsWith(Destination.PROFILE_EDIT) == true -> stringResource(R.string.title_profile_editor)
        route?.startsWith(Destination.CONTROL_EDIT) == true -> stringResource(R.string.title_control_editor)
        route?.startsWith(Destination.SKIN_EDIT) == true -> stringResource(R.string.title_skin_editor)
        current != null -> stringResource(current.label)
        else -> stringResource(R.string.app_name)
    }
    val nested = listOf(Destination.PROFILE_EDIT, Destination.CONTROL_EDIT, Destination.SKIN_EDIT).any { route?.startsWith(it) == true }
    LauncherScaffold(current, title, nested, messages, chrome, navigate, onBack = { nav.popBackStack() }) { padding ->
        NavHost(
            nav, startDestination = Destination.HOME.route,
            enterTransition = { if (reduce) fadeIn(tween(0)) else fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 12 } },
            exitTransition = { fadeOut(tween(if (reduce) 0 else 150)) },
        ) {
            composable(Destination.HOME.route) { HomeScreen(padding, navigate) }
            composable(Destination.LIBRARY.route) { LibraryScreen(padding, navigate) }
            composable(Destination.MODS.route) { ModsScreen(padding) }
            composable(Destination.WORLDS.route) { WorldsScreen(padding) }
            composable(Destination.ACCOUNTS.route) { AccountsScreen(padding) }
            composable(Destination.SETTINGS.route) { SettingsScreen(padding, navigate) }
            composable(Destination.BEDROCK.route) { BedrockScreen(padding) }
            composable(Destination.JAVA.route) { JavaScreen(padding, navigate) }
            composable(Destination.VERSIONS.route) { VersionsScreen(padding) }
            composable(Destination.PROFILES.route) { ProfilesScreen(padding, editProfile) }
            composable(Destination.MODPACKS.route) { ModpacksScreen(padding) }
            composable(Destination.TEXTURES.route) { ContentScreen(JavaContentType.RESOURCE_PACKS, padding) }
            composable(Destination.SHADERS.route) { ContentScreen(JavaContentType.SHADER_PACKS, padding) }
            composable(Destination.ADDONS.route) { AddonsScreen(padding) }
            composable(Destination.SKINS.route) { SkinsScreen(padding, editSkin) }
            composable("${Destination.SKIN_EDIT}?id={id}", arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" })) {
                SkinEditorScreen(padding, onDone = { nav.popBackStack() })
            }
            composable(Destination.RUNTIMES.route) { RuntimesScreen(padding) }
            composable(Destination.DOWNLOADS.route) { DownloadsScreen(padding) }
            composable(Destination.FILES.route) { FilesScreen(padding) }
            composable(Destination.PERFORMANCE.route) { PerformanceScreen(padding, navigate) }
            composable(Destination.GAME_SETTINGS.route) { GameSettingsScreen(padding, navigate) }
            composable(Destination.CONTROLS.route) { ControlsScreen(padding, editControls) }
            composable(Destination.NOTIFICATIONS.route) { NotificationsScreen(padding, onOpenRoute = { r -> Destination.entries.firstOrNull { it.route == r }?.let(navigate) }) }
            composable(Destination.LOGS.route) { LogsScreen(padding) }
            composable(Destination.DIAGNOSTICS.route) { DiagnosticsScreen(padding) }
            composable(Destination.UPDATES.route) { UpdatesScreen(padding) }
            composable(Destination.PRIVACY.route) { PrivacyScreen(padding) }
            composable("${Destination.PROFILE_EDIT}?id={id}", arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" })) {
                ProfileEditorScreen(padding, onDone = { nav.popBackStack() })
            }
            composable("${Destination.CONTROL_EDIT}?name={name}", arguments = listOf(navArgument("name") { type = NavType.StringType; defaultValue = "" })) {
                ControlEditorScreen(padding, onDone = { nav.popBackStack() })
            }
        }
    }
}
