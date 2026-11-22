package dev.eloa.launcher.feature.bedrock

import dev.eloa.launcher.feature.common.str
import androidx.compose.ui.res.stringResource
import dev.eloa.launcher.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.data.db.BedrockVersionEntity
import dev.eloa.launcher.core.designsystem.component.EmptyState
import dev.eloa.launcher.core.designsystem.component.LabeledValue
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.ProgressPanel
import dev.eloa.launcher.core.designsystem.component.SkeletonList
import dev.eloa.launcher.core.designsystem.component.StatusChip
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.core.designsystem.component.TextInputDialog
import dev.eloa.launcher.core.designsystem.theme.MonoStyle
import dev.eloa.launcher.feature.common.LaunchResultDialog
import dev.eloa.launcher.feature.common.formatDate

private val APK_MIMES = arrayOf("application/vnd.android.package-archive", "application/octet-stream", "application/zip", "application/x-zip-compressed", "*/*")

@Composable
fun BedrockScreen(padding: PaddingValues, vm: BedrockViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val outcome by vm.outcome.collectAsStateWithLifecycle()
    val needsPermission by vm.needsPermission.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.import(it) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportMetadata) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detailsId by rememberSaveable { mutableLongStateOf(-1L) }

    LaunchResultDialog(outcome, vm::dismissOutcome)
    report?.let { r -> ImportReportDialog(r, vm::dismissReport) }
    plan?.let { p ->
        AlertDialog(
            modifier = Modifier.testTag("install_plan"),
            onDismissRequest = vm::dismissPlan, title = { Text(stringResource(R.string.install_1_s, p.entity.displayName)) },
            text = { Text(p.explanation) },
            confirmButton = { if (p.canProceed) Button(onClick = vm::confirmInstall, modifier = Modifier.testTag("install_confirm")) { Text(stringResource(R.string.install)) } else TextButton(onClick = { context.startActivity(vm.appSettingsIntent()); vm.dismissPlan() }) { Text(stringResource(R.string.open_app_settings)) } },
            dismissButton = { TextButton(onClick = vm::dismissPlan) { Text(if (p.canProceed) stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_cancel) else stringResource(R.string.close)) } },
        )
    }
    if (needsPermission) AlertDialog(
        onDismissRequest = vm::dismissPermission, title = { Text(stringResource(R.string.permission_required)) },
        text = { Text(stringResource(R.string.android_requires_you_to_allow_this)) },
        confirmButton = { Button(onClick = { context.startActivity(vm.permissionIntent()); vm.dismissPermission() }) { Text(stringResource(R.string.open_setting)) } },
        dismissButton = { TextButton(onClick = vm::dismissPermission) { Text(stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_cancel)) } },
    )

    Scaffold(
        modifier = Modifier.padding(padding),
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { picker.launch(APK_MIMES) }, icon = { Icon(Icons.Outlined.FileDownload, null) }, text = { Text(stringResource(R.string.import_apk)) }, modifier = Modifier.testTag("import_apk"))
        },
    ) { inner ->
        Column(Modifier.padding(inner)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.library_1_s, ui.versions.size)) })
                Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.import_history)) })
            }
            ProgressPanel(progress, vm::cancel, Modifier.padding(16.dp))
            when {
                ui.loading -> SkeletonList(4, Modifier.padding(16.dp))
                tab == 0 -> LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = vm::launchInstalled) { Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.play_installed)) }
                            OutlinedButton(onClick = { exporter.launch("bedrock-library.json") }, enabled = ui.versions.isNotEmpty()) { Text(stringResource(R.string.backup_metadata)) }
                        }
                    }
                    if (ui.versions.isEmpty()) item {
                        EmptyState(Icons.Outlined.Android, stringResource(R.string.no_bedrock_versions_yet),
                            stringResource(R.string.import_minecraft_bedrock_apk_files_you),
                            actionLabel = stringResource(R.string.import_apk), onAction = { picker.launch(APK_MIMES) })
                    }
                    items(ui.versions, key = { it.id }) { v -> VersionCard(v, v.id == ui.selectedId, onClick = { detailsId = v.id }) }
                }
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (history.isEmpty()) item { EmptyState(Icons.Outlined.FileDownload, stringResource(R.string.no_imports_yet), stringResource(R.string.imported_and_rejected_files_will_appear)) }
                    items(history, key = { it.id }) { h ->
                        LauncherCard(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(h.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${formatDate(h.timestamp)} · ${h.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                StatusChip(if (h.success) "OK" else stringResource(R.string.rejected), if (h.success) StatusLevel.OK else StatusLevel.WARNING)
                            }
                        }
                    }
                }
            }
        }
    }
    ui.versions.firstOrNull { it.id == detailsId }?.let { v -> VersionSheet(v, v.id == ui.selectedId, vm) { detailsId = -1 } }
}

@Composable
private fun VersionCard(v: BedrockVersionEntity, selected: Boolean, onClick: () -> Unit) {
    LauncherCard(Modifier.fillMaxWidth().testTag("bedrock_version"), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(v.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${v.versionName ?: "?"} (${v.versionCode}) · ${v.abis.ifEmpty { "—" }} · ${FileOps.formatSize(v.totalSize)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.imported_1_s, formatDate(v.importedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (selected) StatusChip(stringResource(R.string.selected), StatusLevel.INFO)
                CompatChip(v)
                if (!v.integrityOk) StatusChip(stringResource(R.string.damaged), StatusLevel.ERROR)
            }
        }
    }
}

@Composable
private fun CompatChip(v: BedrockVersionEntity) = when (v.compatStatus) {
    "COMPATIBLE" -> StatusChip(stringResource(R.string.compatible), StatusLevel.OK)
    "WARNING" -> StatusChip(stringResource(R.string.check), StatusLevel.WARNING)
    else -> StatusChip(stringResource(R.string.incompatible), StatusLevel.ERROR)
}

@Composable
private fun VersionSheet(v: BedrockVersionEntity, selected: Boolean, vm: BedrockViewModel, onDismiss: () -> Unit) {
    var rename by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var deleteFiles by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("version_sheet")) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) {
            Text(v.displayName, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CompatChip(v); StatusChip(if (v.signatureValid) stringResource(R.string.signature_ok) else stringResource(R.string.bad_signature), if (v.signatureValid) StatusLevel.OK else StatusLevel.ERROR) }
            Spacer(Modifier.height(12.dp))
            LabeledValue(stringResource(R.string.label_package), v.packageName)
            LabeledValue(stringResource(R.string.version), stringResource(R.string.version_code_1_s_2_s, v.versionName ?: "?", v.versionCode))
            LabeledValue(stringResource(R.string.architecture), v.abis.ifEmpty { "—" })
            LabeledValue(stringResource(R.string.min_android_api), v.minSdk.toString())
            LabeledValue(stringResource(R.string.target_api), v.targetSdk?.toString() ?: "—")
            LabeledValue(stringResource(R.string.sort_size), FileOps.formatSize(v.totalSize))
            LabeledValue(stringResource(R.string.apk_files), vm.files(v).joinToString { it.name })
            LabeledValue(stringResource(R.string.imported), formatDate(v.importedAt))
            LabeledValue(stringResource(R.string.last_verified), formatDate(v.lastVerifiedAt))
            LabeledValue(stringResource(R.string.last_launched), formatDate(v.lastLaunchedAt))
            if (v.notes.isNotBlank()) LabeledValue(stringResource(R.string.notes), v.notes)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.compatibility), style = MaterialTheme.typography.titleSmall)
            Text(v.compatReasons, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.certificate_sha_256), style = MaterialTheme.typography.titleSmall)
            Text(v.certSha256.ifEmpty { "—" }, style = MonoStyle)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.launch(v.id) }, modifier = Modifier.weight(1f).testTag("bedrock_launch")) { Text(stringResource(R.string.launch)) }
                FilledTonalButton(onClick = { vm.requestInstall(v.id) }, modifier = Modifier.weight(1f).testTag("bedrock_install")) { Text(stringResource(R.string.install)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.select(v.id) }, enabled = !selected, modifier = Modifier.weight(1f)) { Text(if (selected) stringResource(R.string.selected) else stringResource(R.string.select)) }
                OutlinedButton(onClick = { vm.verify(v.id) }, modifier = Modifier.weight(1f).testTag("bedrock_verify")) { Text(stringResource(R.string.verify)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { rename = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_rename)) }
                OutlinedButton(onClick = { notes = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.notes)) }
                TextButton(onClick = { delete = true }, modifier = Modifier.weight(1f).testTag("bedrock_delete")) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (rename) TextInputDialog(stringResource(R.string.action_rename), stringResource(R.string.sort_name), v.displayName, { vm.rename(v.id, it); rename = false }, { rename = false })
    if (notes) TextInputDialog(str(R.string.notes), str(R.string.notes), v.notes, { vm.setNotes(v.id, it); notes = false }, { notes = false }, validate = { if (it.length > 500) str(R.string.too_long) else null })
    if (delete) AlertDialog(
        modifier = Modifier.testTag("confirm_dialog"),
        onDismissRequest = { delete = false }, title = { Text(stringResource(R.string.remove_1_s, v.displayName)) },
        text = {
            Column {
                Text(stringResource(R.string.the_entry_will_be_removed_from))
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(deleteFiles, { deleteFiles = it }); Text(stringResource(R.string.also_delete_the_stored_apk_copies, FileOps.formatSize(v.totalSize))) }
            }
        },
        confirmButton = { Button(onClick = { vm.delete(v.id, deleteFiles); delete = false; onDismiss() }, modifier = Modifier.testTag("confirm_button")) { Text(stringResource(R.string.remove)) } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text(stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_cancel)) } },
    )
}

@Composable
private fun ImportReportDialog(r: ImportReport, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag("import_report"),
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.import_finished)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (r.imported.isNotEmpty()) Text(stringResource(R.string.added_1_s, r.imported.joinToString()))
                if (r.duplicates.isNotEmpty()) Text(stringResource(R.string.already_in_library_1_s, r.duplicates.joinToString()))
                r.failures.forEach { (n, m) -> Text("✗ $n — $m", color = MaterialTheme.colorScheme.error) }
                r.warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall) }
                if (r.imported.isEmpty() && r.failures.isEmpty() && r.duplicates.isEmpty()) Text(stringResource(R.string.no_files_were_processed))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
