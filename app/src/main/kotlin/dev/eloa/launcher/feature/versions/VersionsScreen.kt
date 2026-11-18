package dev.eloa.launcher.feature.versions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eloa.launcher.core.designsystem.component.ChipRow
import dev.eloa.launcher.core.designsystem.component.ConfirmDialog
import dev.eloa.launcher.core.designsystem.component.EmptyState
import dev.eloa.launcher.core.designsystem.component.ErrorBanner
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.SearchField
import dev.eloa.launcher.core.designsystem.component.SkeletonList
import dev.eloa.launcher.core.designsystem.component.StatusChip
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.domain.java.meta.VersionChannel
import dev.eloa.launcher.domain.java.meta.VersionManifest

private enum class ChannelFilter(val label: String, val channels: Set<VersionChannel>) {
    RELEASE("Releases", setOf(VersionChannel.RELEASE)),
    SNAPSHOT("Snapshots", setOf(VersionChannel.SNAPSHOT)),
    OLD("Old (beta/alpha)", setOf(VersionChannel.OLD_BETA, VersionChannel.OLD_ALPHA)),
    ALL("All", VersionChannel.entries.toSet()),
}

@Composable
fun VersionsScreen(padding: PaddingValues, vm: VersionsViewModel = hiltViewModel()) {
    val m by vm.manifest.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val verifying by vm.verifying.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf(ChannelFilter.RELEASE) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<VersionManifest.Entry?>(null) }
    var delete by remember { mutableStateOf<InstalledVersion?>(null) }
    Column(Modifier.padding(padding)) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Available") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Installed (${installed.size})") })
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("versions")) {
            if (tab == 0) {
                m.error?.let { e -> item { ErrorBanner(if (m.fromCache) "Offline — showing cached list ($e)" else "Cannot load versions: $e", onRetry = { vm.refresh() }) } }
                item { SearchField(query, { query = it }, placeholder = "Search versions") }
                item { ChipRow(ChannelFilter.entries, filter, { it.label }, { filter = it }) }
                val manifest = m.manifest
                if (manifest == null) {
                    item { if (m.loading || m.error == null) SkeletonList(6) else EmptyState(Icons.Outlined.CloudOff, "No version list", "Connect to the internet and retry.", actionLabel = "Retry", onAction = { vm.refresh() }) }
                } else {
                    val list = manifest.versions.filter { it.channel in filter.channels && (query.isBlank() || it.id.contains(query, true)) }
                    item { Text("Latest release ${manifest.latest.release} · snapshot ${manifest.latest.snapshot} · ${list.size} shown", style = MaterialTheme.typography.labelMedium) }
                    items(list, key = { it.id }) { v ->
                        LauncherCard(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(v.id, style = MaterialTheme.typography.titleSmall)
                                    Text("${v.type} · ${v.releaseTime.take(10)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (installed.any { it.id == v.id }) StatusChip("Installed", StatusLevel.OK)
                                TextButton(onClick = { confirm = v }) { Text("New profile") }
                            }
                        }
                    }
                }
            } else {
                if (installed.isEmpty()) item { EmptyState(Icons.Outlined.Layers, "Nothing installed", "Versions are installed when you install a profile.") }
                items(installed, key = { it.id }) { v ->
                    LauncherCard(Modifier.fillMaxWidth()) {
                        Text(v.id, style = MaterialTheme.typography.titleSmall)
                        Text(if (v.isLoaderProfile) "Mod loader profile" else "Vanilla", style = MaterialTheme.typography.bodySmall)
                        Text(if (v.usedBy.isEmpty()) "Not used by any profile" else "Used by: ${v.usedBy.joinToString()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { vm.verify(v.id) }, enabled = verifying == null) { Text(if (verifying == v.id) "Verifying…" else "Verify") }
                            OutlinedButton(onClick = { delete = v }, enabled = v.usedBy.isEmpty()) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { v -> ConfirmDialog("Minecraft ${v.id}", "Create a new vanilla profile with Minecraft ${v.id} and download its files from Mojang? You can add a mod loader in the profile editor.", "Create & install", { vm.createProfile(v.id); confirm = null }, { confirm = null }) }
    delete?.let { v -> ConfirmDialog("Delete ${v.id}?", "The version files (not libraries/assets shared with others) will be deleted. Profiles are not affected.", "Delete", { vm.delete(v); delete = null }, { delete = null }, destructive = true) }
}
