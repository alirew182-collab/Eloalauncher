package dev.eloa.launcher.feature.files

import dev.eloa.launcher.feature.common.str
import androidx.compose.ui.res.stringResource
import dev.eloa.launcher.R
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
                IconButton(onClick = { vm.up() }, enabled = ui.path.isNotEmpty()) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.up)) }
                Text("/" + ui.path, Modifier.weight(1f), style = MonoStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { newFolder = true }, modifier = Modifier.testTag("files_new_folder")) { Icon(Icons.Outlined.CreateNewFolder, stringResource(R.string.new_folder)) }
                IconButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Outlined.UploadFile, stringResource(R.string.import_here)) }
            }
        }
        ui.clipboard?.let { c ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (c.move) R.string.clip_move_1_s else R.string.clip_copy_1_s, c.name), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = vm::paste, modifier = Modifier.testTag("files_paste")) { Text(stringResource(R.string.paste_here)) }
                    TextButton(onClick = vm::clearClipboard) { Text(stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_cancel)) }
                }
            }
        }
        item { SearchField(ui.query, vm::search, placeholder = stringResource(R.string.search_in_1_s, ui.root.label)) }
        item { ChipRow(FileSort.entries, ui.sort, { str(R.string.sort) + it.name.lowercase() }, vm::setSort) }
        ui.error?.let { item { ErrorBanner(it, onRetry = { vm.load() }) } }
        val list = ui.searchResults ?: ui.entries
        if (ui.loading && ui.searchResults == null) item { SkeletonList(6) }
        else if (list.isEmpty()) item { EmptyState(Icons.Outlined.FolderOpen, if (ui.searchResults != null) stringResource(R.string.no_results) else stringResource(R.string.empty_folder), stringResource(R.string.import_files_with_the_upload_button)) }
        else items(list, key = { it.relativePath }) { e -> FileRow(e, vm, onExport = { exportEntry = e; exporter.launch(if (e.isDirectory) "${e.name}.zip" else e.name) }, onRename = { renameEntry = e }, onDelete = { deleteEntry = e }) }
    }
    if (newFolder) TextInputDialog(stringResource(R.string.new_folder), stringResource(R.string.sort_name), "", { vm.createFolder(it); newFolder = false }, { newFolder = false },
        validate = { if (!PathSafety.isValidFileName(it)) str(R.string.invalid_name) else null }, confirmLabel = str(R.string.create))
    renameEntry?.let { e -> TextInputDialog(str(R.string.action_rename), str(R.string.new_name), e.name, { vm.rename(e, it); renameEntry = null }, { renameEntry = null }, validate = { if (!PathSafety.isValidFileName(it)) str(R.string.invalid_name) else null }) }
    deleteEntry?.let { e -> ConfirmDialog(stringResource(R.string.delete_1_s, e.name), if (e.isDirectory) stringResource(R.string.the_folder_and_everything_inside_it) else stringResource(R.string.the_file_will_be_permanently_deleted_2), stringResource(R.string.action_delete), { vm.delete(e); deleteEntry = null }, { deleteEntry = null }, destructive = true) }
    ui.info?.let { AlertDialog(onDismissRequest = vm::dismissInfo, title = { Text(stringResource(R.string.integrity)) }, text = { Text(it, style = MonoStyle) }, confirmButton = { TextButton(onClick = vm::dismissInfo) { Text("OK") } }) }
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
                if (e.isDirectory) append(stringResource(R.string.s_1_s_item_s, e.childCount)) else append(FileOps.formatSize(e.size))
                if (e.isDirectory && e.size >= 0) append(" · ${FileOps.formatSize(e.size)}")
                append(" · ${formatDate(e.lastModified)}")
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("file_menu")) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.actions)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.action_rename)) }, { menu = false; onRename() })
                DropdownMenuItem({ Text(stringResource(R.string.copy)) }, { menu = false; vm.copy(e, false) })
                DropdownMenuItem({ Text(stringResource(R.string.move)) }, { menu = false; vm.copy(e, true) })
                DropdownMenuItem({ Text(stringResource(R.string.action_export)) }, { menu = false; onExport() })
                DropdownMenuItem({ Text(stringResource(R.string.back_up)) }, { menu = false; vm.backup(e) })
                if (!e.isDirectory) DropdownMenuItem({ Text(stringResource(R.string.check_integrity)) }, { menu = false; vm.integrity(e) })
                DropdownMenuItem({ Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }, { menu = false; onDelete() }, modifier = Modifier.testTag("file_delete"))
            }
        }
    }
}
