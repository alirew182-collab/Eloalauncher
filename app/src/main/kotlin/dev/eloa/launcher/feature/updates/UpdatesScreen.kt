package dev.eloa.launcher.feature.updates

import androidx.compose.ui.res.stringResource
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eloa.launcher.BuildConfig
import dev.eloa.launcher.R
import dev.eloa.launcher.core.data.settings.LauncherSettings
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.core.designsystem.component.LabeledValue
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.ProgressInfo
import dev.eloa.launcher.core.designsystem.component.ProgressPanel
import dev.eloa.launcher.core.designsystem.component.SectionHeader
import dev.eloa.launcher.core.designsystem.component.StatusChip
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.core.designsystem.theme.MonoStyle
import dev.eloa.launcher.domain.java.update.UpdateResult
import dev.eloa.launcher.feature.bedrock.PackageInstallerGateway
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.formatDate
import dev.eloa.launcher.feature.common.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdatesViewModel @Inject constructor(
    private val repo: UpdateRepository,
    private val settings: SettingsRepository,
    private val installer: PackageInstallerGateway,
    private val messages: UserMessages,
) : ViewModel() {
    val s: StateFlow<LauncherSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, LauncherSettings())
    private val _result = MutableStateFlow<UpdateResult?>(null)
    val result: StateFlow<UpdateResult?> = _result
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking
    private val _progress = MutableStateFlow<ProgressInfo?>(null)
    val progress: StateFlow<ProgressInfo?> = _progress
    private var job: Job? = null

    fun check() = viewModelScope.launch {
        _checking.value = true
        runCatching { repo.check() }.onSuccess { _result.value = it }.onFailure { messages.error(it.userMessage()) }
        _checking.value = false
    }

    fun install(title: String) {
        val r = _result.value as? UpdateResult.Available ?: return
        if (!installer.canRequestInstalls()) { messages.error(R.string.msg_allow_installs); return }
        job = viewModelScope.launch {
            _progress.value = ProgressInfo(title, 0f, null)
            runCatching { repo.downloadAndInstall(r.info) { f -> _progress.value = ProgressInfo(title, f, null) } }
                .onFailure { messages.error(it.userMessage()) }
            _progress.value = null
        }
    }

    fun cancel() { job?.cancel(); _progress.value = null }
    fun permissionIntent(): Intent = installer.unknownSourcesSettingsIntent()
    fun setAuto(on: Boolean) = viewModelScope.launch { settings.update { it.copy(autoUpdateCheck = on) }; repo.scheduleOrCancel() }
    fun setPrerelease(on: Boolean) = viewModelScope.launch { settings.update { it.copy(includePrereleases = on) } }
}

@Composable
fun UpdatesScreen(padding: PaddingValues, vm: UpdatesViewModel = hiltViewModel()) {
    val s by vm.s.collectAsStateWithLifecycle()
    val r by vm.result.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val downloading = context.getString(R.string.progress_downloading_update)
    LazyColumn(Modifier.padding(padding).testTag("updates"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                LabeledValue(stringResource(R.string.installed_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                LabeledValue(stringResource(R.string.source), "github.com/${BuildConfig.UPDATE_REPO}/releases")
                LabeledValue(stringResource(R.string.last_check), formatDate(s.lastUpdateCheckAt))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = vm::check, enabled = !checking, modifier = Modifier.testTag("check_updates")) { Text(stringResource(R.string.check_now)) }
                    if (checking) CircularProgressIndicator(Modifier.padding(start = 12.dp))
                }
            }
        }
        item { ProgressPanel(progress, vm::cancel) }
        when (val x = r) {
            is UpdateResult.Available -> item {
                LauncherCard(Modifier.fillMaxWidth().testTag("update_available")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.version_1_s, x.info.version), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (x.info.sha256 != null) StatusChip(stringResource(R.string.checksum_published), StatusLevel.OK) else StatusChip(stringResource(R.string.no_checksum), StatusLevel.ERROR)
                    }
                    x.info.publishedAt?.let { Text(it.take(10), style = MaterialTheme.typography.labelSmall) }
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) { Text(x.info.notes.ifBlank { "—" }, style = MonoStyle) }
                    Text(stringResource(R.string.before_installing_the_launcher_checks_the), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.install(downloading) }, enabled = progress == null && x.info.sha256 != null) { Text(stringResource(R.string.download_and_install)) }
                        x.info.pageUrl?.let { url -> OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } }) { Text(stringResource(R.string.release_page)) } }
                    }
                    OutlinedButton(onClick = { context.startActivity(vm.permissionIntent()) }) { Text(stringResource(R.string.allow_installing_updates)) }
                }
            }
            is UpdateResult.UpToDate -> item { StatusChip(stringResource(R.string.you_have_the_latest_version_1, x.latest), StatusLevel.OK) }
            is UpdateResult.NoApk -> item { StatusChip(stringResource(R.string.release_1_s_has_no_installable, x.latest), StatusLevel.WARNING) }
            null -> Unit
        }
        item { SectionHeader(stringResource(R.string.automatic_checks)) }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.check_for_updates_daily))
                        Text(stringResource(R.string.off_by_default_only_on_wi), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(s.autoUpdateCheck, vm::setAuto, modifier = Modifier.testTag("switch_auto_update"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.include_pre_releases), Modifier.weight(1f))
                    Switch(s.includePrereleases, vm::setPrerelease)
                }
            }
        }
    }
}
