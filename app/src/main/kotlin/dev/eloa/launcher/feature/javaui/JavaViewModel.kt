package dev.eloa.launcher.feature.javaui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eloa.launcher.core.designsystem.component.ProgressInfo
import dev.eloa.launcher.domain.java.runtime.JavaRuntimeInfo
import dev.eloa.launcher.feature.common.ProfileSelection
import dev.eloa.launcher.feature.common.SelectedProfile
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.userMessage
import dev.eloa.launcher.feature.java.InstallWorker
import dev.eloa.launcher.feature.java.JavaRepository
import dev.eloa.launcher.feature.launch.LaunchCoordinator
import dev.eloa.launcher.feature.launch.LaunchOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Maps the unique install job of a profile to a progress panel model. */
fun installProgress(context: Context, profileId: String): Flow<ProgressInfo?> =
    WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(InstallWorker.workName(profileId)).map { infos ->
        val w = infos.firstOrNull { !it.state.isFinished } ?: return@map null
        val stage = w.progress.getString(InstallWorker.KEY_STAGE) ?: if (w.state == WorkInfo.State.ENQUEUED) "Waiting to start" else "Preparing"
        val f = w.progress.getFloat(InstallWorker.KEY_FRACTION, -1f)
        ProgressInfo("Installing: $stage", if (f < 0) null else f, w.progress.getString(InstallWorker.KEY_DETAIL))
    }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class JavaViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val selected: SelectedProfile,
    private val java: JavaRepository,
    private val launcher: LaunchCoordinator,
    private val messages: UserMessages,
) : ViewModel() {
    val selection: StateFlow<ProfileSelection> = selected.selection.stateIn(viewModelScope, SharingStarted.Eagerly, ProfileSelection(emptyList(), null))
    val progress: StateFlow<ProgressInfo?> = selection.map { it.selected?.id }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else installProgress(context, id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _runtimes = MutableStateFlow<List<JavaRuntimeInfo>>(emptyList())
    val runtimes: StateFlow<List<JavaRuntimeInfo>> = _runtimes
    private val _outcome = MutableStateFlow<LaunchOutcome?>(null)
    val outcome: StateFlow<LaunchOutcome?> = _outcome
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    init { refreshRuntimes() }

    fun refreshRuntimes() = viewModelScope.launch { _runtimes.value = runCatching { java.runtimes() }.getOrDefault(emptyList()) }
    fun select(id: String) = viewModelScope.launch { selected.select(id) }

    fun install() {
        val p = selection.value.selected ?: return
        InstallWorker.enqueue(context, p.id)
        messages.info("Installation of ${p.name} started — progress is also shown in the notification.")
    }

    fun cancelInstall() { selection.value.selected?.let { InstallWorker.cancel(context, it.id) } }

    fun verify() {
        val p = selection.value.selected ?: return
        val id = p.launchVersionId ?: return messages.error("Install the profile first")
        viewModelScope.launch {
            _busy.value = true
            try {
                val r = java.verify(id)
                if (r.ok) messages.info("All ${r.checked} files verified") else messages.error("${r.missing.size} missing and ${r.corrupted.size} damaged file(s). Use Repair to fix them.")
            } catch (e: Exception) { messages.error(e.userMessage()) } finally { _busy.value = false }
        }
    }

    fun launch() {
        val p = selection.value.selected ?: return
        viewModelScope.launch {
            _busy.value = true
            try { _outcome.value = launcher.prepareJava(p.id) } finally { _busy.value = false }
        }
    }

    fun dismissOutcome() { _outcome.value = null }
}
