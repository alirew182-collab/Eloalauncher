package dev.eloa.launcher.feature.javaui

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
    val tabs = listOf("Overview", "Worlds", "Datapacks", "Configs")
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
            EmptyState(Icons.Outlined.Coffee, "No Java Edition profile", "Profiles keep their own version, mod loader, mods, settings and game folder.",
                actionLabel = "Create profile", onAction = { onNavigate(Destination.PROFILES) })
            return@Column
        }
        ProfileSelectorBar(selection, vm::select)
        ProgressPanel(progress, vm::cancelInstall)
        LauncherCard(Modifier.fillMaxWidth()) {
            Row {
                Text("Game files", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                when (p.state) {
                    InstallState.INSTALLED -> StatusChip("Installed", StatusLevel.OK)
                    InstallState.NEEDS_PROCESSORS -> StatusChip("Loader setup pending", StatusLevel.WARNING)
                    InstallState.FAILED -> StatusChip("Failed", StatusLevel.ERROR)
                    InstallState.INSTALLING, InstallState.QUEUED -> StatusChip("Installing", StatusLevel.INFO)
                    InstallState.NOT_INSTALLED -> StatusChip("Not installed", StatusLevel.WARNING)
                }
            }
            Spacer(Modifier.height(8.dp))
            LabeledValue("Minecraft", p.minecraftVersion)
            LabeledValue("Mod loader", "${p.loader.lowercase().replaceFirstChar { it.uppercase() }} ${p.loaderVersion ?: if (p.loader == "VANILLA") "" else "(latest stable)"}")
            LabeledValue("Launch version", p.launchVersionId ?: "—")
            LabeledValue("Memory", "${p.minMemoryMb}–${p.maxMemoryMb} MB")
            LabeledValue("Last played", formatDate(p.lastPlayedAt))
            p.lastError?.let { Text("Last error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val installed = p.state == InstallState.INSTALLED || p.state == InstallState.NEEDS_PROCESSORS
                Button(onClick = vm::install, enabled = progress == null, modifier = Modifier.weight(1f).testTag("java_install")) { Text(if (installed) "Repair" else "Install") }
                FilledTonalButton(onClick = vm::verify, enabled = installed && !busy && progress == null, modifier = Modifier.weight(1f).testTag("java_verify")) { Text("Verify files") }
            }
        }
        LaunchButton(if (busy) "CHECKING…" else "PLAY", vm::launch, Modifier.fillMaxWidth(), enabled = !busy && progress == null, icon = Icons.Outlined.PlayArrow)
        LauncherCard(Modifier.fillMaxWidth()) {
            Text("Java runtime", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            if (runtimes.isEmpty()) Text("No runtime imported. Java Edition requires a Java runtime built for Android (and an execution backend).", style = MaterialTheme.typography.bodySmall)
            runtimes.forEach { LabeledValue("Java ${it.majorVersion}", "${it.fullVersion} · ${it.arch}") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onNavigate(Destination.SETTINGS) }) { Text("Manage runtimes") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onNavigate(Destination.MODS) }, modifier = Modifier.weight(1f)) { Text("Mods") }
            OutlinedButton(onClick = { onNavigate(Destination.PROFILES) }, modifier = Modifier.weight(1f)) { Text("Edit profile") }
        }
    }
}
