package dev.eloa.launcher.feature.profiles

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eloa.launcher.core.designsystem.component.ErrorBanner
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.SkeletonList
import dev.eloa.launcher.core.designsystem.component.StatusChip
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.domain.java.loader.ModLoaderType
import dev.eloa.launcher.feature.common.ProfileArt
import dev.eloa.launcher.feature.common.ProfileAvatar
import dev.eloa.launcher.feature.common.SimpleMenu
import kotlin.math.roundToInt

@Composable
fun ProfileEditorScreen(padding: PaddingValues, onDone: () -> Unit, vm: ProfileEditorViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.saved) { if (s.saved) onDone() }
    if (!s.loaded) { SkeletonList(6, Modifier.padding(padding).padding(16.dp)); return }
    val c = s.config
    Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).testTag("profile_editor"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(c.iconKey, c.coverKey, 64.dp)
            Spacer(Modifier.width(14.dp))
            Text(if (s.isNew) "New profile" else "Edit profile", style = MaterialTheme.typography.headlineSmall)
        }
        OutlinedTextField(c.name, { vm.update(c.copy(name = it.take(60))) }, label = { Text("Name") }, singleLine = true, isError = c.name.isBlank(), modifier = Modifier.fillMaxWidth().testTag("profile_name"))
        OutlinedTextField(c.description, { vm.update(c.copy(description = it.take(300))) }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 2)

        Text("Icon", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileArt.icons.keys.forEach { key ->
                Box(Modifier.clip(RoundedCornerShape(14.dp)).then(if (key == c.iconKey) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)) else Modifier).clickable { vm.update(c.copy(iconKey = key)) }.padding(3.dp)) {
                    ProfileAvatar(key, c.coverKey, 44.dp)
                }
            }
        }
        Text("Cover", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileArt.covers.keys.forEach { key -> FilterChip(key == c.coverKey, { vm.update(c.copy(coverKey = key)) }, label = { Text(key.replaceFirstChar { it.uppercase() }) }) }
        }

        LauncherCard(Modifier.fillMaxWidth()) {
            Text("Game", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            var vOpen by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Minecraft ${c.minecraftVersion.ifEmpty { "—" }}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Box {
                    OutlinedButton(onClick = { vOpen = true }, enabled = s.mcVersions.isNotEmpty(), modifier = Modifier.testTag("mc_version")) { Text("Version"); Icon(Icons.Outlined.ArrowDropDown, null) }
                    SimpleMenu(vOpen, { vOpen = false }, s.mcVersions.take(300), { it }) { vm.update(c.copy(minecraftVersion = it)) }
                }
            }
            if (s.mcVersions.isEmpty()) Text("Version list unavailable (offline?). Connect to the internet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            FilterChip(s.showSnapshots, vm::toggleSnapshots, label = { Text("Show snapshots") })
            Spacer(Modifier.height(8.dp))
            Text("Mod loader", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModLoaderType.entries.forEach { t -> FilterChip(c.loader == t.name, { vm.update(c.copy(loader = t.name)) }, label = { Text(t.displayName) }, modifier = Modifier.testTag("loader_${t.name}")) }
            }
            if (c.loader != ModLoaderType.VANILLA.name) {
                var lOpen by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Loader version: ${c.loaderVersion ?: "latest stable (at install)"}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (s.loaderLoading) CircularProgressIndicator(Modifier.padding(8.dp).width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    Box {
                        OutlinedButton(onClick = { lOpen = true }, enabled = s.loaderVersions.isNotEmpty()) { Text("Choose") }
                        SimpleMenu(lOpen, { lOpen = false }, s.loaderVersions.take(200), { it.version + if (it.stable) "" else " (beta)" }) { vm.update(c.copy(loaderVersion = it.version)) }
                    }
                }
                s.loaderError?.let { ErrorBanner(it, onRetry = vm::loadLoaderVersions) }
            }
        }

        LauncherCard(Modifier.fillMaxWidth()) {
            Text("Memory", style = MaterialTheme.typography.titleMedium)
            Text("Device RAM ${s.totalRamMb} MB · safe maximum ${s.safeMaxMb} MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val maxSlider = (s.totalRamMb - 512).coerceAtLeast(1024).toFloat()
            Text("Maximum: ${c.maxMemoryMb} MB")
            Slider(c.maxMemoryMb.toFloat(), { v -> val mb = (v / 128).roundToInt() * 128; vm.update(c.copy(maxMemoryMb = mb.coerceAtLeast(512), minMemoryMb = minOf(c.minMemoryMb, mb.coerceAtLeast(512)))) },
                valueRange = 512f..maxSlider, modifier = Modifier.testTag("memory_slider"))
            Text("Minimum: ${c.minMemoryMb} MB")
            Slider(c.minMemoryMb.toFloat(), { v -> vm.update(c.copy(minMemoryMb = ((v / 128).roundToInt() * 128).coerceIn(256, c.maxMemoryMb))) }, valueRange = 256f..c.maxMemoryMb.toFloat().coerceAtLeast(257f))
            vm.memoryWarning()?.let { StatusChip(it, StatusLevel.WARNING) }
        }

        LauncherCard(Modifier.fillMaxWidth()) {
            Text("Advanced", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text("Graphics preset", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ULTRA_LOW", "LOW", "BALANCED", "HIGH", "ULTRA").forEach { g -> FilterChip(c.graphicsPreset == g, { vm.update(c.copy(graphicsPreset = g)) }, label = { Text(g.lowercase().replace('_', ' ')) }) }
            }
            OutlinedTextField(c.jvmArgs, { vm.update(c.copy(jvmArgs = it.take(1000))) }, label = { Text("Extra JVM arguments") }, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Agents and boot classpath overrides are rejected for safety.") })
            OutlinedTextField(c.gameArgs, { vm.update(c.copy(gameArgs = it.take(1000))) }, label = { Text("Extra game arguments") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(c.width?.toString() ?: "", { vm.update(c.copy(width = it.filter(Char::isDigit).take(5).toIntOrNull())) }, label = { Text("Width") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(c.height?.toString() ?: "", { vm.update(c.copy(height = it.filter(Char::isDigit).take(5).toIntOrNull())) }, label = { Text("Height") }, modifier = Modifier.weight(1f), singleLine = true)
            }
        }
        val error = vm.validate()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Cancel") }
            FilledTonalButton(onClick = { vm.save(false) }, enabled = error == null, modifier = Modifier.weight(1f).testTag("profile_save")) { Text("Save") }
            Button(onClick = { vm.save(true) }, enabled = error == null, modifier = Modifier.weight(1.3f).testTag("profile_save_install")) { Text("Save & install") }
        }
        Spacer(Modifier.height(24.dp))
    }
}
