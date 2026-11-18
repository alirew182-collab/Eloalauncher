package dev.eloa.launcher.feature.files

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.common.io.PathSafety
import dev.eloa.launcher.core.designsystem.component.ChipRow
import dev.eloa.launcher.core.designsystem.component.ConfirmDialog
import dev.eloa.launcher.core.designsystem.component.EmptyState
import dev.eloa.launcher.core.designsystem.component.ErrorBanner
import dev.eloa.launcher.core.designsystem.component.SearchField
import dev.eloa.launcher.core.designsystem.component.SkeletonList
import dev.eloa.launcher.core.designsystem.component.TextInputDialog
import dev.eloa.launcher.core.designsystem.theme.MonoStyle
import dev.eloa.launcher.feature.common.formatDate

@Composable
fun FilesScreen(padding: PaddingValues, vm: FilesViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    BackHandler(enabled = ui.path.isNotEmpty()) { vm.up() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.import(it) }
    var exportEntry by remember { mutableStateOf<FileEntry?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> val e = exportEntry; if (uri != null && e != null) vm.export(e, uri) }
    var newFolder by remember { mutableStateOf(false) }
    var renameEntry by remember { mutableStateOf<FileEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<FileEntry?>(null) }

    LazyColumn(Modifier.padding(padding).testTag("files"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { ChipRow(FileRoot.entries, ui.root, { it.label }, vm::setRoot) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.up() }, enabled = ui.path.isNotEmpty()) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Up") }
                Text("/" + ui.path, Modifier.weight(1f), style = MonoStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { newFolder = true }, modifier = Modifier.testTag("files_new_folder")) { Icon(Icons.Outlined.CreateNewFolder, "New folder") }
                IconButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Outlined.UploadFile, "Import here") }
            }
        }
        ui.clipboard?.let { c ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${if (c.move) "Move" else "Copy"}: ${c.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = vm::paste, modifier = Modifier.testTag("files_paste")) { Text("Paste here") }
                    TextButton(onClick = vm::clearClipboard) { Text("Cancel") }
                }
            }
        }
        item { SearchField(ui.query, vm::search, placeholder = "Search in ${ui.root.label}") }
        item { ChipRow(FileSort.entries, ui.sort, { "Sort: " + it.name.lowercase() }, vm::setSort) }
        ui.error?.let { item { ErrorBanner(it, onRetry = { vm.load() }) } }
        val list = ui.searchResults ?: ui.entries
        if (ui.loading && ui.searchResults == null) item { SkeletonList(6) }
        else if (list.isEmpty()) item { EmptyState(Icons.Outlined.FolderOpen, if (ui.searchResults != null) "No results" else "Empty folder", "Import files with the upload button or create a folder.") }
        else items(list, key = { it.relativePath }) { e -> FileRow(e, vm, onExport = { exportEntry = e; exporter.launch(if (e.isDirectory) "${e.name}.zip" else e.name) }, onRename = { renameEntry = e }, onDelete = { deleteEntry = e }) }
    }
    if (newFolder) TextInputDialog("New folder", "Name", "", { vm.createFolder(it); newFolder = false }, { newFolder = false },
        validate = { if (!PathSafety.isValidFileName(it)) "Invalid name" else null }, confirmLabel = "Create")
    renameEntry?.let { e -> TextInputDialog("Rename", "New name", e.name, { vm.rename(e, it); renameEntry = null }, { renameEntry = null }, validate = { if (!PathSafety.isValidFileName(it)) "Invalid name" else null }) }
    deleteEntry?.let { e -> ConfirmDialog("Delete ${e.name}?", if (e.isDirectory) "The folder and everything inside it will be permanently deleted." else "The file will be permanently deleted.", "Delete", { vm.delete(e); deleteEntry = null }, { deleteEntry = null }, destructive = true) }
    ui.info?.let { AlertDialog(onDismissRequest = vm::dismissInfo, title = { Text("Integrity") }, text = { Text(it, style = MonoStyle) }, confirmButton = { TextButton(onClick = vm::dismissInfo) { Text("OK") } }) }
}

@Composable
private fun FileRow(e: FileEntry, vm: FilesViewModel, onExport: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(enabled = e.isDirectory) { vm.open(e) }.padding(vertical = 6.dp).testTag("file_row"), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (e.isDirectory) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(buildString {
                if (e.isDirectory) append("${e.childCount} item(s)") else append(FileOps.formatSize(e.size))
                if (e.isDirectory && e.size >= 0) append(" · ${FileOps.formatSize(e.size)}")
                append(" · ${formatDate(e.lastModified)}")
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("file_menu")) { Icon(Icons.Outlined.MoreVert, "Actions") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Rename") }, { menu = false; onRename() })
                DropdownMenuItem({ Text("Copy") }, { menu = false; vm.copy(e, false) })
                DropdownMenuItem({ Text("Move") }, { menu = false; vm.copy(e, true) })
                DropdownMenuItem({ Text("Export") }, { menu = false; onExport() })
                DropdownMenuItem({ Text("Back up") }, { menu = false; vm.backup(e) })
                if (!e.isDirectory) DropdownMenuItem({ Text("Check integrity") }, { menu = false; vm.integrity(e) })
                DropdownMenuItem({ Text("Delete", color = MaterialTheme.colorScheme.error) }, { menu = false; onDelete() }, modifier = Modifier.testTag("file_delete"))
            }
        }
    }
}
