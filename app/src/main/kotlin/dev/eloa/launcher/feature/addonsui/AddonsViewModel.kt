package dev.eloa.launcher.feature.addonsui

import dev.eloa.launcher.feature.common.str
import dev.eloa.launcher.R
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eloa.launcher.core.data.db.AddonEntity
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.core.designsystem.component.ProgressInfo
import dev.eloa.launcher.domain.bedrock.apk.Finding
import dev.eloa.launcher.feature.addons.AddonImportResult
import dev.eloa.launcher.feature.addons.AddonRepository
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddonDetails(val addon: AddonEntity, val contents: List<Pair<String, Long>>, val conflicts: List<Finding>)

@HiltViewModel
class AddonsViewModel @Inject constructor(
    private val repo: AddonRepository,
    private val settings: SettingsRepository,
    private val messages: UserMessages,
) : ViewModel() {
    val addons: StateFlow<List<AddonEntity>?> = repo.addons.map<List<AddonEntity>, List<AddonEntity>?> { it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val mojangFolder: StateFlow<String> = settings.settings.map { it.mojangFolderUri }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val _progress = MutableStateFlow<ProgressInfo?>(null)
    val progress: StateFlow<ProgressInfo?> = _progress
    private val _results = MutableStateFlow<List<AddonImportResult>?>(null)
    val results: StateFlow<List<AddonImportResult>?> = _results
    private val _details = MutableStateFlow<AddonDetails?>(null)
    val details: StateFlow<AddonDetails?> = _details
    private val _launchIntent = MutableStateFlow<Intent?>(null)
    val launchIntent: StateFlow<Intent?> = _launchIntent

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
                _progress.value = ProgressInfo(str(R.string.checking_add_ons), 0f, null, cancellable = false)
                _results.value = repo.import(uris) { n, f -> _progress.value = ProgressInfo(str(R.string.checking_add_ons), f, n, cancellable = false) }
            } catch (e: Exception) { messages.error(e.userMessage()) } finally { _progress.value = null }
        }
    }

    fun dismissResults() { _results.value = null }

    fun open(a: AddonEntity) = viewModelScope.launch { _details.value = AddonDetails(a, runCatching { repo.contents(a.id) }.getOrDefault(emptyList()), repo.conflicts(a.id)) }
    fun closeDetails() { _details.value = null }

    private fun act(msg: String?, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onSuccess { msg?.let(messages::info) }.onFailure { messages.error(it.userMessage()) }
    }

    fun toggle(a: AddonEntity) = act(null) { repo.setEnabled(a.id, !a.enabled) }
    fun setCategory(a: AddonEntity, c: String) = act(str(R.string.category_updated)) { repo.setCategory(a.id, c); _details.value = _details.value?.let { d -> d.copy(addon = d.addon.copy(category = c)) } }
    fun rename(a: AddonEntity, n: String) = act(str(R.string.renamed)) { repo.rename(a.id, n); _details.value = _details.value?.let { d -> d.copy(addon = d.addon.copy(name = n)) } }
    fun delete(a: AddonEntity) = act(str(R.string.deleted_1_s, a.name)) { repo.delete(a.id); _details.value = null }
    fun backup(uri: Uri) = act(null) { messages.info(R.string.backed_up_1_s_add_on, repo.backup(uri)) }
    fun copyToMojang(a: AddonEntity) = act(null) { messages.info(repo.copyToMojangFolder(a.id)) }
    fun openInMinecraft(a: AddonEntity) = act(null) { _launchIntent.value = repo.openInMinecraftIntent(a.id) }
    fun consumeIntent() { _launchIntent.value = null }
    fun setMojangFolder(uri: Uri) = act(str(R.string.folder_linked)) { settings.update { it.copy(mojangFolderUri = uri.toString()) } }
    fun unlinkMojangFolder() = act(str(R.string.folder_unlinked)) { settings.update { it.copy(mojangFolderUri = "") } }
}
