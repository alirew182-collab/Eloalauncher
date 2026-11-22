package dev.eloa.launcher.feature.modpacks

import dev.eloa.launcher.feature.common.str
import androidx.compose.ui.res.stringResource
import dev.eloa.launcher.R
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eloa.launcher.core.designsystem.component.LabeledValue
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.ProgressInfo
import dev.eloa.launcher.core.designsystem.component.ProgressPanel
import dev.eloa.launcher.feature.common.ProfileSelection
import dev.eloa.launcher.feature.common.ProfileSelectorBar
import dev.eloa.launcher.feature.common.SelectedProfile
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.userMessage
import dev.eloa.launcher.feature.java.ModpackPreview
import dev.eloa.launcher.feature.java.ModpackRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ModpacksViewModel @Inject constructor(
    private val repo: ModpackRepository,
    private val selected: SelectedProfile,
    private val messages: UserMessages,
) : ViewModel() {
    val selection: StateFlow<ProfileSelection> = selected.selection.stateIn(viewModelScope, SharingStarted.Eagerly, ProfileSelection(emptyList(), null))
    private val _preview = MutableStateFlow<Pair<File, ModpackPreview>?>(null)
    val preview: StateFlow<Pair<File, ModpackPreview>?> = _preview
    private val _progress = MutableStateFlow<ProgressInfo?>(null)
    val progress: StateFlow<ProgressInfo?> = _progress

    fun selectProfile(id: String) = viewModelScope.launch { selected.select(id) }

    fun open(uri: Uri) = viewModelScope.launch {
        _progress.value = ProgressInfo(str(R.string.reading_modpack), null, null, cancellable = false)
        try { _preview.value = repo.preview(uri) } catch (e: Exception) { messages.error(e.userMessage()) } finally { _progress.value = null }
    }

    fun cancelPreview() { _preview.value?.first?.delete(); _preview.value = null }

    fun install() {
        val (file, p) = _preview.value ?: return
        _preview.value = null
        viewModelScope.launch {
            runCatching { repo.install(file, p) }.onSuccess { messages.info(R.string.profile_1_s_created_files_are, p.name) }.onFailure { file.delete(); messages.error(it.userMessage()) }
        }
    }

    fun export(uri: Uri, worlds: Boolean) {
        val p = selection.value.selected ?: return
        viewModelScope.launch {
            _progress.value = ProgressInfo(str(R.string.exporting_1_s, p.name), null, null, cancellable = false)
            try { messages.info(R.string.exported_1_s_files, repo.export(p, uri, worlds)) } catch (e: Exception) { messages.error(e.userMessage()) } finally { _progress.value = null }
        }
    }
}

@Composable
fun ModpacksScreen(padding: PaddingValues, vm: ModpacksViewModel = hiltViewModel()) {
    val selection by vm.selection.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    var worlds by rememberSaveable { mutableStateOf(false) }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::open) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let { u -> vm.export(u, worlds) } }
    Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).testTag("modpacks"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ProgressPanel(progress, {})
        LauncherCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.import_a_modpack), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.supported_modrinth_packs_mrpack_and_packs),
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Button(onClick = { opener.launch(arrayOf("*/*")) }, modifier = Modifier.testTag("modpack_import")) { Text(stringResource(R.string.choose_file)) }
        }
        LauncherCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.export_a_profile_as_modpack), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            ProfileSelectorBar(selection, vm::selectProfile)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(worlds, { worlds = it }); Text(stringResource(R.string.include_worlds_saves)) }
            Text(stringResource(R.string.includes_mods_config_resource_packs_shader), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = { exporter.launch("${selection.selected?.name ?: "profile"}.eloapack") }, enabled = selection.selected != null) { Text(stringResource(R.string.export_eloapack)) }
        }
    }
    preview?.let { (_, p) ->
        AlertDialog(
            modifier = Modifier.testTag("modpack_preview"),
            onDismissRequest = vm::cancelPreview, title = { Text(p.name) },
            text = {
                Column {
                    p.summary?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    LabeledValue(stringResource(R.string.format), p.format)
                    LabeledValue(stringResource(R.string.minecraft), p.minecraftVersion)
                    LabeledValue(stringResource(R.string.loader), "${p.loader} ${p.loaderVersion ?: ""}")
                    LabeledValue(if (p.format == stringResource(R.string.modrinth)) stringResource(R.string.files_to_download) else stringResource(R.string.bundled_files), p.fileCount.toString())
                }
            },
            confirmButton = { Button(onClick = vm::install) { Text(stringResource(R.string.create_profile_install)) } },
            dismissButton = { TextButton(onClick = vm::cancelPreview) { Text(stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_cancel)) } },
        )
    }
}
