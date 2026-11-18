package dev.eloa.launcher.feature.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import dev.eloa.launcher.core.designsystem.component.ConfirmDialog
import dev.eloa.launcher.core.designsystem.component.EmptyState
import dev.eloa.launcher.core.designsystem.component.LabeledValue
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.ProgressInfo
import dev.eloa.launcher.core.designsystem.component.ProgressPanel
import dev.eloa.launcher.core.designsystem.component.StatusChip
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.formatDate
import dev.eloa.launcher.feature.common.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(private val engine: DiagnosticsEngine, private val messages: UserMessages) : ViewModel() {
    private val _report = MutableStateFlow<DiagnosticReport?>(null)
    val report: StateFlow<DiagnosticReport?> = _report
    private val _progress = MutableStateFlow<ProgressInfo?>(null)
    val progress: StateFlow<ProgressInfo?> = _progress
    private var job: Job? = null

    fun run() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            try {
                _progress.value = ProgressInfo("Running diagnostics", null, null)
                _report.value = engine.run { _progress.value = ProgressInfo("Running diagnostics", null, "Checking: $it") }
            } catch (e: Exception) { messages.error(e.userMessage()) } finally { _progress.value = null }
        }
    }

    fun cancel() { job?.cancel(); _progress.value = null }

    fun fix(f: AutoFix) = viewModelScope.launch {
        runCatching { engine.applyFix(f.id) }.onSuccess { messages.info(it); run() }.onFailure { messages.error(it.userMessage()) }
    }
}

private fun DiagSeverity.level() = when (this) { DiagSeverity.INFO -> StatusLevel.INFO; DiagSeverity.WARNING -> StatusLevel.WARNING; else -> StatusLevel.ERROR }

@Composable
fun DiagnosticsScreen(padding: PaddingValues, vm: DiagnosticsViewModel = hiltViewModel()) {
    val report by vm.report.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<AutoFix?>(null) }
    LazyColumn(Modifier.padding(padding).testTag("diagnostics"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Diagnostic centre", style = MaterialTheme.typography.titleLarge)
                    Text("Game files, storage, directories, APKs, Java, libraries, mods, configuration, network, memory and launch settings.", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = vm::run, enabled = progress == null, modifier = Modifier.testTag("run_diagnostics")) { Text(if (report == null) "Run" else "Run again") }
            }
        }
        item { ProgressPanel(progress, vm::cancel) }
        val r = report
        if (r == null && progress == null) item { EmptyState(Icons.Outlined.Analytics, "Not run yet", "Tap Run to check the launcher, game files and device.") }
        if (r != null) {
            item {
                Text("${r.checksRun} checks · ${r.issues.size} finding(s) · ${r.durationMs} ms · ${formatDate(r.timestamp)}", style = MaterialTheme.typography.labelMedium)
            }
            if (r.issues.isEmpty()) item { EmptyState(Icons.Outlined.CheckCircle, "No problems found", "Everything checked looks healthy.") }
            items(r.issues, key = { it.id }) { i ->
                LauncherCard(Modifier.fillMaxWidth().testTag("diag_issue")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(i.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        StatusChip(i.severity.name.lowercase(), i.severity.level())
                    }
                    Spacer(Modifier.height(6.dp))
                    LabeledValue("Component", i.component)
                    LabeledValue("Cause", i.cause)
                    LabeledValue("Solution", i.solution)
                    i.fix?.let { f ->
                        Spacer(Modifier.height(6.dp))
                        FilledTonalButton(onClick = { if (f.requiresConfirmation) confirm = f else vm.fix(f) }, modifier = Modifier.testTag("diag_fix")) {
                            Text(f.label + if (f.requiresConfirmation) "…" else "")
                        }
                    }
                }
            }
        }
    }
    confirm?.let { f -> ConfirmDialog("Apply fix?", f.confirmText ?: f.label, "Apply", { vm.fix(f); confirm = null }, { confirm = null }) }
}
