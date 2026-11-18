package dev.eloa.launcher.feature.profiles

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eloa.launcher.core.data.db.JavaProfileEntity
import dev.eloa.launcher.core.data.device.DeviceInfoProvider
import dev.eloa.launcher.core.data.performance.PerformanceAdvisor
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.domain.java.loader.LoaderVersion
import dev.eloa.launcher.domain.java.loader.ModLoaderType
import dev.eloa.launcher.domain.java.launch.LaunchPlanBuilder
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.userMessage
import dev.eloa.launcher.feature.java.InstallWorker
import dev.eloa.launcher.feature.java.JavaRepository
import dev.eloa.launcher.feature.java.ProfileConfig
import dev.eloa.launcher.feature.java.ProfileRepository
import dev.eloa.launcher.feature.java.toConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: ProfileRepository,
    private val settings: SettingsRepository,
    private val messages: UserMessages,
) : ViewModel() {
    val profiles: StateFlow<List<JavaProfileEntity>?> = repo.profiles.map<List<JavaProfileEntity>, List<JavaProfileEntity>?> { it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val selectedId: StateFlow<String> = settings.settings.map { it.selectedProfileId }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private fun act(msg: String, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onSuccess { messages.info(msg) }.onFailure { messages.error(it.userMessage()) }
    }

    fun select(id: String) = act("Profile selected") { settings.update { it.copy(selectedProfileId = id) } }
    fun clone(id: String, files: Boolean) = act("Profile cloned") { repo.clone(id, files) }
    fun delete(id: String, files: Boolean) = act(if (files) "Profile and its game folder deleted" else "Profile deleted (game folder kept)") { repo.delete(id, files) }
    fun export(id: String, uri: Uri) = act("Configuration exported") { repo.exportConfig(id, uri) }
    fun import(uri: Uri) = act("Profile imported — install it to download game files") { repo.importConfig(uri) }
    fun install(id: String) { InstallWorker.enqueue(context, id); messages.info("Installation started") }
}

data class EditorState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val config: ProfileConfig = ProfileConfig(name = "", minecraftVersion = "", loader = ModLoaderType.VANILLA.name),
    val mcVersions: List<String> = emptyList(),
    val showSnapshots: Boolean = false,
    val loaderVersions: List<LoaderVersion> = emptyList(),
    val loaderLoading: Boolean = false,
    val loaderError: String? = null,
    val totalRamMb: Long = 4096,
    val safeMaxMb: Int = 2048,
    val saved: Boolean = false,
)

@HiltViewModel
class ProfileEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    handle: SavedStateHandle,
    private val repo: ProfileRepository,
    private val java: JavaRepository,
    private val device: DeviceInfoProvider,
    private val settings: SettingsRepository,
    private val messages: UserMessages,
) : ViewModel() {
    private val id: String? = handle.get<String>("id")?.takeIf { it.isNotBlank() }
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state
    private var loaderJob: Job? = null

    init {
        viewModelScope.launch {
            val total = device.memorySnapshot().second / 1_048_576
            val s = settings.current()
            val existing = id?.let { repo.get(it) }
            val cfg = existing?.toConfig() ?: ProfileConfig(name = "", minecraftVersion = "", loader = ModLoaderType.VANILLA.name,
                maxMemoryMb = minOf(s.defaultMaxMemoryMb, PerformanceAdvisor.safeMaxMemoryMb(total)), minMemoryMb = 512)
            _state.value = _state.value.copy(loaded = true, isNew = existing == null, config = cfg, totalRamMb = total, safeMaxMb = PerformanceAdvisor.safeMaxMemoryMb(total))
            val m = java.refreshManifest()
            updateVersions()
            if (cfg.minecraftVersion.isEmpty()) m?.latest?.release?.let { update(cfg.copy(minecraftVersion = it)) }
            loadLoaderVersions()
        }
    }

    private fun updateVersions() {
        val m = java.manifest.value.manifest ?: return
        val snaps = _state.value.showSnapshots
        _state.value = _state.value.copy(mcVersions = m.versions.filter { it.type == "release" || (snaps && it.type == "snapshot") }.map { it.id })
    }

    fun toggleSnapshots() { _state.value = _state.value.copy(showSnapshots = !_state.value.showSnapshots); updateVersions() }

    fun update(cfg: ProfileConfig) {
        val old = _state.value.config
        _state.value = _state.value.copy(config = cfg)
        if (old.loader != cfg.loader || old.minecraftVersion != cfg.minecraftVersion) {
            _state.value = _state.value.copy(config = cfg.copy(loaderVersion = null))
            loadLoaderVersions()
        }
    }

    fun loadLoaderVersions() {
        val cfg = _state.value.config
        val type = ModLoaderType.valueOf(cfg.loader)
        loaderJob?.cancel()
        if (type == ModLoaderType.VANILLA || cfg.minecraftVersion.isEmpty()) { _state.value = _state.value.copy(loaderVersions = emptyList(), loaderError = null); return }
        loaderJob = viewModelScope.launch {
            _state.value = _state.value.copy(loaderLoading = true, loaderError = null)
            try {
                val list = java.loaderVersions(type, cfg.minecraftVersion)
                _state.value = _state.value.copy(loaderVersions = list, loaderLoading = false,
                    loaderError = if (list.isEmpty()) "${type.displayName} is not available for ${cfg.minecraftVersion}" else null)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loaderLoading = false, loaderError = e.userMessage())
            }
        }
    }

    fun memoryWarning(): String? = PerformanceAdvisor.memoryWarning(_state.value.config.maxMemoryMb, _state.value.totalRamMb)

    fun validate(): String? {
        val c = _state.value.config
        return repo.validateName(c.name)
            ?: if (c.minecraftVersion.isBlank()) "Choose a Minecraft version" else null
            ?: if (c.minMemoryMb > c.maxMemoryMb) "Minimum memory exceeds maximum" else null
            ?: LaunchPlanBuilder.validateExtraJvmArgs(c.jvmArgs.split(' ').filter { it.isNotBlank() }).firstOrNull()?.let { "JVM argument not allowed: $it" }
            ?: if (ModLoaderType.valueOf(c.loader) != ModLoaderType.VANILLA && _state.value.loaderError != null && _state.value.loaderVersions.isEmpty() && !_state.value.loaderLoading) _state.value.loaderError else null
    }

    fun save(installNow: Boolean) = viewModelScope.launch {
        validate()?.let { messages.error(it); return@launch }
        try {
            val c = _state.value.config
            val pid = if (id == null) repo.create(c).id else { repo.update(id, c); id }
            settings.update { it.copy(selectedProfileId = pid) }
            if (installNow) InstallWorker.enqueue(context, pid)
            messages.info(if (installNow) "Saved — installing game files" else "Profile saved")
            _state.value = _state.value.copy(saved = true)
        } catch (e: Exception) { messages.error(e.userMessage()) }
    }
}
