package dev.eloa.launcher.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Texture
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.ui.graphics.vector.ImageVector

enum class Destination(val route: String, val label: String, val icon: ImageVector, val group: Int) {
    HOME("home", "Home", Icons.Outlined.Home, 0),
    BEDROCK("bedrock", "Bedrock Edition", Icons.Outlined.Smartphone, 0),
    JAVA("java", "Java Edition", Icons.Outlined.Coffee, 0),
    VERSIONS("versions", "Versions", Icons.Outlined.Layers, 1),
    MODS("mods", "Mods", Icons.Outlined.Extension, 1),
    MODPACKS("modpacks", "Modpacks", Icons.Outlined.Inventory2, 1),
    TEXTURES("textures", "Resource packs", Icons.Outlined.Texture, 1),
    SHADERS("shaders", "Shaders", Icons.Outlined.AutoAwesome, 1),
    ADDONS("addons", "Bedrock add-ons", Icons.Outlined.Widgets, 1),
    FILES("files", "File manager", Icons.Outlined.Folder, 2),
    PROFILES("profiles", "Profiles", Icons.Outlined.People, 2),
    SETTINGS("settings", "Settings", Icons.Outlined.Settings, 2),
    DIAGNOSTICS("diagnostics", "Diagnostics", Icons.Outlined.Analytics, 2),
    LOGS("logs", "Error log", Icons.Outlined.BugReport, 2);

    companion object {
        fun of(route: String?) = entries.firstOrNull { route?.startsWith(it.route) == true }
        const val PROFILE_EDIT = "profile_edit"
    }
}
