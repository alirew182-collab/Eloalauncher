package dev.eloa.launcher.feature.logs

import androidx.compose.ui.res.stringResource
import dev.eloa.launcher.R
import dev.eloa.launcher.feature.common.fix
import dev.eloa.launcher.feature.common.title
import dev.eloa.launcher.feature.common.userMessage
import kotlinx.coroutines.flow.map
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eloa.launcher.core.data.db.LogDao
import dev.eloa.launcher.core.data.db.LogEntity
import dev.eloa.launcher.core.data.logging.DbLogger
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.core.designsystem.component.ConfirmDialog
import dev.eloa.launcher.core.designsystem.component.EmptyState
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.SearchField
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.core.designsystem.component.statusColor
import dev.eloa.launcher.core.designsystem.theme.MonoStyle
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.formatDate
import dev.eloa.launcher.feature.common.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

val ALL_LEVELS = listOf("DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL")

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LogsViewModel @Inject constructor(private val dao: LogDao, private val storage: LauncherStorage, private val messages: UserMessages) : ViewModel() {
    val levels = MutableStateFlow(setOf("INFO", "WARNING", "ERROR", "CRITICAL"))
    val query = MutableStateFlow("")
    val logs: StateFlow<List<LogEntity>?> = combine(levels, query.debounce(250)) { l, q -> l to q }
        .flatMapLatest { (l, q) -> dao.observe(l.toList(), q.trim(), 1000) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Known failure causes found in recent error entries of the launcher log. */
    val analysis: StateFlow<dev.eloa.launcher.domain.java.crash.CrashAnalysis?> = dao.observe(listOf("ERROR", "CRITICAL"), "", 200)
        .map { list -> if (list.isEmpty()) null else dev.eloa.launcher.domain.java.crash.CrashAnalyzer.analyze(list.joinToString("\n") { listOfNotNull(it.event, it.errorType, it.stackTrace).joinToString("\n") }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _fileAnalysis = MutableStateFlow<Pair<String, dev.eloa.launcher.domain.java.crash.CrashAnalysis>?>(null)
    val fileAnalysis: StateFlow<Pair<String, dev.eloa.launcher.domain.java.crash.CrashAnalysis>?> = _fileAnalysis

    /** Analyses a game log or crash report the user picks (for example logs/latest.log or a file from crash-reports). */
    fun analyzeFile(uri: Uri) = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                val tmp = storage.copyToTemp(uri, 32L * 1024 * 1024)
                try { storage.displayName(uri) to dev.eloa.launcher.domain.java.crash.CrashAnalyzer.analyze(dev.eloa.launcher.core.common.logging.SecretRedactor.redact(tmp.readText())) } finally { tmp.delete() }
            }
        }.onSuccess { _fileAnalysis.value = it }.onFailure { messages.error(it.userMessage()) }
    }

    fun toggle(level: String) { levels.value = if (level in levels.value) levels.value - level else levels.value + level }

    suspend fun exportText(): String = withContext(Dispatchers.IO) { dao.allAscending().joinToString("\n") { DbLogger.format(it) } }

    fun export(uri: Uri) = viewModelScope.launch {
        runCatching {
            val f = File(storage.importTemp, "eloa-log.txt").apply { parentFile?.mkdirs(); writeText(exportText()) }
            try { storage.exportTo(f, uri) } finally { f.delete() }
        }.onSuccess { messages.info(R.string.log_exported) }.onFailure { messages.error(it.userMessage()) }
    }

    fun copy(context: Context, text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("log", text.take(200_000)))
        messages.info(R.string.copied_to_clipboard)
    }

    fun copyAll(context: Context) = viewModelScope.launch { copy(context, exportText()) }
    fun clear() = viewModelScope.launch { dao.clear(); messages.info(R.string.log_cleared) }
}

private fun levelOf(l: String) = when (l) { "ERROR", "CRITICAL" -> StatusLevel.ERROR; "WARNING" -> StatusLevel.WARNING; "INFO" -> StatusLevel.INFO; else -> StatusLevel.OK }

@Composable
fun LogsScreen(padding: PaddingValues, vm: LogsViewModel = hiltViewModel()) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val fileAnalysis by vm.fileAnalysis.collectAsStateWithLifecycle()
    val analyzer = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { it?.let(vm::analyzeFile) }
    val levels by vm.levels.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(vm::export) }
    var clear by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    LazyColumn(Modifier.padding(padding).testTag("logs"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exporter.launch("eloa-log.txt") }) { Text(stringResource(R.string.action_export)) }
                OutlinedButton(onClick = { vm.copyAll(context) }) { Text(stringResource(R.string.copy_all)) }
                OutlinedButton(onClick = { clear = true }, modifier = Modifier.testTag("logs_clear")) { Text(stringResource(R.string.clear)) }
            }
        }
        item { CrashCard(analysis, fileAnalysis?.second, fileAnalysis?.first) { analyzer.launch(arrayOf("text/*", "application/octet-stream", "*/*")) } }
        item { SearchField(query, { vm.query.value = it }, placeholder = stringResource(R.string.search_component_event_or_error_type)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ALL_LEVELS.forEach { l -> FilterChip(l in levels, { vm.toggle(l) }, label = { Text(l.take(4)) }, modifier = Modifier.testTag("level_$l")) }
            }
        }
        val list = logs
        if (list != null && list.isEmpty()) item { EmptyState(Icons.Outlined.BugReport, stringResource(R.string.no_log_entries), stringResource(R.string.nothing_matches_the_current_filter)) }
        items(list.orEmpty(), key = { it.id }) { e ->
            LauncherCard(Modifier.fillMaxWidth().clickable { expanded = if (expanded == e.id) null else e.id }.testTag("log_entry"), contentPadding = PaddingValues(12.dp)) {
                Row {
                    Text(e.level, color = statusColor(levelOf(e.level)), style = MaterialTheme.typography.labelMedium)
                    Text("  ${e.component} · ${formatDate(e.timestamp)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(e.event, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded == e.id) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                e.errorType?.let { Text(it, style = MonoStyle, color = MaterialTheme.colorScheme.error) }
                if (expanded == e.id) {
                    e.context?.let { Text(it, style = MonoStyle) }
                    e.stackTrace?.let { Text(it.take(6000), style = MonoStyle) }
                    Column { TextButton(onClick = { vm.copy(context, DbLogger.format(e)) }) { Text(stringResource(R.string.copy_entry)) } }
                }
            }
        }
    }
    if (clear) ConfirmDialog(stringResource(R.string.clear_the_log), stringResource(R.string.all_log_entries_will_be_deleted), stringResource(R.string.clear), { vm.clear(); clear = false }, { clear = false }, destructive = true)
}

@Composable
private fun CrashCard(launcher: dev.eloa.launcher.domain.java.crash.CrashAnalysis?, file: dev.eloa.launcher.domain.java.crash.CrashAnalysis?, fileName: String?, onPick: () -> Unit) {
    dev.eloa.launcher.core.designsystem.component.LauncherCard(Modifier.fillMaxWidth().testTag("crash_card")) {
        Text(stringResource(R.string.crash_analysis), style = MaterialTheme.typography.titleMedium)
        val shown = file ?: launcher
        if (shown == null || shown.findings.isEmpty()) {
            Text(stringResource(R.string.crash_none_found), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            if (fileName != null) Text(fileName, style = dev.eloa.launcher.core.designsystem.theme.MonoStyle)
            shown.findings.take(3).forEach { f ->
                Text(f.cause.title() + (f.detail?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Text(f.cause.fix(), style = MaterialTheme.typography.bodySmall)
                Text(f.evidence, style = dev.eloa.launcher.core.designsystem.theme.MonoStyle, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            shown.exceptionHeadline?.let { Text(it, style = dev.eloa.launcher.core.designsystem.theme.MonoStyle, maxLines = 2) }
        }
        androidx.compose.material3.OutlinedButton(onClick = onPick, modifier = Modifier.testTag("analyze_file")) { Text(stringResource(R.string.crash_analyze_file)) }
    }
}
