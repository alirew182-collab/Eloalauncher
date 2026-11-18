package dev.eloa.launcher.feature.files

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class Clipboard(val root: FileRoot, val rel: String, val name: String, val move: Boolean)

data class FilesUi(
    val root: FileRoot = FileRoot.INSTANCES,
    val path: String = "",
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = true,
    val sort: FileSort = FileSort.NAME,
    val query: String = "",
    val searchResults: List<FileEntry>? = null,
    val clipboard: Clipboard? = null,
    val error: String? = null,
    val info: String? = null,
)

@HiltViewModel
class FilesViewModel @Inject constructor(private val repo: FileManagerRepository, private val messages: UserMessages) : ViewModel() {
    private val _ui = MutableStateFlow(FilesUi())
    val ui: StateFlow<FilesUi> = _ui
    private var searchJob: Job? = null

    init { load() }

    fun load() = viewModelScope.launch {
        val s = _ui.value
        _ui.value = s.copy(loading = true, error = null)
        _ui.value = try { _ui.value.copy(entries = repo.list(s.root, s.path, s.sort, computeDirSizes = s.sort == FileSort.SIZE), loading = false) }
        catch (e: Exception) { _ui.value.copy(loading = false, error = e.userMessage(), path = "") }
    }

    fun setRoot(r: FileRoot) { _ui.value = _ui.value.copy(root = r, path = "", query = "", searchResults = null); load() }
    fun open(e: FileEntry) { if (e.isDirectory) { _ui.value = _ui.value.copy(path = e.relativePath, query = "", searchResults = null); load() } }
    fun up(): Boolean {
        val p = _ui.value.path
        if (p.isEmpty()) return false
        _ui.value = _ui.value.copy(path = p.substringBeforeLast('/', "")); load(); return true
    }
    fun setSort(s: FileSort) { _ui.value = _ui.value.copy(sort = s); load() }

    fun search(q: String) {
        _ui.value = _ui.value.copy(query = q)
        searchJob?.cancel()
        if (q.isBlank()) { _ui.value = _ui.value.copy(searchResults = null); return }
        searchJob = viewModelScope.launch { delay(300); _ui.value = _ui.value.copy(searchResults = repo.search(_ui.value.root, q)) }
    }

    private fun act(msg: String?, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onSuccess { msg?.let(messages::info); load() }.onFailure { messages.error(it.userMessage()) }
    }

    fun createFolder(name: String) = act("Folder created") { repo.createFolder(_ui.value.root, _ui.value.path, name) }
    fun rename(e: FileEntry, name: String) = act("Renamed") { repo.rename(_ui.value.root, e.relativePath, name) }
    fun delete(e: FileEntry) = act("Deleted ${e.name}") { repo.delete(_ui.value.root, e.relativePath) }
    fun copy(e: FileEntry, move: Boolean) { _ui.value = _ui.value.copy(clipboard = Clipboard(_ui.value.root, e.relativePath, e.name, move)); messages.info("${if (move) "Cut" else "Copied"} ${e.name} — open the destination and tap Paste") }
    fun clearClipboard() { _ui.value = _ui.value.copy(clipboard = null) }
    fun paste() {
        val c = _ui.value.clipboard ?: return
        act(if (c.move) "Moved ${c.name}" else "Copied ${c.name}") { repo.copyOrMove(c.root, c.rel, _ui.value.root, _ui.value.path, c.move); _ui.value = _ui.value.copy(clipboard = null) }
    }
    fun import(uris: List<Uri>) = uris.forEach { u -> act("Imported") { repo.import(_ui.value.root, _ui.value.path, u) } }
    fun export(e: FileEntry, uri: Uri) = act("Exported ${e.name}") { repo.export(_ui.value.root, e.relativePath, uri) }
    fun backup(e: FileEntry) = act(null) { messages.info("Backup saved to Backups/${repo.backup(_ui.value.root, e.relativePath)}") }
    fun integrity(e: FileEntry) = viewModelScope.launch {
        _ui.value = _ui.value.copy(info = "Checking ${e.name}…")
        _ui.value = _ui.value.copy(info = runCatching { repo.checkArchive(_ui.value.root, e.relativePath) + "\nSHA-256: " + repo.sha256(_ui.value.root, e.relativePath) }.getOrElse { it.userMessage() })
    }
    fun dismissInfo() { _ui.value = _ui.value.copy(info = null) }
}
