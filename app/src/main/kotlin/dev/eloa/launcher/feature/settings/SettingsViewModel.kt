package dev.eloa.launcher.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.data.db.AccountEntity
import dev.eloa.launcher.core.data.device.DeviceInfo
import dev.eloa.launcher.core.data.device.DeviceInfoProvider
import dev.eloa.launcher.core.data.performance.HardwareSummary
import dev.eloa.launcher.core.data.performance.PerformanceAdvisor
import dev.eloa.launcher.core.data.performance.PresetSpec
import dev.eloa.launcher.core.data.settings.LauncherSettings
import dev.eloa.launcher.core.data.settings.PerformancePreset
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.core.data.storage.StorageStats
import dev.eloa.launcher.domain.java.auth.DeviceCode
import dev.eloa.launcher.domain.java.runtime.JavaRuntimeInfo
import dev.eloa.launcher.feature.common.CleanupWorker
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.userMessage
import dev.eloa.launcher.feature.java.AccountRepository
import dev.eloa.launcher.feature.java.JavaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface SignInState {
    data object Idle : SignInState
    data object Requesting : SignInState
    data class WaitingForUser(val code: DeviceCode) : SignInState
    data class Failed(val message: String) : SignInState
}

fun DeviceInfo.summary() = HardwareSummary(totalRamMb, cpuCores, is64Bit, glesVersion.substringBefore('.').toIntOrNull() ?: 2, isLowRamDevice)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val device: DeviceInfoProvider,
    private val storage: LauncherStorage,
    private val accounts: AccountRepository,
    private val java: JavaRepository,
    private val messages: UserMessages,
) : ViewModel() {
    val state: StateFlow<LauncherSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, LauncherSettings())
    val accountList: StateFlow<List<AccountEntity>> = accounts.accounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _device = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _device
    private val _storage = MutableStateFlow<StorageStats?>(null)
    val storageStats: StateFlow<StorageStats?> = _storage
    private val _runtimes = MutableStateFlow<List<JavaRuntimeInfo>>(emptyList())
    val runtimes: StateFlow<List<JavaRuntimeInfo>> = _runtimes
    private val _signIn = MutableStateFlow<SignInState>(SignInState.Idle)
    val signIn: StateFlow<SignInState> = _signIn
    private var signInJob: Job? = null

    init { refreshDevice() }

    fun refreshDevice() = viewModelScope.launch {
        _device.value = withContext(Dispatchers.Default) { device.collect() }
        _storage.value = withContext(Dispatchers.IO) { storage.stats() }
        _runtimes.value = withContext(Dispatchers.IO) { java.runtimes() }
        // First run: pick a preset from measured hardware unless the user chose one.
        val s = settings.current()
        val d = _device.value
        if (d != null && !s.presetChosenByUser && !s.onboardingDone) {
            val rec = PerformanceAdvisor.recommend(d.summary())
            applySpec(PerformanceAdvisor.spec(rec, d.summary()), userChosen = false)
            settings.update { it.copy(onboardingDone = true) }
        }
    }

    fun recommended(): PerformancePreset? = _device.value?.let { PerformanceAdvisor.recommend(it.summary()) }
    fun specFor(p: PerformancePreset): PresetSpec? = _device.value?.let { PerformanceAdvisor.spec(p, it.summary()) }

    fun update(t: (LauncherSettings) -> LauncherSettings) = viewModelScope.launch {
        settings.update(t)
        CleanupWorker.schedule(context, settings.current())
    }

    fun applyPreset(p: PerformancePreset) {
        val spec = specFor(p) ?: return
        viewModelScope.launch { applySpec(spec, userChosen = true); messages.info("${p.name.lowercase().replace('_', ' ')} preset applied to launcher settings") }
    }

    private suspend fun applySpec(spec: PresetSpec, userChosen: Boolean) {
        settings.update {
            if (spec.preset == PerformancePreset.CUSTOM) it.copy(performancePreset = spec.preset, presetChosenByUser = userChosen)
            else it.copy(performancePreset = spec.preset, presetChosenByUser = userChosen, defaultMaxMemoryMb = spec.javaMaxMemoryMb,
                maxConcurrentDownloads = spec.maxConcurrentDownloads, reduceMotion = !spec.uiAnimations, enableBlur = spec.blur,
                uiRefreshCapHz = if (userChosen) spec.uiRefreshCapHz else it.uiRefreshCapHz, cacheLimitMb = spec.cacheLimitMb, limitBackgroundWork = spec.preset <= PerformancePreset.LOW)
        }
    }

    fun cleanNow() = viewModelScope.launch {
        val freed = withContext(Dispatchers.IO) { storage.cleanupTemp(0) }
        settings.update { it.copy(lastCleanupAt = System.currentTimeMillis()) }
        _storage.value = withContext(Dispatchers.IO) { storage.stats() }
        messages.info("Freed ${FileOps.formatSize(freed)}")
    }

    // ---- accounts ----
    fun startSignIn() {
        signInJob?.cancel()
        signInJob = viewModelScope.launch {
            _signIn.value = SignInState.Requesting
            try {
                val code = accounts.startSignIn()
                _signIn.value = SignInState.WaitingForUser(code)
                val acc = accounts.completeSignIn(code)
                _signIn.value = SignInState.Idle
                messages.info("Signed in as ${acc.username}")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                _signIn.value = SignInState.Failed(e.userMessage())
            }
        }
    }
    fun cancelSignIn() { signInJob?.cancel(); _signIn.value = SignInState.Idle }

    private fun act(msg: String, block: suspend () -> Unit) = viewModelScope.launch { runCatching { block() }.onSuccess { messages.info(msg) }.onFailure { messages.error(it.userMessage()) } }
    fun setActive(id: String) = act("Account selected") { accounts.setActive(id) }
    fun refreshAccount(id: String) = act("Session refreshed") { accounts.refresh(id) }
    fun signOut(id: String) = act("Signed out — tokens deleted") { accounts.signOut(id) }

    // ---- runtimes ----
    fun importRuntime(uri: Uri) = act("Runtime imported") { java.importRuntime(uri); _runtimes.value = java.runtimes() }
    fun deleteRuntime(r: JavaRuntimeInfo) = act("Runtime deleted") { withContext(Dispatchers.IO) { java.deleteRuntime(r) }; _runtimes.value = java.runtimes() }
}
