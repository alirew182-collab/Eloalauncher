package dev.eloa.launcher.feature.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Agriculture
import androidx.compose.material.icons.outlined.Castle
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.Forest
import androidx.compose.material.icons.outlined.Grass
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Rocket
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Water
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/** Built-in profile icons and cover gradients (no external images required). */
object ProfileArt {
    val icons: Map<String, ImageVector> = linkedMapOf(
        "grass" to Icons.Outlined.Grass, "diamond" to Icons.Outlined.Diamond, "castle" to Icons.Outlined.Castle,
        "sword" to Icons.Outlined.Shield, "fire" to Icons.Outlined.LocalFireDepartment, "farm" to Icons.Outlined.Agriculture,
        "rocket" to Icons.Outlined.Rocket, "pets" to Icons.Outlined.Pets, "water" to Icons.Outlined.Water, "forest" to Icons.Outlined.Forest,
    )
    val covers: Map<String, List<Color>> = linkedMapOf(
        "forest" to listOf(Color(0xFF1B5E20), Color(0xFF43A047)),
        "ocean" to listOf(Color(0xFF01579B), Color(0xFF26C6DA)),
        "nether" to listOf(Color(0xFF4A0E0E), Color(0xFFE53935)),
        "end" to listOf(Color(0xFF1A1033), Color(0xFF9C6BFF)),
        "desert" to listOf(Color(0xFF8D6E00), Color(0xFFFFCA28)),
        "night" to listOf(Color(0xFF0D1B2A), Color(0xFF415A77)),
    )
    fun icon(key: String) = icons[key] ?: Icons.Outlined.Grass
    fun cover(key: String) = covers[key] ?: covers.getValue("forest")
}

@Composable
fun ProfileAvatar(iconKey: String, coverKey: String, size: Dp = 48.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(Brush.linearGradient(ProfileArt.cover(coverKey))), contentAlignment = Alignment.Center) {
        Icon(ProfileArt.icon(iconKey), null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

fun formatDate(ms: Long?): String = if (ms == null || ms <= 0) "—" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

@Composable
fun <T> SimpleMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<T>, label: (T) -> String, onPick: (T) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        items.forEach { item -> DropdownMenuItem(text = { Text(label(item)) }, onClick = { onDismiss(); onPick(item) }) }
    }
}
