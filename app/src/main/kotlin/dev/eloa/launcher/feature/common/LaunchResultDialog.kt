package dev.eloa.launcher.feature.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.core.designsystem.component.statusColor
import dev.eloa.launcher.core.designsystem.component.statusIcon
import dev.eloa.launcher.core.designsystem.theme.MonoStyle
import dev.eloa.launcher.domain.java.launch.PreflightSeverity
import dev.eloa.launcher.feature.launch.LaunchOutcome

@Composable
fun LaunchResultDialog(outcome: LaunchOutcome?, onDismiss: () -> Unit) {
    when (outcome) {
        null, LaunchOutcome.Started -> return
        is LaunchOutcome.Failed -> AlertDialog(
            modifier = Modifier.testTag("launch_result"),
            onDismissRequest = onDismiss, title = { Text("Launch failed") }, text = { Text(outcome.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
        is LaunchOutcome.Blocked -> {
            var showCmd by remember { mutableStateOf(false) }
            AlertDialog(
                modifier = Modifier.testTag("launch_result"),
                onDismissRequest = onDismiss, title = { Text(outcome.title) },
                text = {
                    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                        outcome.issues.forEach { i ->
                            val lvl = if (i.severity == PreflightSeverity.BLOCKER) StatusLevel.ERROR else StatusLevel.WARNING
                            Row(Modifier.padding(vertical = 6.dp)) {
                                Icon(statusIcon(lvl), null, tint = statusColor(lvl), modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(i.message, style = MaterialTheme.typography.bodyMedium)
                                    i.fix?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                                }
                            }
                        }
                        if (outcome.commandPreview != null) {
                            TextButton(onClick = { showCmd = !showCmd }) { Text(if (showCmd) "Hide launch command" else "Show prepared launch command") }
                            if (showCmd) { Spacer(Modifier.height(4.dp)); Text(outcome.commandPreview, style = MonoStyle, modifier = Modifier.fillMaxWidth()) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("launch_result_ok")) { Text("OK") } },
            )
        }
    }
}
