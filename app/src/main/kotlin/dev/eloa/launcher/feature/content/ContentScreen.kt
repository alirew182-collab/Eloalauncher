package dev.eloa.launcher.feature.content

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import dev.eloa.launcher.core.designsystem.component.ErrorBanner
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.SearchField
import dev.eloa.launcher.core.designsystem.component.SkeletonList
import dev.eloa.launcher.core.designsystem.component.StatusChip
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.core.designsystem.theme.MonoStyle
import dev.eloa.launcher.domain.java.content.JavaContentType
import dev.eloa.launcher.domain.java.content.PackFormats
import dev.eloa.launcher.feature.common.ProfileSelectorBar
import dev.eloa.launcher.feature.common.SimpleMenu
import dev.eloa.launcher.feature.common.formatDate
import dev.eloa.launcher.feature.java.ContentItem

fun JavaContentType.title() = when (this) {
    JavaContentType.RESOURCE_PACKS -> "Resource packs"; JavaContentType.SHADER_PACKS -> "Shader packs"; JavaContentType.DATAPACKS -> "Datapacks"
    JavaContentType.WORLDS -> "Worlds"; JavaContentType.CONFIGS -> "Configuration files"
}

@Composable
fun ContentScreen(type: JavaContentType, padding: PaddingValues, showProfileBar: Boolean = true, vm: ContentViewModel = hiltViewModel(key = "content-${type.name}")) {
    val selection by vm.selection.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(type, selection.selected) { vm.bind(type, selection.selected) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.import(it) }
    var exportItem by remember { mutableStateOf<ContentItem?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> val i = exportItem; if (uri != null && i != null) vm.export(i, uri) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(ContentSort.NAME) }
    var deleteItem by remember { mutableStateOf<ContentItem?>(null) }
    var editItem by remember { mutableStateOf<ContentItem?>(null) }

    Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("content_${type.name}")) {
            if (showProfileBar) item { ProfileSelectorBar(selection, vm::selectProfile) }
            if (selection.selected == null && !ui.loading) {
                item { EmptyState(Icons.Outlined.FolderOpen, "No profile", "Create a Java Edition profile first; each profile has its own ${type.title().lowercase()}.") }
                return@LazyColumn
            }
            ui.error?.let { item { ErrorBanner(it, onRetry = vm::refresh) } }
            if (type == JavaContentType.SHADER_PACKS && !ui.shaderLoaderPresent) item {
                ErrorBanner("Shader packs need a shader mod (Iris for Fabric/Quilt, Oculus for Forge, or OptiFine). None is enabled in this profile.")
            }
            if (type == JavaContentType.DATAPACKS) item {
                var open by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("World: ${ui.world ?: "no worlds"}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Box {
                        OutlinedButton(onClick = { open = true }, enabled = ui.worlds.size > 1) { Text("Change world") }
                        SimpleMenu(open, { open = false }, ui.worlds, { it }) { vm.selectWorld(it) }
                    }
                }
            }
            item { SearchField(query, { query = it }, placeholder = "Search ${type.title().lowercase()}") }
            item { ChipRow(ContentSort.entries, sort, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, { sort = it }) }
            if (ui.loading) { item { SkeletonList(4) }; return@LazyColumn }
            val shown = ui.items.filter { query.isBlank() || it.displayName.contains(query, true) }.let { l ->
                when (sort) { ContentSort.NAME -> l.sortedBy { it.displayName.lowercase() }; ContentSort.SIZE -> l.sortedByDescending { it.sizeBytes }; ContentSort.DATE -> l.sortedByDescending { it.lastModified } }
            }
            if (shown.isEmpty()) item {
                EmptyState(Icons.Outlined.FolderOpen, if (query.isBlank()) "Nothing here yet" else "No matches",
                    if (type == JavaContentType.DATAPACKS && ui.world == null) "Import or create a world first." else "Tap + to import ${type.title().lowercase()} (${type.extensions.joinToString { ".$it" }}).")
            }
            items(shown, key = { it.fileName }) { item ->
                LauncherCard(Modifier.fillMaxWidth().testTag("content_item")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${FileOps.formatSize(item.sizeBytes)} · ${formatDate(item.lastModified)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (item.description.isNotBlank()) Text(item.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (type == JavaContentType.RESOURCE_PACKS) when (item.compat) {
                                PackFormats.Compat.COMPATIBLE -> StatusChip("Made for this version", StatusLevel.OK)
                                PackFormats.Compat.INCOMPATIBLE -> StatusChip("Made for another version", StatusLevel.WARNING)
                                PackFormats.Compat.UNKNOWN -> Unit
                            }
                        }
                        if (type != JavaContentType.WORLDS && type != JavaContentType.CONFIGS) Switch(item.enabled, { vm.toggle(item) }, modifier = Modifier.testTag("content_toggle"))
                        if (type == JavaContentType.CONFIGS) IconButton(onClick = { editItem = item }) { Icon(Icons.Outlined.Edit, "Edit") }
                        IconButton(onClick = { exportItem = item; exporter.launch(if (item.isDirectory) "${item.fileName}.zip" else item.fileName) }) { Icon(Icons.Outlined.IosShare, "Export") }
                        IconButton(onClick = { deleteItem = item }, modifier = Modifier.testTag("content_delete")) { Icon(Icons.Outlined.Delete, "Delete") }
                    }
                }
            }
        }
        if (selection.selected != null) FloatingActionButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).testTag("content_import")) { Icon(Icons.Outlined.Add, "Import") }
    }
    deleteItem?.let { d ->
        ConfirmDialog("Delete ${d.displayName}?", if (type == JavaContentType.WORLDS) "The world and all its progress will be permanently deleted. Export it first if you want a backup." else "The file will be permanently deleted from this profile.",
            "Delete", { vm.delete(d); deleteItem = null }, { deleteItem = null }, destructive = true)
    }
    editItem?.let { e -> ConfigEditor(e, vm) { editItem = null } }
}

@Composable
private fun ConfigEditor(item: ContentItem, vm: ContentViewModel, onDismiss: () -> Unit) {
    val initial = remember(item) { vm.readConfig(item) }
    if (initial == null) { onDismiss(); return }
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(item.fileName) },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, textStyle = MonoStyle, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp).testTag("config_editor"))
                Spacer(Modifier.height(4.dp))
                Text("A backup of the previous content is kept.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { vm.saveConfig(item, text); onDismiss() }, enabled = text != initial) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
