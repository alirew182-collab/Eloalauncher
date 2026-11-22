package dev.eloa.launcher.feature.privacy

import androidx.compose.ui.res.stringResource
import dev.eloa.launcher.R
import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.eloa.launcher.core.designsystem.component.ConfirmDialog
import dev.eloa.launcher.core.designsystem.component.LabeledValue
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.SectionHeader
import dev.eloa.launcher.core.designsystem.theme.MonoStyle
import dev.eloa.launcher.domain.java.net.SourcePolicy
import dev.eloa.launcher.domain.java.update.UpdateChecker

/** What the launcher stores and contacts — stated plainly. There is no telemetry at all. */
@Composable
fun PrivacyScreen(padding: PaddingValues) {
    val context = LocalContext.current
    var wipe by remember { mutableStateOf(false) }
    LazyColumn(Modifier.padding(padding).testTag("privacy"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { SectionHeader(stringResource(R.string.telemetry)) }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.eloa_launcher_contains_no_analytics_advertising))
            }
        }
        item { SectionHeader(stringResource(R.string.stored_on_this_device)) }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                LabeledValue(stringResource(R.string.microsoft_tokens), stringResource(R.string.encrypted_with_aes_gcm_the_key))
                LabeledValue(stringResource(R.string.game_files), stringResource(R.string.private_app_storage_versions_libraries_profiles))
                LabeledValue(stringResource(R.string.logs), stringResource(R.string.local_database_tokens_and_other_secrets))
                LabeledValue(stringResource(R.string.your_files), stringResource(R.string.read_only_through_the_android_file))
                LabeledValue(stringResource(R.string.android_backup), stringResource(R.string.disabled_so_tokens_are_never_copied))
            }
        }
        item { SectionHeader(stringResource(R.string.network_connections)) }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.game_files_and_loaders_are_downloaded), style = MaterialTheme.typography.bodySmall)
                Text(SourcePolicy.TRUSTED_HOSTS.sorted().joinToString("\n"), style = MonoStyle)
                Text(stringResource(R.string.sign_in_login_microsoftonline_com_user), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.privacy_ely_hosts), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.modpacks_mrpack_only_the_download_hosts), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.launcher_updates_only_when_you_check) + UpdateChecker.GITHUB_HOSTS.sorted().joinToString(), style = MaterialTheme.typography.bodySmall)
            }
        }
        item { SectionHeader(stringResource(R.string.delete_everything)) }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.removes_all_launcher_data_accounts_and), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { wipe = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.testTag("wipe_data")) { Text(stringResource(R.string.delete_all_launcher_data)) }
            }
        }
    }
    if (wipe) ConfirmDialog(stringResource(R.string.delete_all_data), stringResource(R.string.this_cannot_be_undone_the_app), stringResource(R.string.delete_everything), {
        wipe = false
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
    }, { wipe = false }, destructive = true)
}
