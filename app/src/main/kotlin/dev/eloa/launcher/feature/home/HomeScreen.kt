package dev.eloa.launcher.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.data.settings.Edition
import dev.eloa.launcher.core.designsystem.component.LaunchButton
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.MetricTile
import dev.eloa.launcher.core.designsystem.component.SectionHeader
import dev.eloa.launcher.core.designsystem.component.SkeletonList
import dev.eloa.launcher.core.designsystem.component.StatusChip
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.core.designsystem.component.UsageBar
import dev.eloa.launcher.core.designsystem.theme.LocalLauncherExtras
import dev.eloa.launcher.feature.common.LaunchResultDialog
import dev.eloa.launcher.feature.common.ProfileAvatar
import dev.eloa.launcher.feature.common.SimpleMenu
import dev.eloa.launcher.feature.common.formatDate
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
        item { HeroCard(s, launching, vm, onNavigate) }
        if (s.loading) { item { SkeletonList(3) }; return@LazyColumn }
        item { StatusGrid(s, onNavigate) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { onNavigate(Destination.SETTINGS) }, label = { Text("Settings") }, leadingIcon = { Icon(Icons.Outlined.Settings, null) })
                AssistChip(onClick = { onNavigate(Destination.VERSIONS) }, label = { Text("Versions") }, leadingIcon = { Icon(Icons.Outlined.Layers, null) })
                AssistChip(onClick = { onNavigate(Destination.DIAGNOSTICS) }, label = { Text("Diagnose") }, leadingIcon = { Icon(Icons.Outlined.Analytics, null) })
            }
        }
        item { SectionHeader("Recently launched") }
        if (s.recent.isEmpty()) item { Text("Nothing launched yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(s.recent, key = { it.id }) { h ->
            LauncherCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(h.title, style = MaterialTheme.typography.titleSmall)
                        Text("${if (h.edition == "JAVA") "Java" else "Bedrock"} · ${h.subtitle} · ${formatDate(h.launchedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        h.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    StatusChip(if (h.success) "Started" else "Blocked", if (h.success) StatusLevel.OK else StatusLevel.WARNING)
                }
            }
        }
        item { SectionHeader("Latest add-ons", action = { OutlinedButton(onClick = { onNavigate(Destination.ADDONS) }) { Text("All") } }) }
        if (s.recentAddons.isEmpty()) item { Text("No add-ons imported yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(s.recentAddons, key = { "a" + it.id }) { a ->
            LauncherCard(Modifier.fillMaxWidth(), onClick = { onNavigate(Destination.ADDONS) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Widgets, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${a.category} · ${formatDate(a.importedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (a.hasErrors) StatusChip("Issues", StatusLevel.WARNING)
                }
            }
        }
    }
}

@Composable
private fun HeroCard(s: HomeState, launching: Boolean, vm: HomeViewModel, onNavigate: (Destination) -> Unit) {
    val x = LocalLauncherExtras.current
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(x.heroGradient)).padding(20.dp)) {
        Column {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(Edition.JAVA to "Java Edition", Edition.BEDROCK to "Bedrock Edition").forEachIndexed { i, (e, label) ->
                    SegmentedButton(selected = s.edition == e, onClick = { vm.selectEdition(e) }, shape = SegmentedButtonDefaults.itemShape(i, 2),
                        modifier = Modifier.testTag("edition_${e.name}")) { Text(label) }
                }
            }
            Spacer(Modifier.height(20.dp))
            var menu by remember { mutableStateOf(false) }
            Box {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (s.edition == Edition.JAVA) {
                        val p = s.selectedProfile
                        if (p != null) ProfileAvatar(p.iconKey, p.coverKey, 56.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p?.name ?: "No profile", style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(p?.let { "Minecraft ${it.minecraftVersion} · ${it.loader.lowercase().replaceFirstChar { c -> c.uppercase() }}${it.loaderVersion?.let { v -> " $v" } ?: ""}" } ?: "Create a profile to play Java Edition",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            p?.let { Text("Last played: ${formatDate(it.lastPlayedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    } else {
                        val b = s.selectedBedrock
                        Column(Modifier.weight(1f)) {
                            Text(b?.displayName ?: "Minecraft Bedrock", style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(b?.let { "${it.versionName} · ${it.abis.ifEmpty { "no native libs" }}" } ?: "No APK imported", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Installed on device: ${s.installedBedrockVersion ?: "none"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.testTag("version_picker")) { Text("Change"); Icon(Icons.Outlined.ArrowDropDown, null) }
                }
                if (s.edition == Edition.JAVA) SimpleMenu(menu, { menu = false }, s.profiles, { "${it.name} (${it.minecraftVersion})" }) { vm.selectProfile(it.id) }
                else SimpleMenu(menu, { menu = false }, s.bedrockVersions, { it.displayName }) { vm.selectBedrock(it.id) }
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LaunchButton(if (launching) "CHECKING…" else "PLAY", vm::launch, Modifier.weight(1f), enabled = !launching, icon = Icons.Outlined.PlayArrow)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AccountCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(if (s.edition == Edition.JAVA) (s.account?.username?.let { "Signed in as $it" } ?: "Not signed in — Settings → Accounts") else "Bedrock uses the in-game account",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (s.edition == Edition.JAVA && s.profiles.isEmpty()) OutlinedButton(onClick = { onNavigate(Destination.PROFILES) }) { Text("New profile") }
            }
        }
    }
}

@Composable
private fun StatusGrid(s: HomeState, onNavigate: (Destination) -> Unit) {
    val filesStatus = when (s.edition) {
        Edition.JAVA -> when (s.selectedProfile?.state) {
            InstallState.INSTALLED -> "Installed" to StatusLevel.OK
            InstallState.INSTALLING, InstallState.QUEUED -> "Installing" to StatusLevel.INFO
            InstallState.FAILED -> "Failed" to StatusLevel.ERROR
            InstallState.NEEDS_PROCESSORS -> "Needs runtime" to StatusLevel.WARNING
            else -> "Not installed" to StatusLevel.WARNING
        }
        Edition.BEDROCK -> when {
            s.selectedBedrock == null -> "No APK" to StatusLevel.WARNING
            !s.selectedBedrock.integrityOk -> "Damaged" to StatusLevel.ERROR
            else -> "Verified" to StatusLevel.OK
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile("Game files", filesStatus.first, Icons.Outlined.VerifiedUser, Modifier.weight(1f), filesStatus.second)
            val sys = s.system
            MetricTile("Free RAM", sys?.let { "${it.availRamMb} / ${it.totalRamMb} MB" } ?: "…", Icons.Outlined.Memory, Modifier.weight(1f),
                sys?.let { if (it.availRamMb < 700) StatusLevel.WARNING else null })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile("Launcher data", s.system?.let { FileOps.formatSize(it.launcherBytes) } ?: "…", Icons.Outlined.SdStorage, Modifier.weight(1f))
            MetricTile("Profiles · APKs", "${s.profiles.size} · ${s.bedrockVersions.size}", Icons.Outlined.Inventory2, Modifier.weight(1f))
        }
        s.system?.let { sys ->
            LauncherCard(Modifier.fillMaxWidth(), onClick = { onNavigate(Destination.FILES) }) {
                Text("Device storage: ${FileOps.formatSize(sys.freeStorage)} free of ${FileOps.formatSize(sys.totalStorage)}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                UsageBar(1f - sys.freeStorage.toFloat() / sys.totalStorage.coerceAtLeast(1))
            }
        }
        if (s.edition == Edition.JAVA) Text("Java profile memory: ${s.selectedProfile?.maxMemoryMb ?: "—"} MB", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
    }
}
