package dev.eloa.launcher.feature.profiles

import dev.eloa.launcher.feature.common.str
import androidx.compose.ui.res.stringResource
import dev.eloa.launcher.R
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
import dev.eloa.launcher.feature.common.label
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
            Text(if (s.isNew) stringResource(R.string.new_profile) else stringResource(R.string.edit_profile), style = MaterialTheme.typography.headlineSmall)
        }
        OutlinedTextField(c.name, { vm.update(c.copy(name = it.take(60))) }, label = { Text(stringResource(R.string.sort_name)) }, singleLine = true, isError = c.name.isBlank(), modifier = Modifier.fillMaxWidth().testTag("profile_name"))
        OutlinedTextField(c.description, { vm.update(c.copy(description = it.take(300))) }, label = { Text(stringResource(R.string.description)) }, modifier = Modifier.fillMaxWidth(), minLines = 2)

        Text(stringResource(R.string.icon), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileArt.icons.keys.forEach { key ->
                Box(Modifier.clip(RoundedCornerShape(14.dp)).then(if (key == c.iconKey) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)) else Modifier).clickable { vm.update(c.copy(iconKey = key)) }.padding(3.dp)) {
                    ProfileAvatar(key, c.coverKey, 44.dp)
                }
            }
        }
        Text(stringResource(R.string.cover), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileArt.covers.keys.forEach { key -> FilterChip(key == c.coverKey, { vm.update(c.copy(coverKey = key)) }, label = { Text(key.replaceFirstChar { it.uppercase() }) }) }
        }

        LauncherCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.game), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            var vOpen by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.minecraft_1_s, c.minecraftVersion.ifEmpty { "—" }), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Box {
                    OutlinedButton(onClick = { vOpen = true }, enabled = s.mcVersions.isNotEmpty(), modifier = Modifier.testTag("mc_version")) { Text(stringResource(R.string.version)); Icon(Icons.Outlined.ArrowDropDown, null) }
                    SimpleMenu(vOpen, { vOpen = false }, s.mcVersions.take(300), { it }) { vm.update(c.copy(minecraftVersion = it)) }
                }
            }
            if (s.mcVersions.isEmpty()) Text(stringResource(R.string.version_list_unavailable_offline_connect_to), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            FilterChip(s.showSnapshots, vm::toggleSnapshots, label = { Text(stringResource(R.string.show_snapshots)) })
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.mod_loader), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModLoaderType.entries.forEach { t -> FilterChip(c.loader == t.name, { vm.update(c.copy(loader = t.name)) }, label = { Text(t.displayName) }, modifier = Modifier.testTag("loader_${t.name}")) }
            }
            if (c.loader != ModLoaderType.VANILLA.name) {
                var lOpen by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.loader_version_1_s, c.loaderVersion ?: stringResource(R.string.latest_stable_at_install)), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (s.loaderLoading) CircularProgressIndicator(Modifier.padding(8.dp).width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    Box {
                        OutlinedButton(onClick = { lOpen = true }, enabled = s.loaderVersions.isNotEmpty()) { Text(stringResource(R.string.choose)) }
                        SimpleMenu(lOpen, { lOpen = false }, s.loaderVersions.take(200), { it.version + if (it.stable) "" else str(R.string.beta) }) { vm.update(c.copy(loaderVersion = it.version)) }
                    }
                }
                s.loaderError?.let { ErrorBanner(it, onRetry = vm::loadLoaderVersions) }
            }
        }

        LauncherCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.memory), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.device_ram_1_s_mb_safe, s.totalRamMb, s.safeMaxMb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val maxSlider = (s.totalRamMb - 512).coerceAtLeast(1024).toFloat()
            Text(stringResource(R.string.maximum_1_s_mb, c.maxMemoryMb))
            Slider(c.maxMemoryMb.toFloat(), { v -> val mb = (v / 128).roundToInt() * 128; vm.update(c.copy(maxMemoryMb = mb.coerceAtLeast(512), minMemoryMb = minOf(c.minMemoryMb, mb.coerceAtLeast(512)))) },
                valueRange = 512f..maxSlider, modifier = Modifier.testTag("memory_slider"))
            Text(stringResource(R.string.minimum_1_s_mb, c.minMemoryMb))
            Slider(c.minMemoryMb.toFloat(), { v -> vm.update(c.copy(minMemoryMb = ((v / 128).roundToInt() * 128).coerceIn(256, c.maxMemoryMb))) }, valueRange = 256f..c.maxMemoryMb.toFloat().coerceAtLeast(257f))
            when (vm.memoryWarning()) {
                dev.eloa.launcher.core.data.performance.MemoryWarning.ABOVE_SAFE -> StatusChip(stringResource(R.string.above_the_safe_limit_for_this), StatusLevel.WARNING)
                dev.eloa.launcher.core.data.performance.MemoryWarning.TOO_LOW -> StatusChip(stringResource(R.string.less_than_512_mb_is_not), StatusLevel.WARNING)
                null -> Unit
            }
        }

        LauncherCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.advanced), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.performance_mode), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dev.eloa.launcher.core.data.settings.PerformancePreset.entries.forEach { g ->
                    FilterChip(dev.eloa.launcher.core.data.settings.PerformancePreset.parse(c.graphicsPreset) == g, { vm.update(c.copy(graphicsPreset = g.name)) }, label = { Text(g.label()) })
                }
            }
            OutlinedTextField(c.jvmArgs, { vm.update(c.copy(jvmArgs = it.take(1000))) }, label = { Text(stringResource(R.string.extra_jvm_arguments)) }, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(stringResource(R.string.agents_and_boot_classpath_overrides_are)) })
            OutlinedTextField(c.gameArgs, { vm.update(c.copy(gameArgs = it.take(1000))) }, label = { Text(stringResource(R.string.extra_game_arguments)) }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(c.width?.toString() ?: "", { vm.update(c.copy(width = it.filter(Char::isDigit).take(5).toIntOrNull())) }, label = { Text(stringResource(R.string.width)) }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(c.height?.toString() ?: "", { vm.update(c.copy(height = it.filter(Char::isDigit).take(5).toIntOrNull())) }, label = { Text(stringResource(R.string.height)) }, modifier = Modifier.weight(1f), singleLine = true)
            }
        }
        val error = vm.validate()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text(stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_cancel)) }
            FilledTonalButton(onClick = { vm.save(false) }, enabled = error == null, modifier = Modifier.weight(1f).testTag("profile_save")) { Text(stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_save)) }
            Button(onClick = { vm.save(true) }, enabled = error == null, modifier = Modifier.weight(1.3f).testTag("profile_save_install")) { Text(stringResource(R.string.save_install)) }
        }
        Spacer(Modifier.height(24.dp))
    }
}
