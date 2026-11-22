package dev.eloa.launcher.feature.notifications

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eloa.launcher.R
import dev.eloa.launcher.core.data.db.NotificationEntity
import dev.eloa.launcher.core.designsystem.component.ConfirmDialog
import dev.eloa.launcher.core.designsystem.component.EmptyState
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.core.designsystem.component.statusColor
import dev.eloa.launcher.core.designsystem.component.statusIcon
import dev.eloa.launcher.feature.common.formatDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(private val center: NotificationCenter) : ViewModel() {
    val items: StateFlow<List<NotificationEntity>> = center.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun markAllRead() = viewModelScope.launch { center.markAllRead() }
    fun open(n: NotificationEntity) = viewModelScope.launch { center.markRead(n.id) }
    fun delete(id: Long) = viewModelScope.launch { center.delete(id) }
    fun clear() = viewModelScope.launch { center.clear() }
}

private fun level(type: String) = when (type) {
    "INSTALL_FAILED", "DAMAGED_FILE", "OVERHEATING" -> StatusLevel.ERROR
    "LOW_STORAGE", "INCOMPATIBLE_MOD" -> StatusLevel.WARNING
    "DOWNLOAD_DONE" -> StatusLevel.OK
    else -> StatusLevel.INFO
}

@Composable
fun NotificationsScreen(padding: PaddingValues, onOpenRoute: (String) -> Unit, vm: NotificationsViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    var clear by remember { mutableStateOf(false) }
    LazyColumn(Modifier.padding(padding).testTag("notifications"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::markAllRead, enabled = items.any { !it.read }) { Text(stringResource(R.string.mark_all_as_read)) }
                OutlinedButton(onClick = { clear = true }, enabled = items.isNotEmpty(), modifier = Modifier.testTag("notifications_clear")) { Text(stringResource(R.string.clear_all)) }
            }
        }
        if (items.isEmpty()) item { EmptyState(Icons.Outlined.Notifications, stringResource(R.string.no_notifications), stringResource(R.string.finished_downloads_install_errors_low_storage)) }
        items(items, key = { it.id }) { n ->
            val lvl = level(n.type)
            LauncherCard(Modifier.fillMaxWidth().testTag("notification_item"), onClick = { vm.open(n); n.route?.let(onOpenRoute) }) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(statusIcon(lvl), null, tint = statusColor(lvl))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n.title, fontWeight = if (n.read) FontWeight.Normal else FontWeight.Bold)
                        Text(n.message, style = MaterialTheme.typography.bodySmall)
                        Text(formatDate(n.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.delete(n.id) }) { Icon(Icons.Outlined.Delete, LocalContext.current.getString(R.string.action_delete)) }
                }
            }
        }
    }
    if (clear) ConfirmDialog(stringResource(R.string.clear_all_notifications), stringResource(R.string.the_notification_history_will_be_deleted), stringResource(R.string.clear), { vm.clear(); clear = false }, { clear = false }, destructive = true)
}
