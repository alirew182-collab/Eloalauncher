package dev.eloa.launcher.feature.mods

import dev.eloa.launcher.feature.common.str
import androidx.compose.ui.res.stringResource
import dev.eloa.launcher.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.designsystem.component.ChipRow
import dev.eloa.launcher.core.designsystem.component.ConfirmDialog
import dev.eloa.launcher.core.designsystem.component.EmptyState
import dev.eloa.launcher.core.designsystem.component.LabeledValue
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.SearchField
import dev.eloa.launcher.core.designsystem.component.SkeletonList
import dev.eloa.launcher.core.designsystem.component.StatusChip
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.domain.java.mods.IssueSeverity
import dev.eloa.launcher.domain.java.mods.ModFile
import dev.eloa.launcher.feature.common.ProfileSelectorBar
import dev.eloa.launcher.feature.common.formatDate
import java.io.File

@Composable
fun ModsScreen(padding: PaddingValues, vm: ModsViewModel = hiltViewModel()) {
    val selection by vm.selection.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(selection.selected) { vm.bind(selection.selected) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.import(it) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ModFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(ModSort.NAME) }
    var details by remember { mutableStateOf<ModFile?>(null) }
    var delete by remember { mutableStateOf<ModFile?>(null) }
    var restore by remember { mutableStateOf(false) }
    var restoreFile by remember { mutableStateOf<File?>(null) }
    val problemFiles = ui.issues.filter { it.severity != IssueSeverity.INFO }.mapNotNull { it.modFile }.flatMap { it.split(", ") }.toSet()

    Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("mods")) {
            item { ProfileSelectorBar(selection, vm::selectProfile) }
            if (selection.selected == null) { item { EmptyState(Icons.Outlined.Extension, stringResource(R.string.no_profile), stringResource(R.string.create_a_profile_with_fabric_quilt)) }; return@LazyColumn }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::backup, enabled = ui.mods.isNotEmpty()) { Text(stringResource(R.string.backup)) }
                    OutlinedButton(onClick = { restore = true }, enabled = ui.backups.isNotEmpty()) { Text(stringResource(R.string.restore_1_s, ui.backups.size)) }
                    OutlinedButton(onClick = vm::refresh) { Text(stringResource(R.string.re_check)) }
                }
            }
            if (ui.issues.isNotEmpty()) item {
                LauncherCard(Modifier.fillMaxWidth().testTag("mod_issues")) {
                    Text(stringResource(R.string.compatibility_check), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    ui.issues.take(12).forEach { i ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            StatusChip(i.severity.name.lowercase(), when (i.severity) { IssueSeverity.ERROR -> StatusLevel.ERROR; IssueSeverity.WARNING -> StatusLevel.WARNING; else -> StatusLevel.INFO })
                            Spacer(Modifier.padding(4.dp))
                            Column {
                                Text(i.message, style = MaterialTheme.typography.bodySmall)
                                i.suggestion?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                            }
                        }
                    }
                    if (ui.issues.size > 12) Text(stringResource(R.string.s_1_s_more, ui.issues.size - 12), style = MaterialTheme.typography.labelSmall)
                }
            }
            item { SearchField(query, { query = it }, placeholder = stringResource(R.string.search_mods)) }
            item { ChipRow(ModFilter.entries, filter, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, { filter = it }) }
            item { ChipRow(ModSort.entries, sort, { str(R.string.sort) + it.name.lowercase() }, { sort = it }) }
            if (ui.loading) { item { SkeletonList(5) }; return@LazyColumn }
            val shown = ui.mods.filter { m ->
                (query.isBlank() || m.displayName.contains(query, true) || m.metadata.any { it.id.contains(query, true) }) && when (filter) {
                    ModFilter.ALL -> true; ModFilter.ENABLED -> m.enabled; ModFilter.DISABLED -> !m.enabled
                    ModFilter.PROBLEMS -> m.fileName in problemFiles; ModFilter.LIBRARIES -> m.fileName in ui.libraries
                }
            }.let { l -> when (sort) { ModSort.NAME -> l; ModSort.SIZE -> l.sortedByDescending { it.sizeBytes }; ModSort.DATE -> l.sortedByDescending { it.lastModified } } }
            item { Text(stringResource(R.string.s_1_s_of_2_s_mods, shown.size, ui.mods.size, ui.mods.count { it.enabled }), style = MaterialTheme.typography.labelMedium) }
            if (shown.isEmpty()) item { EmptyState(Icons.Outlined.Extension, stringResource(R.string.no_mods), stringResource(R.string.import_jar_files_for_1_s, selection.selected?.loader?.lowercase().orEmpty()), actionLabel = stringResource(R.string.label_import), onAction = { picker.launch(arrayOf("application/java-archive", "application/octet-stream", "application/zip", "*/*")) }) }
            items(shown, key = { it.fileName }) { m ->
                LauncherCard(Modifier.fillMaxWidth().testTag("mod_item"), onClick = { details = m }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${m.primary?.version ?: "?"} · ${m.metadata.joinToString("/") { it.format.displayName }.ifEmpty { "?" }} · ${FileOps.formatSize(m.sizeBytes)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (m.fileName in problemFiles) StatusChip(stringResource(R.string.problem), StatusLevel.ERROR)
                                if (m.fileName in ui.libraries) StatusChip(stringResource(R.string.nav_group_library), StatusLevel.INFO)
                            }
                        }
                        Switch(m.enabled, { vm.toggle(m) }, modifier = Modifier.testTag("mod_toggle"))
                        IconButton(onClick = { delete = m }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
                    }
                }
            }
        }
        if (selection.selected != null) FloatingActionButton(onClick = { picker.launch(arrayOf("application/java-archive", "application/octet-stream", "application/zip", "*/*")) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).testTag("mod_import")) { Icon(Icons.Outlined.Add, stringResource(R.string.import_mod)) }
    }
    details?.let { m -> ModDetails(m, ui.issues.filter { it.modFile?.contains(m.fileName) == true }.map { it.message }) { details = null } }
    delete?.let { m -> ConfirmDialog(stringResource(R.string.delete_1_s, m.displayName), stringResource(R.string.the_jar_file_will_be_removed), stringResource(R.string.action_delete), { vm.delete(m); delete = null }, { delete = null }, destructive = true) }
    if (restore) AlertDialog(
        onDismissRequest = { restore = false }, title = { Text(stringResource(R.string.restore_mods)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { ui.backups.forEach { f -> TextButton(onClick = { restoreFile = f; restore = false }) { Text(f.name) } } } },
        confirmButton = { TextButton(onClick = { restore = false }) { Text(stringResource(R.string.close)) } },
    )
    restoreFile?.let { f -> ConfirmDialog(stringResource(R.string.restore_1_s_2, f.name), stringResource(R.string.current_mods_will_be_replaced_by), stringResource(R.string.restore), { vm.restore(f); restoreFile = null }, { restoreFile = null }) }
}

@Composable
private fun ModDetails(m: ModFile, problems: List<String>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(m.displayName, style = MaterialTheme.typography.headlineSmall)
            LabeledValue(stringResource(R.string.file), m.fileName)
            LabeledValue(stringResource(R.string.sort_size), FileOps.formatSize(m.sizeBytes))
            LabeledValue(stringResource(R.string.modified), formatDate(m.lastModified))
            m.error?.let { Text(stringResource(R.string.error_1_s, it), color = MaterialTheme.colorScheme.error) }
            if (m.metadata.isEmpty()) Text(stringResource(R.string.no_fabric_quilt_forge_neoforge_metadata), style = MaterialTheme.typography.bodySmall)
            m.metadata.forEach { md ->
                Spacer(Modifier.height(10.dp))
                Text("${md.format.displayName}: ${md.name}", style = MaterialTheme.typography.titleSmall)
                LabeledValue("ID", md.id); LabeledValue(stringResource(R.string.version), md.version)
                if (md.authors.isNotEmpty()) LabeledValue(stringResource(R.string.authors), md.authors.joinToString())
                md.license?.let { LabeledValue(stringResource(R.string.license), it) }
                LabeledValue(stringResource(R.string.environment), md.environment)
                if (md.description.isNotBlank()) Text(md.description, style = MaterialTheme.typography.bodySmall)
                if (md.dependencies.isNotEmpty()) {
                    Text(stringResource(R.string.dependencies), style = MaterialTheme.typography.labelLarge)
                    md.dependencies.forEach { d -> Text("• ${d.modId} ${d.range.joinToString(" | ")} (${d.kind.name.lowercase()})", style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (problems.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.problems), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                problems.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
