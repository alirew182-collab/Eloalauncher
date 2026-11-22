package dev.eloa.launcher.feature.bedrock

import dev.eloa.launcher.feature.common.str
import dev.eloa.launcher.R
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eloa.launcher.core.data.db.BedrockVersionEntity
import dev.eloa.launcher.core.data.db.ImportHistoryEntity
import dev.eloa.launcher.core.data.settings.Edition
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.core.designsystem.component.ProgressInfo
import dev.eloa.launcher.domain.bedrock.apk.KnownPackages
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.userMessage
import dev.eloa.launcher.feature.launch.LaunchCoordinator
import dev.eloa.launcher.feature.launch.LaunchOutcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BedrockUi(val loading: Boolean = true, val versions: List<BedrockVersionEntity> = emptyList(), val selectedId: Long = -1)

@HiltViewModel
class BedrockViewModel @Inject constructor(
    private val repo: BedrockRepository,
    private val installer: PackageInstallerGateway,
    private val launcher: LaunchCoordinator,
    private val settings: SettingsRepository,
    private val messages: UserMessages,
) : ViewModel() {
    val ui: StateFlow<BedrockUi> = kotlinx.coroutines.flow.combine(repo.versions, settings.settings) { v, s -> BedrockUi(false, v, s.selectedBedrockId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BedrockUi())
    val history: StateFlow<List<ImportHistoryEntity>> = repo.history.map { h -> h.filter { it.kind == "APK" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _progress = MutableStateFlow<ProgressInfo?>(null)
    val progress: StateFlow<ProgressInfo?> = _progress
    private val _report = MutableStateFlow<ImportReport?>(null)
    val report: StateFlow<ImportReport?> = _report
    private val _plan = MutableStateFlow<InstallPlan?>(null)
    val plan: StateFlow<InstallPlan?> = _plan
    private val _outcome = MutableStateFlow<LaunchOutcome?>(null)
    val outcome: StateFlow<LaunchOutcome?> = _outcome
    private val _needsPermission = MutableStateFlow(false)
    val needsPermission: StateFlow<Boolean> = _needsPermission
    private var job: Job? = null

    init {
        viewModelScope.launch {
            PackageInstallerGateway.events.collect { e -> if (e.success) messages.info(e.message) else messages.error(e.message); _progress.value = null }
        }
    }

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        job = viewModelScope.launch {
            try {
                _progress.value = ProgressInfo(str(R.string.importing_1_s_file_s, uris.size), 0f, null)
                _report.value = repo.import(uris) { label, f -> _progress.value = ProgressInfo(str(R.string.progress_importing), f, label) }
            } catch (e: Exception) { messages.error(e.userMessage()) } finally { _progress.value = null }
        }
    }

    fun cancel() { job?.cancel(); _progress.value = null }
    fun dismissReport() { _report.value = null }

    fun select(id: Long) = viewModelScope.launch { settings.update { it.copy(selectedBedrockId = id, selectedEdition = Edition.BEDROCK) }; messages.info(R.string.selected_for_launch) }
    fun rename(id: Long, name: String) = viewModelScope.launch { repo.rename(id, name) }
    fun setNotes(id: Long, notes: String) = viewModelScope.launch { repo.setNotes(id, notes) }
    fun delete(id: Long, files: Boolean) = viewModelScope.launch {
        runCatching { repo.delete(id, files) }.onSuccess { messages.info(if (files) str(R.string.removed_with_files) else str(R.string.removed_from_library_files_kept)) }.onFailure { messages.error(it.userMessage()) }
    }

    fun verify(id: Long) {
        job = viewModelScope.launch {
            _progress.value = ProgressInfo(str(R.string.verifying_files), 0f, null)
            try {
                val (ok, msg) = repo.verify(id) { _progress.value = ProgressInfo(str(R.string.verifying_files), it, null) }
                if (ok) messages.info(msg) else messages.error(msg)
            } catch (e: Exception) { messages.error(e.userMessage()) } finally { _progress.value = null }
        }
    }

    fun requestInstall(id: Long) = viewModelScope.launch { _plan.value = repo.planInstall(id) }
    fun dismissPlan() { _plan.value = null }

    fun confirmInstall() {
        val p = _plan.value ?: return
        _plan.value = null
        if (!installer.canRequestInstalls()) { _needsPermission.value = true; return }
        job = viewModelScope.launch {
            try {
                _progress.value = ProgressInfo(str(R.string.sending_to_android_installer), 0f, p.entity.displayName, cancellable = false)
                repo.install(p.entity.id) { _progress.value = ProgressInfo(str(R.string.sending_to_android_installer), it, p.entity.displayName, cancellable = false) }
                _progress.value = ProgressInfo(str(R.string.waiting_for_confirmation), null, str(R.string.confirm_the_installation_in_the_system), cancellable = false)
            } catch (e: InstallPermissionMissing) { _needsPermission.value = true; _progress.value = null }
            catch (e: Exception) { messages.error(e.userMessage()); _progress.value = null }
        }
    }

    fun permissionIntent(): Intent = installer.unknownSourcesSettingsIntent()
    fun dismissPermission() { _needsPermission.value = false }
    fun appSettingsIntent(): Intent = installer.appDetailsIntent(KnownPackages.MINECRAFT_BEDROCK)

    fun launch(id: Long) = viewModelScope.launch { _outcome.value = launcher.launchBedrock(id) }
    fun launchInstalled() { if (!launcher.launchInstalledBedrock()) messages.error(R.string.minecraft_bedrock_is_not_installed_on) }
    fun dismissOutcome() { _outcome.value = null }

    fun exportMetadata(uri: Uri) = viewModelScope.launch {
        runCatching { repo.exportMetadata(uri) }.onSuccess { messages.info(R.string.library_metadata_exported) }.onFailure { messages.error(it.userMessage()) }
    }

    fun files(e: BedrockVersionEntity) = repo.files(e)
}
