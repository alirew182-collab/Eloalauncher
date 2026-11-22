package dev.eloa.launcher.feature.versions

import dev.eloa.launcher.feature.common.str
import androidx.compose.ui.res.stringResource
import dev.eloa.launcher.R
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

private enum class ChannelFilter(@param:androidx.annotation.StringRes val label: Int, val channels: Set<VersionChannel>) {
    RELEASE(R.string.releases, setOf(VersionChannel.RELEASE)),
    SNAPSHOT(R.string.snapshots, setOf(VersionChannel.SNAPSHOT)),
    OLD(R.string.old_beta_alpha, setOf(VersionChannel.OLD_BETA, VersionChannel.OLD_ALPHA)),
    ALL(R.string.particles_all, VersionChannel.entries.toSet()),
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
            Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.available)) })
            Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.installed_1_s, installed.size)) })
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("versions")) {
            if (tab == 0) {
                m.error?.let { e -> item { ErrorBanner(if (m.fromCache) stringResource(R.string.offline_showing_cached_list_1_s, e) else stringResource(R.string.cannot_load_versions_1_s, e), onRetry = { vm.refresh() }) } }
                item { SearchField(query, { query = it }, placeholder = stringResource(R.string.search_versions)) }
                item { ChipRow(ChannelFilter.entries, filter, { str(it.label) }, { filter = it }) }
                val manifest = m.manifest
                if (manifest == null) {
                    item { if (m.loading || m.error == null) SkeletonList(6) else EmptyState(Icons.Outlined.CloudOff, stringResource(R.string.no_version_list), stringResource(R.string.connect_to_the_internet_and_retry), actionLabel = stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_retry), onAction = { vm.refresh() }) }
                } else {
                    val list = manifest.versions.filter { it.channel in filter.channels && (query.isBlank() || it.id.contains(query, true)) }
                    item { Text(stringResource(R.string.latest_release_1_s_snapshot_2, manifest.latest.release, manifest.latest.snapshot, list.size), style = MaterialTheme.typography.labelMedium) }
                    items(list, key = { it.id }) { v ->
                        LauncherCard(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(v.id, style = MaterialTheme.typography.titleSmall)
                                    Text("${v.type} · ${v.releaseTime.take(10)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (installed.any { it.id == v.id }) StatusChip(stringResource(R.string.installed), StatusLevel.OK)
                                TextButton(onClick = { confirm = v }) { Text(stringResource(R.string.new_profile)) }
                            }
                        }
                    }
                }
            } else {
                if (installed.isEmpty()) item { EmptyState(Icons.Outlined.Layers, stringResource(R.string.nothing_installed), stringResource(R.string.versions_are_installed_when_you_install)) }
                items(installed, key = { it.id }) { v ->
                    LauncherCard(Modifier.fillMaxWidth()) {
                        Text(v.id, style = MaterialTheme.typography.titleSmall)
                        Text(if (v.isLoaderProfile) stringResource(R.string.mod_loader_profile) else stringResource(R.string.vanilla), style = MaterialTheme.typography.bodySmall)
                        Text(if (v.usedBy.isEmpty()) stringResource(R.string.not_used_by_any_profile) else stringResource(R.string.used_by_1_s, v.usedBy.joinToString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { vm.verify(v.id) }, enabled = verifying == null) { Text(if (verifying == v.id) stringResource(R.string.verifying) else stringResource(R.string.verify)) }
                            OutlinedButton(onClick = { delete = v }, enabled = v.usedBy.isEmpty()) { Text(stringResource(R.string.action_delete)) }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { v -> ConfirmDialog(stringResource(R.string.minecraft_1_s, v.id), stringResource(R.string.create_a_new_vanilla_profile_with, v.id), stringResource(R.string.create_install), { vm.createProfile(v.id); confirm = null }, { confirm = null }) }
    delete?.let { v -> ConfirmDialog(stringResource(R.string.delete_1_s, v.id), stringResource(R.string.the_version_files_not_libraries_assets), stringResource(R.string.action_delete), { vm.delete(v); delete = null }, { delete = null }, destructive = true) }
}
