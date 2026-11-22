package dev.eloa.launcher.feature.worlds

import androidx.compose.ui.res.stringResource
import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eloa.launcher.R
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.data.db.WorldBackupEntity
import dev.eloa.launcher.core.designsystem.component.ChipRow
import dev.eloa.launcher.core.designsystem.component.ConfirmDialog
import dev.eloa.launcher.core.designsystem.component.EmptyState
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.ProgressInfo
import dev.eloa.launcher.core.designsystem.component.ProgressPanel
import dev.eloa.launcher.core.designsystem.component.SearchField
import dev.eloa.launcher.core.designsystem.component.StatusChip
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.core.designsystem.component.TextInputDialog
import dev.eloa.launcher.domain.java.content.JavaContentType
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.formatDate
import dev.eloa.launcher.feature.common.userMessage
import dev.eloa.launcher.feature.content.ContentScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class BackupSort { DATE, NAME, SIZE }

@HiltViewModel
class WorldsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: WorldBackupRepository,
    private val messages: UserMessages,
) : ViewModel() {
    val backups: StateFlow<List<WorldBackupEntity>> = repo.backups.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _sources = MutableStateFlow<List<SourceWorld>>(emptyList())
    val sources: StateFlow<List<SourceWorld>> = _sources
    private val _granted = MutableStateFlow(false)
    val granted: StateFlow<Boolean> = _granted
    private val _progress = MutableStateFlow<ProgressInfo?>(null)
    val progress: StateFlow<ProgressInfo?> = _progress
    private var job: Job? = null

    fun load() = viewModelScope.launch {
        _granted.value = repo.folderGranted()
        _sources.value = runCatching { repo.sourceWorlds() }.getOrElse { messages.error(it.userMessage()); emptyList() }
    }

    fun setFolder(uri: android.net.Uri) = viewModelScope.launch { repo.setFolder(uri); load() }

    private fun task(title: String, ok: Int?, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _progress.value = ProgressInfo(title, null, null)
            try { block(); ok?.let { messages.info(it) } } catch (e: kotlinx.coroutines.CancellationException) { messages.info(R.string.msg_cancelled); throw e }
            catch (e: Exception) { messages.error(e.userMessage(context)) } finally { _progress.value = null }
        }
    }

    fun backup(w: SourceWorld) = task(context.getString(R.string.progress_backing_up, w.name), null) {
        val e = repo.backup(w) { bytes -> _progress.value = ProgressInfo(context.getString(R.string.progress_backing_up, w.name), null, FileOps.formatSize(bytes)) }
        if (e == null) messages.info(R.string.msg_backup_identical) else messages.info(R.string.msg_backup_created)
    }
    fun backupAll() = task(context.getString(R.string.progress_backing_up_all), R.string.msg_backup_all_done) { _sources.value.forEach { repo.backup(it) } }
    fun import(uri: android.net.Uri) = task(context.getString(R.string.progress_importing), null) {
        if (repo.import(uri) == null) messages.info(R.string.msg_backup_identical) else messages.info(R.string.msg_world_imported)
    }
    fun verify(id: Long) = task(context.getString(R.string.progress_verifying), null) {
        if (repo.verify(id)) messages.info(R.string.msg_verify_ok) else messages.error(R.string.msg_verify_failed)
    }
    fun restore(id: Long) = task(context.getString(R.string.progress_restoring), null) { val key = repo.restore(id); messages.info(R.string.msg_restored_to, key); load() }
    fun export(id: Long, uri: android.net.Uri) = task(context.getString(R.string.progress_exporting), R.string.msg_exported) { repo.export(id, uri) }
    fun rename(id: Long, label: String) = viewModelScope.launch { repo.rename(id, label) }
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id); messages.info(R.string.msg_backup_deleted) }
    fun cancel() { job?.cancel() }
    fun openInMinecraft(id: Long, launch: (android.content.Intent) -> Unit) = viewModelScope.launch {
        runCatching { repo.openInMinecraftIntent(id) }.onSuccess { i -> runCatching { launch(i) }.onFailure { messages.error(R.string.msg_minecraft_missing) } }
            .onFailure { messages.error(it.userMessage()) }
    }
}

@Composable
fun WorldsScreen(padding: PaddingValues) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.padding(padding).testTag("worlds")) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text(stringResource(R.string.bedrock)) }, modifier = Modifier.testTag("worlds_tab_bedrock"))
            Tab(tab == 1, { tab = 1 }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text(stringResource(R.string.java)) }, modifier = Modifier.testTag("worlds_tab_java"))
        }
        if (tab == 0) BedrockWorlds() else ContentScreen(JavaContentType.WORLDS, PaddingValues(0.dp))
    }
}

@Composable
private fun BedrockWorlds(vm: WorldsViewModel = hiltViewModel()) {
    val backups by vm.backups.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val granted by vm.granted.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.load() }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::setFolder) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
    var exportId by remember { mutableStateOf<Long?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> val id = exportId; if (uri != null && id != null) vm.export(id, uri) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(BackupSort.DATE) }
    var restore by remember { mutableStateOf<WorldBackupEntity?>(null) }
    var delete by remember { mutableStateOf<WorldBackupEntity?>(null) }
    var rename by remember { mutableStateOf<WorldBackupEntity?>(null) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("bedrock_worlds")) {
        item { ProgressPanel(progress, vm::cancel) }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.worlds_on_this_device), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.android_lets_the_launcher_read_only),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { folderPicker.launch(null) }) { Text(if (granted) stringResource(R.string.change_folder) else stringResource(R.string.choose_folder)) }
                    FilledTonalButton(onClick = { importer.launch(arrayOf("*/*")) }, modifier = Modifier.testTag("world_import")) { Text(stringResource(R.string.import_mcworld)) }
                }
                if (granted && sources.isEmpty()) Text(stringResource(R.string.no_worlds_found_in_the_chosen), style = MaterialTheme.typography.bodySmall)
                sources.forEach { w ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Public, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(w.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { vm.backup(w) }, enabled = progress == null) { Text(stringResource(R.string.back_up)) }
                    }
                }
                if (sources.size > 1) Button(onClick = vm::backupAll, enabled = progress == null) { Text(stringResource(R.string.back_up_all)) }
            }
        }
        item { SearchField(query, { query = it }, placeholder = stringResource(R.string.search_backups)) }
        item { ChipRow(BackupSort.entries, sort, { when (it) { BackupSort.DATE -> context.getString(R.string.sort_date); BackupSort.NAME -> context.getString(R.string.sort_name); BackupSort.SIZE -> context.getString(R.string.sort_size) } }, { sort = it }) }
        val shown = backups.filter { query.isBlank() || it.label.contains(query, true) || it.levelName.contains(query, true) }.let { l ->
            when (sort) { BackupSort.DATE -> l; BackupSort.NAME -> l.sortedBy { it.label.lowercase() }; BackupSort.SIZE -> l.sortedByDescending { it.sizeBytes } }
        }
        if (shown.isEmpty()) item { EmptyState(Icons.Outlined.Public, stringResource(R.string.no_world_backups), stringResource(R.string.back_up_a_world_from_the)) }
        items(shown, key = { it.id }) { b ->
            LauncherCard(Modifier.fillMaxWidth().testTag("world_backup")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Thumb(b.thumbnailPath)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (b.label != b.levelName) Text(b.levelName, style = MaterialTheme.typography.bodySmall)
                        Text("${FileOps.formatSize(b.sizeBytes)} · ${formatDate(b.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(when (b.source) { "IMPORT" -> context.getString(R.string.source_import); else -> context.getString(R.string.source_folder) } +
                            (b.gameVersion?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (b.verifyOk) StatusChip(stringResource(R.string.verified), StatusLevel.OK) else StatusChip(stringResource(R.string.damaged), StatusLevel.ERROR)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { restore = b }, enabled = progress == null && b.verifyOk, modifier = Modifier.testTag("world_restore")) { Text(stringResource(R.string.restore)) }
                    TextButton(onClick = { vm.openInMinecraft(b.id) { context.startActivity(it) } }) { Text(stringResource(R.string.open_in_minecraft)) }
                    TextButton(onClick = { vm.verify(b.id) }, enabled = progress == null) { Text(stringResource(R.string.verify)) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { rename = b }) { Icon(Icons.Outlined.Edit, context.getString(R.string.action_rename)) }
                    IconButton(onClick = { exportId = b.id; exporter.launch(b.label + ".mcworld") }) { Icon(Icons.Outlined.IosShare, context.getString(R.string.action_export)) }
                    IconButton(onClick = { delete = b }, modifier = Modifier.testTag("world_delete")) { Icon(Icons.Outlined.Delete, context.getString(R.string.action_delete)) }
                }
            }
        }
    }
    restore?.let { b ->
        AlertDialog(
            modifier = Modifier.testTag("confirm_dialog"),
            onDismissRequest = { restore = null }, title = { Text(stringResource(R.string.restore_1_s_3, b.label)) },
            text = { Text(stringResource(R.string.the_backup_is_verified_first_if)) },
            confirmButton = { Button(onClick = { vm.restore(b.id); restore = null }, modifier = Modifier.testTag("confirm_button")) { Text(stringResource(R.string.restore)) } },
            dismissButton = { TextButton(onClick = { restore = null }) { Text(stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_cancel)) } },
        )
    }
    delete?.let { b -> ConfirmDialog(stringResource(R.string.delete_backup), stringResource(R.string.s_1_s_2_s_will_be, b.label, FileOps.formatSize(b.sizeBytes)), stringResource(R.string.action_delete), { vm.delete(b.id); delete = null }, { delete = null }, destructive = true) }
    rename?.let { b -> TextInputDialog(stringResource(R.string.rename_backup), stringResource(R.string.sort_name), b.label, { vm.rename(b.id, it); rename = null }, { rename = null }) }
}

@Composable
private fun Thumb(path: String?) {
    val bmp = remember(path) { path?.let { p -> runCatching { BitmapFactory.decodeFile(File(p).absolutePath)?.asImageBitmap() }.getOrNull() } }
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp))
        else Icon(Icons.Outlined.Public, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
