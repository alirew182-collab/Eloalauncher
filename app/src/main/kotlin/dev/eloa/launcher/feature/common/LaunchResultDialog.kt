package dev.eloa.launcher.feature.common

import androidx.compose.ui.res.stringResource
import dev.eloa.launcher.R
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
            onDismissRequest = onDismiss, title = { Text(stringResource(R.string.launch_failed)) }, text = { Text(if (outcome.resId != 0) stringResource(outcome.resId) else outcome.message) },
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
                                    val local = issueText(i.code)
                                    Text(local?.first ?: i.message, style = MaterialTheme.typography.bodyMedium)
                                    if (local != null) Text(i.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    (local?.second ?: i.fix)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                                }
                            }
                        }
                        if (outcome.commandPreview != null) {
                            TextButton(onClick = { showCmd = !showCmd }) { Text(if (showCmd) stringResource(R.string.hide_launch_command) else stringResource(R.string.show_prepared_launch_command)) }
                            if (showCmd) { Spacer(Modifier.height(4.dp)); Text(outcome.commandPreview, style = MonoStyle, modifier = Modifier.fillMaxWidth()) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("launch_result_ok")) { Text("OK") } },
            )
        }
    }
}

/** Localized explanation and fix for known preflight codes; null → show the technical message. */
@Composable
private fun issueText(code: String): Pair<String, String?>? = when (code) {
    "NOT_INSTALLED" -> stringResource(R.string.pf_not_installed) to stringResource(R.string.pf_not_installed_fix)
    "MISSING_FILES" -> stringResource(R.string.pf_missing_files) to stringResource(R.string.pf_missing_files_fix)
    "NO_GAME_DIR" -> stringResource(R.string.pf_no_game_dir) to stringResource(R.string.pf_no_game_dir_fix)
    "PROCESSORS" -> stringResource(R.string.pf_processors) to stringResource(R.string.pf_processors_fix)
    "NO_RUNTIME" -> stringResource(R.string.pf_no_runtime) to stringResource(R.string.pf_no_runtime_fix)
    "OLD_RUNTIME" -> stringResource(R.string.pf_old_runtime) to stringResource(R.string.pf_no_runtime_fix)
    "LOW_RAM" -> stringResource(R.string.pf_low_ram) to stringResource(R.string.pf_low_ram_fix)
    "NO_BACKEND" -> stringResource(R.string.pf_no_backend) to stringResource(R.string.pf_no_backend_fix)
    "NO_RENDERER" -> stringResource(R.string.pf_no_renderer) to stringResource(R.string.pf_no_renderer_fix)
    "NO_WORLD" -> stringResource(R.string.pf_no_world) to stringResource(R.string.pf_no_world_fix)
    "VERSION_MISMATCH" -> stringResource(R.string.pf_version_mismatch) to null
    "AUTHLIB_MISSING" -> stringResource(R.string.pf_authlib_missing) to stringResource(R.string.pf_authlib_missing_fix)
    else -> null
}
