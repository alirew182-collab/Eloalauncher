package dev.eloa.launcher.feature.controls

import android.net.Uri
import dev.eloa.launcher.core.common.hash.HashAlgorithm
import dev.eloa.launcher.core.common.hash.Hashing
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.common.io.PathSafety
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.domain.java.controls.ControlLayout
import dev.eloa.launcher.domain.java.controls.ControlLayouts
import dev.eloa.launcher.domain.java.controls.ControlPreset
import dev.eloa.launcher.domain.java.controls.GamepadProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Touch layouts and gamepad profiles, stored as JSON in private storage. The runtime backend reads
 * the active layout file (`controls/active.json`) when the game starts.
 */
@Singleton
class ControlsRepository @Inject constructor(
    private val storage: LauncherStorage,
    private val settings: SettingsRepository,
) {
    private val root get() = File(storage.root, "controls")
    private val layoutsDir get() = File(root, "layouts").apply { mkdirs() }
    private val padsDir get() = File(root, "gamepads").apply { mkdirs() }
    private val _layouts = MutableStateFlow<List<ControlLayout>>(emptyList())
    val layouts: StateFlow<List<ControlLayout>> = _layouts

    private fun fileFor(name: String) = File(layoutsDir, PathSafety.sanitizeFileName(name, "layout") + ".json")

    suspend fun load(): List<ControlLayout> = withContext(Dispatchers.IO) {
        if (layoutsDir.listFiles()?.none { it.extension == "json" } != false) {
            ControlPreset.entries.filter { it != ControlPreset.CUSTOM }.forEach { p -> write(ControlLayouts.preset(p)) }
        }
        val list = layoutsDir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f -> runCatching { ControlLayouts.decode(f.readText()) }.getOrNull() }
            .sortedBy { it.name.lowercase() }
        _layouts.value = list
        list
    }

    private fun write(l: ControlLayout) = FileOps.writeAtomically(fileFor(l.name), ControlLayouts.encode(l).toByteArray())

    suspend fun save(l: ControlLayout, previousName: String? = null) = withContext(Dispatchers.IO) {
        val valid = ControlLayouts.validate(l)
        if (previousName != null && previousName != valid.name) fileFor(previousName).delete()
        write(valid)
        if (settings.current().activeControlLayout == previousName) settings.update { it.copy(activeControlLayout = valid.name) }
        publishActive()
        load()
    }

    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        fileFor(name).delete()
        load()
    }

    suspend fun createFromPreset(preset: ControlPreset, name: String): ControlLayout {
        val unique = uniqueName(name)
        val l = ControlLayouts.preset(preset).copy(name = unique, preset = preset.name)
        save(l)
        return l
    }

    private fun uniqueName(base: String): String {
        var n = base.trim().take(50).ifEmpty { "Layout" }
        var i = 2
        while (fileFor(n).exists()) n = "${base.take(46)} ${i++}"
        return n
    }

    suspend fun setActive(name: String) {
        settings.update { it.copy(activeControlLayout = name) }
        withContext(Dispatchers.IO) { publishActive() }
    }

    /** Copies the active layout to the fixed path the runtime reads. */
    private suspend fun publishActive() {
        val name = settings.current().activeControlLayout
        val src = fileFor(name)
        if (src.isFile) FileOps.writeAtomically(File(root, "active.json"), src.readBytes())
    }

    suspend fun import(uri: Uri): ControlLayout = withContext(Dispatchers.IO) {
        val tmp = storage.copyToTemp(uri, 512L * 1024)
        try {
            val l = ControlLayouts.decode(tmp.readText())
            val named = l.copy(name = uniqueName(l.name))
            write(named); load(); named
        } finally { tmp.delete() }
    }

    suspend fun export(name: String, uri: Uri) = withContext(Dispatchers.IO) {
        val f = fileFor(name)
        if (!f.isFile) throw IOException("Layout not found")
        storage.exportTo(f, uri)
    }

    // ---- gamepads ----
    private fun padFile(descriptor: String) = File(padsDir, Hashing.digest(descriptor.byteInputStream(), HashAlgorithm.SHA256).take(24) + ".json")

    suspend fun gamepad(descriptor: String, name: String): GamepadProfile = withContext(Dispatchers.IO) {
        val f = padFile(descriptor)
        if (f.isFile) runCatching { GamepadProfile.decode(f.readText()) }.getOrNull()?.let { return@withContext it }
        GamepadProfile(descriptor, name)
    }

    suspend fun saveGamepad(p: GamepadProfile) = withContext(Dispatchers.IO) {
        FileOps.writeAtomically(padFile(p.descriptor), GamepadProfile.encode(p).toByteArray())
    }

    suspend fun resetGamepad(descriptor: String) = withContext(Dispatchers.IO) { padFile(descriptor).delete() }
}
