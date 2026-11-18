package dev.eloa.launcher.feature.logs

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

    fun toggle(level: String) { levels.value = if (level in levels.value) levels.value - level else levels.value + level }

    suspend fun exportText(): String = withContext(Dispatchers.IO) { dao.allAscending().joinToString("\n") { DbLogger.format(it) } }

    fun export(uri: Uri) = viewModelScope.launch {
        runCatching {
            val f = File(storage.importTemp, "eloa-log.txt").apply { parentFile?.mkdirs(); writeText(exportText()) }
            try { storage.exportTo(f, uri) } finally { f.delete() }
        }.onSuccess { messages.info("Log exported") }.onFailure { messages.error(it.userMessage()) }
    }

    fun copy(context: Context, text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("log", text.take(200_000)))
        messages.info("Copied to clipboard")
    }

    fun copyAll(context: Context) = viewModelScope.launch { copy(context, exportText()) }
    fun clear() = viewModelScope.launch { dao.clear(); messages.info("Log cleared") }
}

private fun levelOf(l: String) = when (l) { "ERROR", "CRITICAL" -> StatusLevel.ERROR; "WARNING" -> StatusLevel.WARNING; "INFO" -> StatusLevel.INFO; else -> StatusLevel.OK }

@Composable
fun LogsScreen(padding: PaddingValues, vm: LogsViewModel = hiltViewModel()) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val levels by vm.levels.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(vm::export) }
    var clear by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    LazyColumn(Modifier.padding(padding).testTag("logs"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exporter.launch("eloa-log.txt") }) { Text("Export") }
                OutlinedButton(onClick = { vm.copyAll(context) }) { Text("Copy all") }
                OutlinedButton(onClick = { clear = true }, modifier = Modifier.testTag("logs_clear")) { Text("Clear") }
            }
        }
        item { SearchField(query, { vm.query.value = it }, placeholder = "Search component, event or error type") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ALL_LEVELS.forEach { l -> FilterChip(l in levels, { vm.toggle(l) }, label = { Text(l.take(4)) }, modifier = Modifier.testTag("level_$l")) }
            }
        }
        val list = logs
        if (list != null && list.isEmpty()) item { EmptyState(Icons.Outlined.BugReport, "No log entries", "Nothing matches the current filter.") }
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
                    Column { TextButton(onClick = { vm.copy(context, DbLogger.format(e)) }) { Text("Copy entry") } }
                }
            }
        }
    }
    if (clear) ConfirmDialog("Clear the log?", "All log entries will be deleted. Export them first if you need them for a bug report.", "Clear", { vm.clear(); clear = false }, { clear = false }, destructive = true)
}
