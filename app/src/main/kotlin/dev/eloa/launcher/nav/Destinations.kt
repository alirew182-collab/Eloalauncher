package dev.eloa.launcher.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Texture
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.ui.graphics.vector.ImageVector
import dev.eloa.launcher.R

/**
 * All screens. [group] 0 = primary sections (bottom bar on phones), 1 = library, 2 = tools.
 */
enum class Destination(val route: String, @StringRes val label: Int, val icon: ImageVector, val group: Int) {
    HOME("home", R.string.nav_home, Icons.Outlined.Home, 0),
    LIBRARY("library", R.string.nav_library, Icons.Outlined.ViewModule, 0),
    MODS("mods", R.string.nav_mods, Icons.Outlined.Extension, 0),
    WORLDS("worlds", R.string.nav_worlds, Icons.Outlined.Public, 0),
    ACCOUNTS("accounts", R.string.nav_accounts, Icons.Outlined.AccountCircle, 0),
    SETTINGS("settings", R.string.nav_settings, Icons.Outlined.Settings, 0),

    JAVA("java", R.string.nav_java, Icons.Outlined.Coffee, 1),
    BEDROCK("bedrock", R.string.nav_bedrock, Icons.Outlined.Smartphone, 1),
    VERSIONS("versions", R.string.nav_versions, Icons.Outlined.Layers, 1),
    PROFILES("profiles", R.string.nav_profiles, Icons.Outlined.People, 1),
    MODPACKS("modpacks", R.string.nav_modpacks, Icons.Outlined.Inventory2, 1),
    TEXTURES("textures", R.string.nav_textures, Icons.Outlined.Texture, 1),
    SHADERS("shaders", R.string.nav_shaders, Icons.Outlined.AutoAwesome, 1),
    ADDONS("addons", R.string.nav_addons, Icons.Outlined.Widgets, 1),
    SKINS("skins", R.string.nav_skins, Icons.Outlined.Checkroom, 1),
    RUNTIMES("runtimes", R.string.nav_runtimes, Icons.Outlined.Memory, 1),
    DOWNLOADS("downloads", R.string.nav_downloads, Icons.Outlined.CloudDownload, 1),
    FILES("files", R.string.nav_files, Icons.Outlined.Folder, 1),

    PERFORMANCE("performance", R.string.nav_performance, Icons.Outlined.Speed, 2),
    GAME_SETTINGS("game_settings", R.string.nav_game_settings, Icons.Outlined.Tune, 2),
    CONTROLS("controls", R.string.nav_controls, Icons.Outlined.Gamepad, 2),
    NOTIFICATIONS("notifications", R.string.nav_notifications, Icons.Outlined.Notifications, 2),
    LOGS("logs", R.string.nav_logs, Icons.Outlined.BugReport, 2),
    DIAGNOSTICS("diagnostics", R.string.nav_diagnostics, Icons.Outlined.Analytics, 2),
    UPDATES("updates", R.string.nav_updates, Icons.Outlined.SystemUpdate, 2),
    PRIVACY("privacy", R.string.nav_privacy, Icons.Outlined.Security, 2);

    companion object {
        fun of(route: String?) = entries.firstOrNull { route == it.route || route?.startsWith(it.route + "?") == true }
        val primary get() = entries.filter { it.group == 0 }
        const val PROFILE_EDIT = "profile_edit"
        const val CONTROL_EDIT = "control_edit"
        const val SKIN_EDIT = "skin_edit"
    }
}
