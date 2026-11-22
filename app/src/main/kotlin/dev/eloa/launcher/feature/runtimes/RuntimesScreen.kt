package dev.eloa.launcher.feature.runtimes

import androidx.compose.ui.res.stringResource
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eloa.launcher.R
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.designsystem.component.ConfirmDialog
import dev.eloa.launcher.core.designsystem.component.EmptyState
import dev.eloa.launcher.core.designsystem.component.LabeledValue
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.ProgressInfo
import dev.eloa.launcher.core.designsystem.component.ProgressPanel
import dev.eloa.launcher.core.designsystem.component.SectionHeader
import dev.eloa.launcher.core.designsystem.component.StatusChip
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.domain.java.runtime.JavaRuntimeInfo
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.userMessage
import dev.eloa.launcher.feature.java.JavaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class RuntimeRow(val info: JavaRuntimeInfo, val sizeBytes: Long, val verified: Boolean?)

@HiltViewModel
class RuntimesViewModel @Inject constructor(
    private val java: JavaRepository,
    private val messages: UserMessages,
) : ViewModel() {
    private val _rows = MutableStateFlow<List<RuntimeRow>>(emptyList())
    val rows: StateFlow<List<RuntimeRow>> = _rows
    private val _progress = MutableStateFlow<ProgressInfo?>(null)
    val progress: StateFlow<ProgressInfo?> = _progress

    init { load() }

    fun load() = viewModelScope.launch {
        _rows.value = withContext(Dispatchers.IO) { java.runtimes().map { RuntimeRow(it, java.runtimeSize(it), null) } }
    }

    fun import(uri: android.net.Uri, title: String) = viewModelScope.launch {
        _progress.value = ProgressInfo(title, null, null, cancellable = false)
        runCatching { java.importRuntime(uri) }.onSuccess { messages.info(R.string.msg_runtime_imported, it.majorVersion) }.onFailure { messages.error(it.userMessage()) }
        _progress.value = null
        load()
    }

    fun verify(r: RuntimeRow) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) { java.verifyRuntime(r.info) }
        _rows.value = _rows.value.map { if (it.info.home == r.info.home) it.copy(verified = ok) else it }
        if (ok) messages.info(R.string.msg_verify_ok) else messages.error(R.string.msg_verify_failed)
    }

    fun delete(r: RuntimeRow) = viewModelScope.launch {
        withContext(Dispatchers.IO) { java.deleteRuntime(r.info) }
        messages.info(R.string.msg_runtime_deleted)
        load()
    }
}

@Composable
fun RuntimesScreen(padding: PaddingValues, vm: RuntimesViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val importing = androidx.compose.ui.res.stringResource(R.string.progress_importing)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.import(u, importing) } }
    var delete by remember { mutableStateOf<RuntimeRow?>(null) }
    LazyColumn(Modifier.padding(padding).testTag("runtimes"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                LabeledValue(stringResource(R.string.device_architecture), Build.SUPPORTED_ABIS.joinToString())
                LabeledValue(stringResource(R.string.java_8), stringResource(R.string.minecraft_1_16_5_and_older))
                LabeledValue(stringResource(R.string.java_17), stringResource(R.string.minecraft_1_17_to_1_20))
                LabeledValue(stringResource(R.string.java_21), stringResource(R.string.minecraft_1_20_5_and_newer))
                Text(stringResource(R.string.the_required_version_is_read_from), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.import_a_java_runtime_built_for), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { picker.launch(arrayOf("application/zip", "*/*")) }, enabled = progress == null, modifier = Modifier.testTag("runtime_import")) { Text(stringResource(R.string.import_runtime)) }
            }
        }
        item { ProgressPanel(progress, {}) }
        item { SectionHeader(stringResource(R.string.installed)) }
        if (rows.isEmpty()) item { EmptyState(Icons.Outlined.Memory, stringResource(R.string.no_runtimes), stringResource(R.string.java_edition_needs_at_least_one)) }
        items(rows, key = { it.info.home.absolutePath }) { r ->
            LauncherCard(Modifier.fillMaxWidth().testTag("runtime_row")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.java_1_s, r.info.majorVersion), style = MaterialTheme.typography.titleSmall)
                        Text("${r.info.fullVersion} · ${r.info.arch} · ${FileOps.formatSize(r.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
                        r.info.vendor?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    when (r.verified) { true -> StatusChip(stringResource(R.string.intact), StatusLevel.OK); false -> StatusChip(stringResource(R.string.damaged), StatusLevel.ERROR); null -> Unit }
                }
                Row {
                    TextButton(onClick = { vm.verify(r) }) { Text(stringResource(R.string.verify)) }
                    TextButton(onClick = { delete = r }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    delete?.let { r -> ConfirmDialog(stringResource(R.string.delete_java_1_s, r.info.majorVersion), stringResource(R.string.s_1_s_will_be_freed_profiles, FileOps.formatSize(r.sizeBytes)), stringResource(R.string.action_delete), { vm.delete(r); delete = null }, { delete = null }, destructive = true) }
}
