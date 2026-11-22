package dev.eloa.launcher.feature.javaui

import androidx.compose.ui.res.stringResource
import dev.eloa.launcher.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eloa.launcher.core.designsystem.component.EmptyState
import dev.eloa.launcher.core.designsystem.component.LabeledValue
import dev.eloa.launcher.core.designsystem.component.LaunchButton
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.ProgressPanel
import dev.eloa.launcher.core.designsystem.component.StatusChip
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.domain.java.content.JavaContentType
import dev.eloa.launcher.feature.common.LaunchResultDialog
import dev.eloa.launcher.feature.common.ProfileSelectorBar
import dev.eloa.launcher.feature.common.formatDate
import dev.eloa.launcher.feature.content.ContentScreen
import dev.eloa.launcher.feature.java.InstallState
import dev.eloa.launcher.feature.java.state
import dev.eloa.launcher.nav.Destination

@Composable
fun JavaScreen(padding: PaddingValues, onNavigate: (Destination) -> Unit, vm: JavaViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(stringResource(R.string.overview), stringResource(R.string.nav_worlds), stringResource(R.string.datapacks), stringResource(R.string.configs))
    Column(Modifier.padding(padding)) {
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 16.dp) {
            tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }, modifier = Modifier.testTag("java_tab_$i")) }
        }
        when (tab) {
            0 -> Overview(vm, onNavigate)
            1 -> ContentScreen(JavaContentType.WORLDS, PaddingValues(0.dp))
            2 -> ContentScreen(JavaContentType.DATAPACKS, PaddingValues(0.dp))
            else -> ContentScreen(JavaContentType.CONFIGS, PaddingValues(0.dp))
        }
    }
}

@Composable
private fun Overview(vm: JavaViewModel, onNavigate: (Destination) -> Unit) {
    val selection by vm.selection.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val runtimes by vm.runtimes.collectAsStateWithLifecycle()
    val outcome by vm.outcome.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchResultDialog(outcome, vm::dismissOutcome)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val p = selection.selected
        if (p == null) {
            EmptyState(Icons.Outlined.Coffee, stringResource(R.string.no_java_edition_profile), stringResource(R.string.profiles_keep_their_own_version_mod),
                actionLabel = stringResource(R.string.create_profile), onAction = { onNavigate(Destination.PROFILES) })
            return@Column
        }
        ProfileSelectorBar(selection, vm::select)
        ProgressPanel(progress, vm::cancelInstall)
        LauncherCard(Modifier.fillMaxWidth()) {
            Row {
                Text(stringResource(R.string.game_files), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                when (p.state) {
                    InstallState.INSTALLED -> StatusChip(stringResource(R.string.installed), StatusLevel.OK)
                    InstallState.NEEDS_PROCESSORS -> StatusChip(stringResource(R.string.loader_setup_pending), StatusLevel.WARNING)
                    InstallState.FAILED -> StatusChip(stringResource(R.string.failed), StatusLevel.ERROR)
                    InstallState.INSTALLING, InstallState.QUEUED -> StatusChip(stringResource(R.string.installing), StatusLevel.INFO)
                    InstallState.NOT_INSTALLED -> StatusChip(stringResource(R.string.not_installed), StatusLevel.WARNING)
                }
            }
            Spacer(Modifier.height(8.dp))
            LabeledValue(stringResource(R.string.minecraft), p.minecraftVersion)
            LabeledValue(stringResource(R.string.mod_loader), p.loader.lowercase().replaceFirstChar { it.uppercase() } + " " + (p.loaderVersion ?: if (p.loader == "VANILLA") "" else stringResource(R.string.latest_stable)))
            LabeledValue(stringResource(R.string.launch_version), p.launchVersionId ?: "—")
            LabeledValue(stringResource(R.string.memory), stringResource(R.string.s_1_s_2_s_mb_2, p.minMemoryMb, p.maxMemoryMb))
            LabeledValue(stringResource(R.string.last_played), formatDate(p.lastPlayedAt))
            p.lastError?.let { Text(stringResource(R.string.last_error_1_s, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val installed = p.state == InstallState.INSTALLED || p.state == InstallState.NEEDS_PROCESSORS
                Button(onClick = vm::install, enabled = progress == null, modifier = Modifier.weight(1f).testTag("java_install")) { Text(if (installed) stringResource(R.string.repair) else stringResource(R.string.install)) }
                FilledTonalButton(onClick = vm::verify, enabled = installed && !busy && progress == null, modifier = Modifier.weight(1f).testTag("java_verify")) { Text(stringResource(R.string.verify_files)) }
            }
        }
        LaunchButton(if (busy) stringResource(R.string.checking) else stringResource(R.string.play), vm::launch, Modifier.fillMaxWidth(), enabled = !busy && progress == null, icon = Icons.Outlined.PlayArrow)
        LauncherCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.java_runtime), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            if (runtimes.isEmpty()) Text(stringResource(R.string.no_runtime_imported_java_edition_requires), style = MaterialTheme.typography.bodySmall)
            runtimes.forEach { LabeledValue(stringResource(R.string.java_1_s, it.majorVersion), "${it.fullVersion} · ${it.arch}") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onNavigate(Destination.RUNTIMES) }) { Text(stringResource(R.string.manage_runtimes)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onNavigate(Destination.MODS) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.nav_mods)) }
            OutlinedButton(onClick = { onNavigate(Destination.PROFILES) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.edit_profile)) }
        }
    }
}
