package dev.eloa.launcher.feature.addonsui

import android.content.ActivityNotFoundException
import android.content.Intent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.data.db.AddonEntity
import dev.eloa.launcher.core.designsystem.component.ChipRow
import dev.eloa.launcher.core.designsystem.component.ConfirmDialog
import dev.eloa.launcher.core.designsystem.component.EmptyState
import dev.eloa.launcher.core.designsystem.component.LabeledValue
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.ProgressPanel
import dev.eloa.launcher.core.designsystem.component.SearchField
import dev.eloa.launcher.core.designsystem.component.SkeletonList
import dev.eloa.launcher.core.designsystem.component.StatusChip
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.core.designsystem.component.TextInputDialog
import dev.eloa.launcher.core.designsystem.theme.MonoStyle
import dev.eloa.launcher.domain.bedrock.apk.Severity
import dev.eloa.launcher.feature.addons.packs
import dev.eloa.launcher.feature.common.formatDate

private val TYPE_FILTERS = listOf("All", "BEHAVIOR", "RESOURCE", "WORLD", "SKIN", "STRUCTURE", "Problems", "Disabled")

@Composable
fun AddonsScreen(padding: PaddingValues, vm: AddonsViewModel = hiltViewModel()) {
    val addons by vm.addons.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val details by vm.details.collectAsStateWithLifecycle()
    val folder by vm.mojangFolder.collectAsStateWithLifecycle()
    val intent by vm.launchIntent.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(intent) {
        intent?.let { i ->
            try { context.startActivity(i) } catch (_: ActivityNotFoundException) {
                context.startActivity(Intent.createChooser(Intent(i).setPackage(null), "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            vm.consumeIntent()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.import(it) }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(vm::backup) }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            vm.setMojangFolder(it)
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var category by rememberSaveable { mutableStateOf("All categories") }

    Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("addons")) {
            item { ProgressPanel(progress, {}) }
            item {
                LauncherCard(Modifier.fillMaxWidth()) {
                    Text("Installing into Minecraft", style = MaterialTheme.typography.titleSmall)
                    Text("“Open in Minecraft” hands the file to the game, which imports it itself (recommended). Alternatively link a games/com.mojang folder you can access (e.g. when the game uses external storage) to copy packs and worlds directly.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { tree.launch(null) }) { Text(if (folder.isEmpty()) "Link com.mojang folder" else "Change folder") }
                        if (folder.isNotEmpty()) TextButton(onClick = vm::unlinkMojangFolder) { Text("Unlink") }
                        OutlinedButton(onClick = { backup.launch("bedrock-addons-backup.zip") }, enabled = !addons.isNullOrEmpty()) { Text("Backup all") }
                    }
                }
            }
            item { SearchField(query, { query = it }, placeholder = "Search add-ons") }
            item { ChipRow(TYPE_FILTERS, filter, { it.lowercase().replaceFirstChar { c -> c.uppercase() } }, { filter = it }) }
            val list = addons
            if (list == null) { item { SkeletonList(4) }; return@LazyColumn }
            val categories = listOf("All categories") + list.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
            if (categories.size > 2) item { ChipRow(categories, category, { it }, { category = it }) }
            val shown = list.filter { a ->
                (query.isBlank() || a.name.contains(query, true) || a.description.contains(query, true)) &&
                    (category == "All categories" || a.category == category) &&
                    when (filter) { "All" -> true; "Problems" -> a.hasErrors; "Disabled" -> !a.enabled; else -> a.packTypes.split(',').contains(filter) }
            }
            if (shown.isEmpty()) item {
                EmptyState(Icons.Outlined.Widgets, if (list.isEmpty()) "No add-ons yet" else "No matches",
                    "Import .mcpack, .mcaddon, .mcworld, .mctemplate, .mcstructure or .zip. Manifests, UUIDs, dependencies and versions are checked first.",
                    actionLabel = if (list.isEmpty()) "Import" else null, onAction = { picker.launch(arrayOf("*/*")) })
            }
            items(shown, key = { it.id }) { a -> AddonCard(a, { vm.open(a) }, { vm.toggle(a) }) }
        }
        ExtendedFloatingActionButton(onClick = { picker.launch(arrayOf("*/*")) }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Import") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).testTag("addon_import"))
    }
    results?.let { r ->
        AlertDialog(
            modifier = Modifier.testTag("addon_results"),
            onDismissRequest = vm::dismissResults, title = { Text("Import results") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    r.forEach { res ->
                        Text((if (res.ok) "✓ " else "✗ ") + "${res.name}: ${res.message}", color = if (res.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                        res.findings.filter { it.severity != Severity.INFO }.forEach { Text("   • ${it.message}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissResults) { Text("OK") } },
        )
    }
    details?.let { d -> AddonSheet(d, folder.isNotEmpty(), vm) }
}

@Composable
private fun AddonCard(a: AddonEntity, onOpen: () -> Unit, onToggle: () -> Unit) {
    LauncherCard(Modifier.fillMaxWidth().testTag("addon_item"), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${a.kind.lowercase()} · ${a.packTypes.lowercase().replace(",", ", ")} · ${a.version ?: ""} · ${FileOps.formatSize(a.sizeBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (a.category.isNotBlank()) StatusChip(a.category, StatusLevel.INFO)
                    if (a.hasErrors) StatusChip("Problems", StatusLevel.ERROR)
                }
            }
            Switch(a.enabled, { onToggle() }, modifier = Modifier.testTag("addon_toggle"))
        }
    }
}

@Composable
private fun AddonSheet(d: AddonDetails, folderLinked: Boolean, vm: AddonsViewModel) {
    val a = d.addon
    var confirmDelete by remember { mutableStateOf(false) }
    var editCategory by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = vm::closeDetails) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text(a.name, style = MaterialTheme.typography.headlineSmall)
            if (a.description.isNotBlank()) Text(a.description, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            LabeledValue("File", a.fileName)
            LabeledValue("Type", "${a.kind} · ${a.packTypes}")
            LabeledValue("Version", a.version ?: "—")
            LabeledValue("Min. game version", a.minEngineVersion ?: "—")
            LabeledValue("Size", FileOps.formatSize(a.sizeBytes))
            LabeledValue("Imported", formatDate(a.importedAt))
            LabeledValue("Category", a.category.ifBlank { "—" })
            a.packs().forEach { p ->
                Spacer(Modifier.height(6.dp))
                Text("${p.type.lowercase()} pack: ${p.name} ${p.version}", style = MaterialTheme.typography.titleSmall)
                Text("UUID ${p.uuid}", style = MonoStyle)
                if (p.dependencies.isNotEmpty()) Text("Depends on: ${p.dependencies.joinToString()}", style = MaterialTheme.typography.bodySmall)
            }
            if (d.conflicts.isNotEmpty() || a.findings.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("Checks", style = MaterialTheme.typography.titleSmall)
                (d.conflicts.map { "${it.severity}: ${it.message}" } + a.findings.lines().filter { it.isNotBlank() }).distinct().forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
            Spacer(Modifier.height(8.dp))
            Text("Contents (${d.contents.size} files)", style = MaterialTheme.typography.titleSmall)
            d.contents.take(40).forEach { (n, s) -> Text("$n · ${FileOps.formatSize(s)}", style = MonoStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (d.contents.size > 40) Text("…and ${d.contents.size - 40} more", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.openInMinecraft(a) }, modifier = Modifier.weight(1f).testTag("addon_open_mc")) { Text("Open in Minecraft") }
                FilledTonalButton(onClick = { vm.copyToMojang(a) }, enabled = folderLinked && a.kind != "MCSTRUCTURE", modifier = Modifier.weight(1f)) { Text("Copy to com.mojang") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { rename = true }, modifier = Modifier.weight(1f)) { Text("Rename") }
                OutlinedButton(onClick = { editCategory = true }, modifier = Modifier.weight(1f)) { Text("Category") }
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.width(1.dp))
        }
    }
    if (confirmDelete) ConfirmDialog("Delete ${a.name}?", "The add-on file will be removed from the launcher library. Copies already imported into Minecraft are not affected.", "Delete", { vm.delete(a); confirmDelete = false }, { confirmDelete = false }, destructive = true)
    if (editCategory) TextInputDialog("Category", "Category", a.category, { vm.setCategory(a, it); editCategory = false }, { editCategory = false }, validate = { if (it.length > 40) "Too long" else null })
    if (rename) TextInputDialog("Rename", "Name", a.name, { vm.rename(a, it); rename = false }, { rename = false })
}
