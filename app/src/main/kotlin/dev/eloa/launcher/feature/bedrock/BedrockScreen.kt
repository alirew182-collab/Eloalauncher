package dev.eloa.launcher.feature.bedrock

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
            onDismissRequest = vm::dismissPlan, title = { Text("Install ${p.entity.displayName}?") },
            text = { Text(p.explanation) },
            confirmButton = { if (p.canProceed) Button(onClick = vm::confirmInstall, modifier = Modifier.testTag("install_confirm")) { Text("Install") } else TextButton(onClick = { context.startActivity(vm.appSettingsIntent()); vm.dismissPlan() }) { Text("Open app settings") } },
            dismissButton = { TextButton(onClick = vm::dismissPlan) { Text(if (p.canProceed) "Cancel" else "Close") } },
        )
    }
    if (needsPermission) AlertDialog(
        onDismissRequest = vm::dismissPermission, title = { Text("Permission required") },
        text = { Text("Android requires you to allow this launcher to install apps. You will be taken to the system setting; come back and tap Install again.") },
        confirmButton = { Button(onClick = { context.startActivity(vm.permissionIntent()); vm.dismissPermission() }) { Text("Open setting") } },
        dismissButton = { TextButton(onClick = vm::dismissPermission) { Text("Cancel") } },
    )

    Scaffold(
        modifier = Modifier.padding(padding),
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { picker.launch(APK_MIMES) }, icon = { Icon(Icons.Outlined.FileDownload, null) }, text = { Text("Import APK") }, modifier = Modifier.testTag("import_apk"))
        },
    ) { inner ->
        Column(Modifier.padding(inner)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Library (${ui.versions.size})") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Import history") })
            }
            ProgressPanel(progress, vm::cancel, Modifier.padding(16.dp))
            when {
                ui.loading -> SkeletonList(4, Modifier.padding(16.dp))
                tab == 0 -> LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = vm::launchInstalled) { Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play installed") }
                            OutlinedButton(onClick = { exporter.launch("bedrock-library.json") }, enabled = ui.versions.isNotEmpty()) { Text("Backup metadata") }
                        }
                    }
                    if (ui.versions.isEmpty()) item {
                        EmptyState(Icons.Outlined.Android, "No Bedrock versions yet",
                            "Import Minecraft Bedrock APK files you own (.apk, split .apks/.xapk). Files are verified before they are added.",
                            actionLabel = "Import APK", onAction = { picker.launch(APK_MIMES) })
                    }
                    items(ui.versions, key = { it.id }) { v -> VersionCard(v, v.id == ui.selectedId, onClick = { detailsId = v.id }) }
                }
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (history.isEmpty()) item { EmptyState(Icons.Outlined.FileDownload, "No imports yet", "Imported and rejected files will appear here.") }
                    items(history, key = { it.id }) { h ->
                        LauncherCard(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(h.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${formatDate(h.timestamp)} · ${h.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                StatusChip(if (h.success) "OK" else "Rejected", if (h.success) StatusLevel.OK else StatusLevel.WARNING)
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
                Text("${v.versionName ?: "?"} (${v.versionCode}) · ${v.abis.ifEmpty { "no ABI" }} · ${FileOps.formatSize(v.totalSize)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Imported ${formatDate(v.importedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (selected) StatusChip("Selected", StatusLevel.INFO)
                compatChip(v)
                if (!v.integrityOk) StatusChip("Damaged", StatusLevel.ERROR)
            }
        }
    }
}

@Composable
private fun compatChip(v: BedrockVersionEntity) = when (v.compatStatus) {
    "COMPATIBLE" -> StatusChip("Compatible", StatusLevel.OK)
    "WARNING" -> StatusChip("Check", StatusLevel.WARNING)
    else -> StatusChip("Incompatible", StatusLevel.ERROR)
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { compatChip(v); StatusChip(if (v.signatureValid) "Signature OK" else "Bad signature", if (v.signatureValid) StatusLevel.OK else StatusLevel.ERROR) }
            Spacer(Modifier.height(12.dp))
            LabeledValue("Package", v.packageName)
            LabeledValue("Version", "${v.versionName ?: "?"} (code ${v.versionCode})")
            LabeledValue("Architecture", v.abis.ifEmpty { "—" })
            LabeledValue("Min Android API", v.minSdk.toString())
            LabeledValue("Target API", v.targetSdk?.toString() ?: "—")
            LabeledValue("Size", FileOps.formatSize(v.totalSize))
            LabeledValue("APK files", vm.files(v).joinToString { it.name })
            LabeledValue("Imported", formatDate(v.importedAt))
            LabeledValue("Last verified", formatDate(v.lastVerifiedAt))
            LabeledValue("Last launched", formatDate(v.lastLaunchedAt))
            if (v.notes.isNotBlank()) LabeledValue("Notes", v.notes)
            Spacer(Modifier.height(6.dp))
            Text("Compatibility", style = MaterialTheme.typography.titleSmall)
            Text(v.compatReasons, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Text("Certificate SHA-256", style = MaterialTheme.typography.titleSmall)
            Text(v.certSha256.ifEmpty { "—" }, style = MonoStyle)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.launch(v.id) }, modifier = Modifier.weight(1f).testTag("bedrock_launch")) { Text("Launch") }
                FilledTonalButton(onClick = { vm.requestInstall(v.id) }, modifier = Modifier.weight(1f).testTag("bedrock_install")) { Text("Install") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.select(v.id) }, enabled = !selected, modifier = Modifier.weight(1f)) { Text(if (selected) "Selected" else "Select") }
                OutlinedButton(onClick = { vm.verify(v.id) }, modifier = Modifier.weight(1f).testTag("bedrock_verify")) { Text("Verify") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { rename = true }, modifier = Modifier.weight(1f)) { Text("Rename") }
                OutlinedButton(onClick = { notes = true }, modifier = Modifier.weight(1f)) { Text("Notes") }
                TextButton(onClick = { delete = true }, modifier = Modifier.weight(1f).testTag("bedrock_delete")) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (rename) TextInputDialog("Rename", "Name", v.displayName, { vm.rename(v.id, it); rename = false }, { rename = false })
    if (notes) TextInputDialog("Notes", "Notes", v.notes, { vm.setNotes(v.id, it); notes = false }, { notes = false }, validate = { if (it.length > 500) "Too long" else null })
    if (delete) AlertDialog(
        modifier = Modifier.testTag("confirm_dialog"),
        onDismissRequest = { delete = false }, title = { Text("Remove ${v.displayName}?") },
        text = {
            Column {
                Text("The entry will be removed from the launcher library. The game installed on the device is not affected.")
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(deleteFiles, { deleteFiles = it }); Text("Also delete the stored APK copies (${FileOps.formatSize(v.totalSize)})") }
            }
        },
        confirmButton = { Button(onClick = { vm.delete(v.id, deleteFiles); delete = false; onDismiss() }, modifier = Modifier.testTag("confirm_button")) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ImportReportDialog(r: ImportReport, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag("import_report"),
        onDismissRequest = onDismiss, title = { Text("Import finished") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (r.imported.isNotEmpty()) Text("Added: ${r.imported.joinToString()}")
                if (r.duplicates.isNotEmpty()) Text("Already in library: ${r.duplicates.joinToString()}")
                r.failures.forEach { (n, m) -> Text("✗ $n — $m", color = MaterialTheme.colorScheme.error) }
                r.warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall) }
                if (r.imported.isEmpty() && r.failures.isEmpty() && r.duplicates.isEmpty()) Text("No files were processed.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
