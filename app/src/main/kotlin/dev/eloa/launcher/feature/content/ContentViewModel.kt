package dev.eloa.launcher.feature.content

import dev.eloa.launcher.feature.common.str
import dev.eloa.launcher.R
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eloa.launcher.core.data.db.JavaProfileEntity
import dev.eloa.launcher.domain.java.content.JavaContentType
import dev.eloa.launcher.feature.common.ProfileSelection
import dev.eloa.launcher.feature.common.SelectedProfile
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.userMessage
import dev.eloa.launcher.feature.java.ContentItem
import dev.eloa.launcher.feature.java.ContentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ContentSort { NAME, SIZE, DATE }

data class ContentUi(
    val loading: Boolean = true,
    val items: List<ContentItem> = emptyList(),
    val worlds: List<String> = emptyList(),
    val world: String? = null,
    val shaderLoaderPresent: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class ContentViewModel @Inject constructor(
    private val repo: ContentRepository,
    private val selected: SelectedProfile,
    private val messages: UserMessages,
) : ViewModel() {
    val selection: StateFlow<ProfileSelection> = selected.selection.stateIn(viewModelScope, SharingStarted.Eagerly, ProfileSelection(emptyList(), null))
    private val _ui = MutableStateFlow(ContentUi())
    val ui: StateFlow<ContentUi> = _ui
    var type: JavaContentType = JavaContentType.RESOURCE_PACKS
        private set
    private var profile: JavaProfileEntity? = null

    fun bind(type: JavaContentType, p: JavaProfileEntity?) {
        val changed = this.type != type || profile?.id != p?.id || profile?.minecraftVersion != p?.minecraftVersion
        this.type = type
        profile = p
        if (changed || _ui.value.loading) refresh()
    }

    fun selectProfile(id: String) = viewModelScope.launch { selected.select(id) }

    fun refresh() {
        val p = profile ?: run { _ui.value = ContentUi(loading = false); return }
        viewModelScope.launch {
            try {
                val worlds = repo.list(p, JavaContentType.WORLDS).filter { it.isDirectory }.map { it.fileName }
                val world = _ui.value.world?.takeIf { it in worlds } ?: worlds.firstOrNull()
                val items = if (type == JavaContentType.DATAPACKS && world == null) emptyList() else repo.list(p, type, world)
                val shader = if (type == JavaContentType.SHADER_PACKS) repo.shaderLoaderPresent(repo.mods(p)) else true
                _ui.value = ContentUi(false, items, worlds, world, shader)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = e.userMessage())
            }
        }
    }

    fun selectWorld(w: String) { _ui.value = _ui.value.copy(world = w); refresh() }

    private fun act(success: String?, block: suspend (JavaProfileEntity) -> Unit) {
        val p = profile ?: return messages.error(R.string.create_a_profile_first)
        viewModelScope.launch {
            runCatching { block(p) }.onSuccess { success?.let(messages::info); refresh() }.onFailure { messages.error(it.userMessage()) }
        }
    }

    fun import(uris: List<Uri>) = uris.forEach { u -> act(str(R.string.imported)) { repo.import(it, type, u, _ui.value.world) } }
    fun toggle(item: ContentItem) = act(null) { repo.setEnabled(it, type, item.fileName, !item.enabled, _ui.value.world) }
    fun delete(item: ContentItem) = act(str(R.string.deleted_1_s, item.displayName)) { repo.delete(it, type, item.fileName, _ui.value.world) }
    fun export(item: ContentItem, uri: Uri) = act(str(R.string.msg_exported)) { repo.export(it, type, item.fileName, uri, _ui.value.world) }

    fun readConfig(item: ContentItem): String? = profile?.let { runCatching { repo.readConfig(it, item.fileName) }.onFailure { e -> messages.error(e.userMessage()) }.getOrNull() }
    fun saveConfig(item: ContentItem, text: String) = act(str(R.string.saved_backup_kept_as_1_s, item.fileName)) { repo.writeConfig(it, item.fileName, text) }
}
