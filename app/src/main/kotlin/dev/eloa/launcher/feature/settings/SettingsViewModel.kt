package dev.eloa.launcher.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eloa.launcher.R
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.data.device.DeviceInfo
import dev.eloa.launcher.core.data.device.DeviceInfoProvider
import dev.eloa.launcher.core.data.performance.HardwareSummary
import dev.eloa.launcher.core.data.performance.PerformanceAdvisor
import dev.eloa.launcher.core.data.settings.LauncherSettings
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.core.data.storage.StorageStats
import dev.eloa.launcher.feature.common.CleanupWorker
import dev.eloa.launcher.feature.common.UserMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

fun DeviceInfo.summary() = HardwareSummary(totalRamMb, cpuCores, is64Bit, glesVersion.substringBefore('.').toIntOrNull() ?: 2, isLowRamDevice)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val device: DeviceInfoProvider,
    private val storage: LauncherStorage,
    private val messages: UserMessages,
) : ViewModel() {
    val state: StateFlow<LauncherSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, LauncherSettings())
    private val _storage = MutableStateFlow<StorageStats?>(null)
    val storageStats: StateFlow<StorageStats?> = _storage

    init {
        viewModelScope.launch {
            _storage.value = withContext(Dispatchers.IO) { storage.stats() }
            // First run: pick a performance mode from measured hardware unless the user chose one.
            val s = settings.current()
            if (!s.presetChosenByUser && !s.onboardingDone) {
                val d = withContext(Dispatchers.Default) { device.collect(includeGpu = false) }
                val spec = PerformanceAdvisor.spec(PerformanceAdvisor.recommend(d.summary()), d.summary())
                settings.update {
                    it.copy(performancePreset = spec.preset, defaultMaxMemoryMb = spec.javaMaxMemoryMb, maxConcurrentDownloads = spec.maxConcurrentDownloads,
                        reduceMotion = !spec.uiAnimations, enableBlur = spec.blur, cacheLimitMb = spec.cacheLimitMb, onboardingDone = true)
                }
            }
        }
    }

    fun update(t: (LauncherSettings) -> LauncherSettings) = viewModelScope.launch {
        settings.update(t)
        CleanupWorker.schedule(context, settings.current())
    }

    fun cleanNow() = viewModelScope.launch {
        val freed = withContext(Dispatchers.IO) { storage.cleanupTemp(0) }
        settings.update { it.copy(lastCleanupAt = System.currentTimeMillis()) }
        _storage.value = withContext(Dispatchers.IO) { storage.stats() }
        messages.info(R.string.msg_freed, FileOps.formatSize(freed))
    }
}
