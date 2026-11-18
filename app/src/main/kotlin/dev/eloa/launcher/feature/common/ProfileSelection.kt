package dev.eloa.launcher.feature.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.eloa.launcher.core.data.db.JavaProfileEntity
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.feature.java.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

data class ProfileSelection(val profiles: List<JavaProfileEntity>, val selected: JavaProfileEntity?)

/** The profile chosen for content screens (mods, packs, shaders) — shared with the Home screen. */
@Singleton
class SelectedProfile @Inject constructor(private val profiles: ProfileRepository, private val settings: SettingsRepository) {
    val selection: Flow<ProfileSelection> = combine(profiles.profiles, settings.settings) { list, s ->
        ProfileSelection(list, list.firstOrNull { it.id == s.selectedProfileId } ?: list.firstOrNull())
    }
    suspend fun select(id: String) = settings.update { it.copy(selectedProfileId = id) }
}

@Composable
fun ProfileSelectorBar(selection: ProfileSelection, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        selection.selected?.let { ProfileAvatar(it.iconKey, it.coverKey, 40.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(selection.selected?.name ?: "No profile", style = MaterialTheme.typography.titleMedium)
            selection.selected?.let { Text("${it.minecraftVersion} · ${it.loader}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Box {
            OutlinedButton(onClick = { open = true }, enabled = selection.profiles.size > 1, modifier = Modifier.testTag("profile_selector")) { Text("Profile"); Icon(Icons.Outlined.ArrowDropDown, null) }
            SimpleMenu(open, { open = false }, selection.profiles, { "${it.name} (${it.minecraftVersion})" }) { onSelect(it.id) }
        }
    }
}
