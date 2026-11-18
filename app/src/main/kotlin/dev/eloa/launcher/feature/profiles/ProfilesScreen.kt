package dev.eloa.launcher.feature.profiles

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eloa.launcher.core.data.db.JavaProfileEntity
import dev.eloa.launcher.core.designsystem.component.EmptyState
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.SkeletonList
import dev.eloa.launcher.core.designsystem.component.StatusChip
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.feature.common.ProfileArt
import dev.eloa.launcher.feature.common.ProfileAvatar
import dev.eloa.launcher.feature.common.formatDate
import dev.eloa.launcher.feature.java.InstallState
import dev.eloa.launcher.feature.java.state

@Composable
fun ProfilesScreen(padding: PaddingValues, onEdit: (String?) -> Unit, vm: ProfilesViewModel = hiltViewModel()) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val selected by vm.selectedId.collectAsStateWithLifecycle()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
    var exportId by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> val id = exportId; if (uri != null && id != null) vm.export(id, uri) }
    var deleting by remember { mutableStateOf<JavaProfileEntity?>(null) }
    var cloning by remember { mutableStateOf<JavaProfileEntity?>(null) }

    Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("profiles")) {
            item { Row { OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "*/*")) }) { Text("Import configuration") } } }
            val list = profiles
            when {
                list == null -> item { SkeletonList(3) }
                list.isEmpty() -> item { EmptyState(Icons.Outlined.People, "No profiles yet", "Create profiles like “Vanilla”, “Fabric Performance” or “RPG Modpack” — each with its own version, loader, mods and settings.", actionLabel = "New profile", onAction = { onEdit(null) }) }
                else -> items(list, key = { it.id }) { p -> ProfileCard(p, p.id == selected || (selected.isEmpty() && p == list.first()), vm, onEdit, { exportId = p.id; exporter.launch("${p.name}.json") }, { cloning = p }, { deleting = p }) }
            }
        }
        ExtendedFloatingActionButton(onClick = { onEdit(null) }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("New profile") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).testTag("new_profile"))
    }
    deleting?.let { p ->
        var files by remember { mutableStateOf(false) }
        AlertDialog(
            modifier = Modifier.testTag("confirm_dialog"),
            onDismissRequest = { deleting = null }, title = { Text("Delete “${p.name}”?") },
            text = { Column { Text("The profile will be removed. Shared game versions stay installed."); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(files, { files = it }); Text("Also delete its game folder (mods, worlds, configs)") } } },
            confirmButton = { Button(onClick = { vm.delete(p.id, files); deleting = null }, modifier = Modifier.testTag("confirm_button")) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    cloning?.let { p ->
        AlertDialog(
            onDismissRequest = { cloning = null }, title = { Text("Clone “${p.name}”") },
            text = { Text("Copy only the settings, or the settings together with the game folder (mods, configs, worlds)?") },
            confirmButton = { Button(onClick = { vm.clone(p.id, true); cloning = null }) { Text("With files") } },
            dismissButton = { TextButton(onClick = { vm.clone(p.id, false); cloning = null }) { Text("Settings only") } },
        )
    }
}

@Composable
private fun ProfileCard(p: JavaProfileEntity, selected: Boolean, vm: ProfilesViewModel, onEdit: (String?) -> Unit, onExport: () -> Unit, onClone: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    LauncherCard(Modifier.fillMaxWidth().testTag("profile_card"), onClick = { onEdit(p.id) }, contentPadding = PaddingValues(0.dp)) {
        Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(Brush.horizontalGradient(ProfileArt.cover(p.coverKey) + Color.Transparent)))
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(p.iconKey, p.coverKey, 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.minecraftVersion} · ${p.loader.lowercase().replaceFirstChar { it.uppercase() }} ${p.loaderVersion ?: ""} · ${p.maxMemoryMb} MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Created ${formatDate(p.createdAt)} · last played ${formatDate(p.lastPlayedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (selected) StatusChip("Selected", StatusLevel.INFO)
                    when (p.state) {
                        InstallState.INSTALLED -> StatusChip("Ready", StatusLevel.OK)
                        InstallState.FAILED -> StatusChip("Install failed", StatusLevel.ERROR)
                        InstallState.INSTALLING, InstallState.QUEUED -> StatusChip("Installing", StatusLevel.INFO)
                        InstallState.NEEDS_PROCESSORS -> StatusChip("Needs runtime", StatusLevel.WARNING)
                        InstallState.NOT_INSTALLED -> StatusChip("Not installed", StatusLevel.WARNING)
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("profile_menu")) { Icon(Icons.Outlined.MoreVert, "Actions") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Select") }, { menu = false; vm.select(p.id) })
                    DropdownMenuItem({ Text("Edit") }, { menu = false; onEdit(p.id) })
                    DropdownMenuItem({ Text(if (p.state == InstallState.INSTALLED) "Repair files" else "Install") }, { menu = false; vm.install(p.id) })
                    DropdownMenuItem({ Text("Clone") }, { menu = false; onClone() })
                    DropdownMenuItem({ Text("Export configuration") }, { menu = false; onExport() })
                    DropdownMenuItem({ Text("Delete", color = MaterialTheme.colorScheme.error) }, { menu = false; onDelete() }, modifier = Modifier.testTag("profile_delete"))
                }
            }
        }
    }
}
