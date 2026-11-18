package dev.eloa.launcher.feature.mods

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eloa.launcher.core.data.db.JavaProfileEntity
import dev.eloa.launcher.domain.java.mods.ModFile
import dev.eloa.launcher.domain.java.mods.ModIssue
import dev.eloa.launcher.feature.common.ProfileSelection
import dev.eloa.launcher.feature.common.SelectedProfile
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.userMessage
import dev.eloa.launcher.feature.java.ContentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

enum class ModFilter { ALL, ENABLED, DISABLED, PROBLEMS, LIBRARIES }
enum class ModSort { NAME, SIZE, DATE }

data class ModsUi(
    val loading: Boolean = true,
    val profile: JavaProfileEntity? = null,
    val mods: List<ModFile> = emptyList(),
    val issues: List<ModIssue> = emptyList(),
    /** File names of mods other enabled mods depend on. */
    val libraries: Set<String> = emptySet(),
    val backups: List<File> = emptyList(),
)

@HiltViewModel
class ModsViewModel @Inject constructor(
    private val repo: ContentRepository,
    private val selected: SelectedProfile,
    private val messages: UserMessages,
) : ViewModel() {
    val selection: StateFlow<ProfileSelection> = selected.selection.stateIn(viewModelScope, SharingStarted.Eagerly, ProfileSelection(emptyList(), null))
    private val _ui = MutableStateFlow(ModsUi())
    val ui: StateFlow<ModsUi> = _ui

    fun bind(p: JavaProfileEntity?) { if (p?.id != _ui.value.profile?.id || p?.loader != _ui.value.profile?.loader || _ui.value.loading) { _ui.value = _ui.value.copy(profile = p); refresh() } }
    fun selectProfile(id: String) = viewModelScope.launch { selected.select(id) }

    fun refresh() {
        val p = _ui.value.profile ?: run { _ui.value = ModsUi(loading = false); return }
        viewModelScope.launch {
            val (mods, issues, backups) = withContext(Dispatchers.IO) {
                val m = repo.mods(p)
                Triple(m, repo.checkMods(p, m), repo.modManager(p).backups())
            }
            val requiredIds = mods.filter { it.enabled }.flatMap { f -> f.metadata.flatMap { md -> md.dependencies.filter { it.kind == dev.eloa.launcher.domain.java.mods.DependencyKind.REQUIRED }.map { it.modId } } }.toSet()
            val libs = mods.filter { f -> f.metadata.any { it.id in requiredIds || it.provides.any { pr -> pr in requiredIds } } }.map { it.fileName }.toSet()
            _ui.value = ModsUi(false, p, mods, issues, libs, backups)
        }
    }

    private fun act(msg: String?, block: suspend (JavaProfileEntity) -> Unit) {
        val p = _ui.value.profile ?: return messages.error("Create a profile first")
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { block(p) } }.onSuccess { msg?.let(messages::info); refresh() }.onFailure { messages.error(it.userMessage()) }
        }
    }

    fun import(uris: List<Uri>) = uris.forEach { u -> act("Mod imported") { repo.importMod(it, u) } }
    fun toggle(m: ModFile) = act(null) { repo.modManager(it).setEnabled(m.fileName, !m.enabled) }
    fun delete(m: ModFile) = act("Deleted ${m.displayName}") { repo.modManager(it).delete(m.fileName) }
    fun backup() = act("Backup created") { repo.modManager(it).backup() }
    fun restore(f: File) = act("Mods restored (previous state was backed up)") { repo.modManager(it).restore(f) }
}
