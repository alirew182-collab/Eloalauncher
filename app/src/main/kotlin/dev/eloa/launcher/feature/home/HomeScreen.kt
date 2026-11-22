package dev.eloa.launcher.feature.home

import androidx.compose.ui.res.stringResource
import dev.eloa.launcher.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.data.settings.Edition
import dev.eloa.launcher.core.data.settings.HomeLayout
import dev.eloa.launcher.core.designsystem.component.LaunchButton
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.MetricTile
import dev.eloa.launcher.core.designsystem.component.SectionHeader
import dev.eloa.launcher.core.designsystem.component.SkeletonList
import dev.eloa.launcher.core.designsystem.component.StatusChip
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.core.designsystem.theme.LocalLauncherExtras
import dev.eloa.launcher.feature.common.LaunchResultDialog
import dev.eloa.launcher.feature.common.ProfileAvatar
import dev.eloa.launcher.feature.common.SimpleMenu
import dev.eloa.launcher.feature.common.formatDate
import dev.eloa.launcher.feature.common.label
import dev.eloa.launcher.feature.common.blockerText
import dev.eloa.launcher.feature.java.InstallState
import dev.eloa.launcher.feature.java.state
import dev.eloa.launcher.nav.Destination

@Composable
fun HomeScreen(padding: PaddingValues, onNavigate: (Destination) -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val launching by vm.launching.collectAsStateWithLifecycle()
    val outcome by vm.outcome.collectAsStateWithLifecycle()
    LaunchResultDialog(outcome, vm::dismissOutcome)
    LazyColumn(Modifier.padding(padding).testTag("home"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            BoxWithConstraints {
                if (maxWidth >= 720.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        JavaCard(s, launching == Edition.JAVA, vm, onNavigate, Modifier.weight(1f))
                        BedrockCard(s, launching == Edition.BEDROCK, vm, onNavigate, Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        JavaCard(s, launching == Edition.JAVA, vm, onNavigate, Modifier.fillMaxWidth())
                        BedrockCard(s, launching == Edition.BEDROCK, vm, onNavigate, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (s.loading) { item { SkeletonList(3) }; return@LazyColumn }
        if (s.layout == HomeLayout.MINIMAL) return@LazyColumn
        if (s.layout == HomeLayout.DASHBOARD) item { Dashboard(s, onNavigate) }
        if (s.profiles.size > 1) {
            item { SectionHeader(stringResource(R.string.quick_launch)) }
            items(s.profiles.take(4), key = { "q" + it.id }) { p ->
                LauncherCard(Modifier.fillMaxWidth(), onClick = { vm.selectProfile(p.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProfileAvatar(p.iconKey, p.coverKey, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.minecraftVersion} · ${p.loader.lowercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = { vm.launchJava(p.id) }, enabled = launching == null) { Text(stringResource(R.string.play)) }
                    }
                }
            }
        }
        item { SectionHeader(stringResource(R.string.recently_launched)) }
        if (s.recent.isEmpty()) item { Text(stringResource(R.string.nothing_launched_yet), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(s.recent, key = { it.id }) { h ->
            LauncherCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (h.edition == "JAVA") Icons.Outlined.Coffee else Icons.Outlined.Smartphone, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.title, style = MaterialTheme.typography.titleSmall)
                        Text("${h.subtitle} · ${formatDate(h.launchedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        h.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    if (h.success) StatusChip(stringResource(R.string.started), StatusLevel.OK) else StatusChip(stringResource(R.string.blocked), StatusLevel.WARNING)
                }
            }
        }
    }
}

@Composable
private fun EditionCardFrame(title: String, icon: ImageVector, modifier: Modifier, tag: String, content: @Composable () -> Unit) {
    val x = LocalLauncherExtras.current
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).border(1.dp, x.hairline, RoundedCornerShape(20.dp)).padding(20.dp).testTag(tag)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun JavaCard(s: HomeState, launching: Boolean, vm: HomeViewModel, onNavigate: (Destination) -> Unit, modifier: Modifier) {
    EditionCardFrame(stringResource(R.string.nav_java), Icons.Outlined.Coffee, modifier, "card_java") {
        val p = s.selectedProfile
        var menu by remember { mutableStateOf(false) }
        Box {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (p != null) { ProfileAvatar(p.iconKey, p.coverKey, 48.dp); Spacer(Modifier.width(12.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(p?.name ?: stringResource(R.string.no_profile), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (p != null) Text(stringResource(R.string.minecraft_1_s_2_s, p.minecraftVersion, p.loader.lowercase()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else Text(stringResource(R.string.create_a_profile_to_play_java), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (s.profiles.size > 1) OutlinedButton(onClick = { menu = true }, modifier = Modifier.testTag("version_picker")) { Icon(Icons.Outlined.ArrowDropDown, null) }
            }
            SimpleMenu(menu, { menu = false }, s.profiles, { "${it.name} (${it.minecraftVersion})" }) { vm.selectProfile(it.id) }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (p?.state) {
                InstallState.INSTALLED -> StatusChip(stringResource(R.string.installed), StatusLevel.OK)
                InstallState.INSTALLING, InstallState.QUEUED -> StatusChip(stringResource(R.string.installing), StatusLevel.INFO)
                InstallState.FAILED -> StatusChip(stringResource(R.string.install_failed), StatusLevel.ERROR)
                InstallState.NEEDS_PROCESSORS -> StatusChip(stringResource(R.string.loader_setup_pending), StatusLevel.WARNING)
                InstallState.NOT_INSTALLED -> StatusChip(stringResource(R.string.not_installed), StatusLevel.WARNING)
                null -> StatusChip(stringResource(R.string.no_profile), StatusLevel.WARNING)
            }
            ModePicker(s, vm)
        }
        Spacer(Modifier.height(14.dp))
        LaunchButton(if (launching) stringResource(R.string.checking) else stringResource(R.string.play), { vm.launchJava() }, Modifier.fillMaxWidth(), enabled = !launching, icon = Icons.Outlined.PlayArrow)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onNavigate(Destination.GAME_SETTINGS) }, modifier = Modifier.weight(1f).testTag("java_settings")) {
                Icon(Icons.Outlined.Settings, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nav_settings), maxLines = 1)
            }
            OutlinedButton(onClick = { onNavigate(if (s.profiles.isEmpty()) Destination.PROFILES else Destination.VERSIONS) }, modifier = Modifier.weight(1f).testTag("java_versions")) {
                Icon(Icons.Outlined.Layers, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nav_versions), maxLines = 1)
            }
        }
    }
}

@Composable
private fun ModePicker(s: HomeState, vm: HomeViewModel) {
    var open by remember { mutableStateOf(false) }
    val current = s.modes.firstOrNull { it.mode == s.mode }
    Box {
        AssistChip(onClick = { open = true }, label = { Text(s.mode.label(), maxLines = 1) }, modifier = Modifier.testTag("mode_picker"),
            leadingIcon = { if (current?.available == false) Icon(Icons.Outlined.WifiOff, null, Modifier.size(16.dp)) else Icon(Icons.Outlined.Wifi, null, Modifier.size(16.dp)) })
        DropdownMenu(open, { open = false }) {
            s.modes.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(m.mode.label())
                            if (!m.available) Text(blockerText(m.blockers.first()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    enabled = m.available, onClick = { vm.selectMode(m.mode); open = false },
                    modifier = Modifier.testTag("mode_${m.mode.name}"),
                )
            }
        }
    }
}

@Composable
private fun BedrockCard(s: HomeState, launching: Boolean, vm: HomeViewModel, onNavigate: (Destination) -> Unit, modifier: Modifier) {
    EditionCardFrame(stringResource(R.string.nav_bedrock), Icons.Outlined.Smartphone, modifier, "card_bedrock") {
        val v = s.bedrock.installedVersion
        Text(if (v != null) stringResource(R.string.minecraft_1_s, v) else stringResource(R.string.not_installed_on_this_device), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.library_1_s_apk_version_s, s.bedrockVersions.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        if (s.bedrock.launchable) StatusChip(stringResource(R.string.installed), StatusLevel.OK) else StatusChip(stringResource(R.string.not_installed), StatusLevel.WARNING)
        Spacer(Modifier.height(14.dp))
        LaunchButton(if (launching) stringResource(R.string.starting) else stringResource(R.string.play), vm::launchBedrock, Modifier.fillMaxWidth(), enabled = !launching, icon = Icons.Outlined.PlayArrow, tag = "bedrock_play")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onNavigate(Destination.BEDROCK) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Settings, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nav_settings), maxLines = 1)
            }
            OutlinedButton(onClick = { onNavigate(Destination.BEDROCK) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Layers, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nav_versions), maxLines = 1)
            }
        }
    }
}

@Composable
private fun Dashboard(s: HomeState, onNavigate: (Destination) -> Unit) {
    val sys = s.system
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(stringResource(R.string.free_ram), sys?.let { stringResource(R.string.s_1_s_2_s_mb, it.availRamMb, it.totalRamMb) } ?: "…", Icons.Outlined.Memory, Modifier.weight(1f),
                sys?.let { if (it.availRamMb < 700) StatusLevel.WARNING else null })
            MetricTile(stringResource(R.string.free_storage), sys?.let { FileOps.formatSize(it.freeStorage) } ?: "…", Icons.Outlined.SdStorage, Modifier.weight(1f),
                sys?.let { if (it.freeStorage < 1_000_000_000L) StatusLevel.WARNING else null })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(stringResource(R.string.nav_profiles), s.profiles.size.toString(), Icons.Outlined.Inventory2, Modifier.weight(1f))
            MetricTile(stringResource(R.string.network), if (sys?.online == true) stringResource(R.string.mode_online) else stringResource(R.string.mode_offline), if (sys?.online == true) Icons.Outlined.Wifi else Icons.Outlined.WifiOff, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { onNavigate(Destination.PERFORMANCE) }, label = { Text(stringResource(R.string.preset_performance)) })
            AssistChip(onClick = { onNavigate(Destination.DIAGNOSTICS) }, label = { Text(stringResource(R.string.diagnose)) })
            AssistChip(onClick = { onNavigate(Destination.DOWNLOADS) }, label = { Text(stringResource(R.string.nav_downloads)) })
        }
        if (s.recent.isEmpty()) Text(stringResource(R.string.play_time_statistics_are_not_available), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
