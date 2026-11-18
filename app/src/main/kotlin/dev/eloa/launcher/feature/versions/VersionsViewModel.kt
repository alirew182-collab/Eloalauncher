package dev.eloa.launcher.feature.versions

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.domain.java.loader.ModLoaderType
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.userMessage
import dev.eloa.launcher.feature.java.InstallWorker
import dev.eloa.launcher.feature.java.JavaRepository
import dev.eloa.launcher.feature.java.ManifestState
import dev.eloa.launcher.feature.java.ProfileConfig
import dev.eloa.launcher.feature.java.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class InstalledVersion(val id: String, val usedBy: List<String>, val isLoaderProfile: Boolean)

@HiltViewModel
class VersionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val java: JavaRepository,
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val messages: UserMessages,
) : ViewModel() {
    val manifest: StateFlow<ManifestState> = java.manifest
    private val _installed = MutableStateFlow<List<InstalledVersion>>(emptyList())
    val installed: StateFlow<List<InstalledVersion>> = _installed
    private val _verifying = MutableStateFlow<String?>(null)
    val verifying: StateFlow<String?> = _verifying

    init { refresh(false) }

    fun refresh(force: Boolean = true) = viewModelScope.launch {
        java.refreshManifest(force)
        loadInstalled()
    }

    private suspend fun loadInstalled() {
        val all = profiles.all()
        _installed.value = withContext(Dispatchers.IO) {
            java.installedVersions().map { id ->
                InstalledVersion(id, all.filter { it.launchVersionId == id || java.clientJarIdFor(it.launchVersionId ?: "") == id }.map { it.name }, java.clientJarIdFor(id) != id)
            }
        }
    }

    /** Creates a vanilla profile for the version and starts installing it. */
    fun createProfile(versionId: String) = viewModelScope.launch {
        try {
            val s = settings.current()
            val p = profiles.create(ProfileConfig(name = "Minecraft $versionId", minecraftVersion = versionId, loader = ModLoaderType.VANILLA.name,
                maxMemoryMb = s.defaultMaxMemoryMb, minMemoryMb = minOf(512, s.defaultMaxMemoryMb)))
            settings.update { it.copy(selectedProfileId = p.id) }
            InstallWorker.enqueue(context, p.id)
            messages.info("Profile created; installing Minecraft $versionId")
        } catch (e: Exception) { messages.error(e.userMessage()) }
    }

    fun verify(id: String) = viewModelScope.launch {
        _verifying.value = id
        try {
            val r = java.verify(id)
            if (r.ok) messages.info("$id: all ${r.checked} files OK") else messages.error("$id: ${r.missing.size} missing, ${r.corrupted.size} damaged — repair the profile that uses it.")
        } catch (e: Exception) { messages.error(e.userMessage()) } finally { _verifying.value = null }
    }

    fun delete(v: InstalledVersion) = viewModelScope.launch {
        runCatching { java.deleteVersion(v.id, v.usedBy) }.onSuccess { messages.info("Deleted ${v.id}"); loadInstalled() }.onFailure { messages.error(it.userMessage()) }
    }
}
