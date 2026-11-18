package dev.eloa.launcher.feature.java

import android.net.Uri
import dev.eloa.launcher.core.common.archive.SafeZip
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.common.io.PathSafety
import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.data.db.JavaProfileEntity
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.domain.java.content.ContentValidator
import dev.eloa.launcher.domain.java.content.JavaContentType
import dev.eloa.launcher.domain.java.content.PackFormats
import dev.eloa.launcher.domain.java.mods.ModCompatibilityChecker
import dev.eloa.launcher.domain.java.mods.ModEnvironment
import dev.eloa.launcher.domain.java.mods.ModFile
import dev.eloa.launcher.domain.java.mods.ModIssue
import dev.eloa.launcher.domain.java.mods.ModManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class ContentItem(
    val fileName: String,
    val displayName: String,
    val sizeBytes: Long,
    val enabled: Boolean,
    val isDirectory: Boolean,
    val description: String,
    val compat: PackFormats.Compat,
    val lastModified: Long,
)

/** Manages per-profile Java content (resource/shader/datapacks, worlds, configs) and mods. */
@Singleton
class ContentRepository @Inject constructor(private val storage: LauncherStorage, private val logger: AppLogger) {

    fun modManager(p: JavaProfileEntity): ModManager {
        val inst = storage.instance(p.gameDirName)
        return ModManager(inst.mods, java.io.File(inst.backups, "mods"))
    }

    suspend fun mods(p: JavaProfileEntity): List<ModFile> = withContext(Dispatchers.IO) { modManager(p).list() }

    fun checkMods(p: JavaProfileEntity, mods: List<ModFile>): List<ModIssue> =
        ModCompatibilityChecker.check(mods, ModEnvironment(p.loaderType, p.minecraftVersion, p.loaderVersion))

    suspend fun importMod(p: JavaProfileEntity, uri: Uri): ModFile = withContext(Dispatchers.IO) {
        val tmp = storage.copyToTemp(uri, 512L * 1024 * 1024)
        try { modManager(p).import(tmp, storage.displayName(uri)) } finally { tmp.delete() }
    }

    private fun dirFor(p: JavaProfileEntity, type: JavaContentType, world: String?): File {
        val inst = storage.instance(p.gameDirName)
        return when (type) {
            JavaContentType.RESOURCE_PACKS -> inst.resourcePacks
            JavaContentType.SHADER_PACKS -> inst.shaderPacks
            JavaContentType.WORLDS -> inst.saves
            JavaContentType.CONFIGS -> inst.config
            JavaContentType.DATAPACKS -> inst.datapacks(world ?: throw IOException("Choose a world first"))
        }
    }

    suspend fun list(p: JavaProfileEntity, type: JavaContentType, world: String? = null): List<ContentItem> = withContext(Dispatchers.IO) {
        val dir = dirFor(p, type, world)
        dir.listFiles().orEmpty().filter { !it.name.startsWith(".") }.map { f ->
            val enabled = !f.name.endsWith(ModManager.DISABLED_SUFFIX)
            var desc = ""
            var compat = PackFormats.Compat.UNKNOWN
            if (f.isFile && (type == JavaContentType.RESOURCE_PACKS || type == JavaContentType.DATAPACKS) && SafeZip.isZip(f)) {
                runCatching { SafeZip.open(f).use { z -> PackFormats.readPackMeta(z) } }.getOrNull()?.let { m ->
                    desc = m.description
                    if (type == JavaContentType.RESOURCE_PACKS) compat = PackFormats.compatibility(m, p.minecraftVersion)
                }
            }
            if (type == JavaContentType.WORLDS && f.isDirectory) desc = if (File(f, "level.dat").isFile) "World · ${FileOps.formatSize(FileOps.sizeOf(f))}" else "Folder without level.dat"
            ContentItem(f.name, f.name.removeSuffix(ModManager.DISABLED_SUFFIX).removeSuffix(".zip"), if (f.isDirectory) FileOps.sizeOf(f) else f.length(),
                enabled, f.isDirectory, desc, compat, f.lastModified())
        }.sortedBy { it.displayName.lowercase() }
    }

    suspend fun import(p: JavaProfileEntity, type: JavaContentType, uri: Uri, world: String? = null): String = withContext(Dispatchers.IO) {
        val name = storage.displayName(uri)
        val tmp = storage.copyToTemp(uri, if (type == JavaContentType.WORLDS) 4L * 1024 * 1024 * 1024 else 1024L * 1024 * 1024)
        try {
            val v = ContentValidator.validate(type, tmp, name)
            if (!v.ok) throw IOException(v.message)
            val dir = dirFor(p, type, world).apply { mkdirs() }
            val safe = PathSafety.sanitizeFileName(name)
            if (type == JavaContentType.WORLDS) {
                var folder = PathSafety.sanitizeFileName(name.substringBeforeLast('.'), "world")
                var i = 2
                while (File(dir, folder).exists()) folder = "${name.substringBeforeLast('.')}-${i++}".let { PathSafety.sanitizeFileName(it) }
                SafeZip.open(tmp).use { z ->
                    val levelDat = z.entries.first { it.name == "level.dat" || it.name.endsWith("/level.dat") }.name
                    val prefix = levelDat.removeSuffix("level.dat")
                    z.extractTo(File(dir, folder), filter = { it.startsWith(prefix) }) { it.removePrefix(prefix) }
                }
                logger.i("Content", "World imported", mapOf("world" to folder))
                return@withContext folder
            }
            val target = File(dir, safe)
            if (target.exists() || File(dir, safe + ModManager.DISABLED_SUFFIX).exists()) throw FileAlreadyExistsException(target, reason = "Already installed")
            FileOps.move(tmp, target)
            logger.i("Content", "Imported ${type.folder}/${target.name}")
            target.name
        } finally { tmp.delete() }
    }

    suspend fun setEnabled(p: JavaProfileEntity, type: JavaContentType, fileName: String, enabled: Boolean, world: String? = null) = withContext(Dispatchers.IO) {
        val dir = dirFor(p, type, world)
        val f = PathSafety.resolveInside(dir, fileName)
        val isEnabled = !fileName.endsWith(ModManager.DISABLED_SUFFIX)
        if (isEnabled == enabled) return@withContext
        val newName = if (enabled) fileName.removeSuffix(ModManager.DISABLED_SUFFIX) else fileName + ModManager.DISABLED_SUFFIX
        if (File(dir, newName).exists()) throw IOException("$newName already exists")
        if (!f.renameTo(File(dir, newName))) throw IOException("Cannot rename $fileName")
    }

    suspend fun delete(p: JavaProfileEntity, type: JavaContentType, fileName: String, world: String? = null) = withContext(Dispatchers.IO) {
        val f = PathSafety.resolveInside(dirFor(p, type, world), fileName)
        if (!f.deleteRecursively()) throw IOException("Cannot delete $fileName")
        logger.i("Content", "Deleted ${type.folder}/$fileName")
    }

    /** Exports a file or (zipped) folder to a SAF document. */
    suspend fun export(p: JavaProfileEntity, type: JavaContentType, fileName: String, uri: Uri, world: String? = null) = withContext(Dispatchers.IO) {
        val f = PathSafety.resolveInside(dirFor(p, type, world), fileName)
        if (f.isDirectory) {
            val zip = File(storage.importTemp, "${f.name}.zip").apply { parentFile?.mkdirs() }
            try { ZipUtil.zipDirectory(f, zip); storage.exportTo(zip, uri) } finally { zip.delete() }
        } else storage.exportTo(f, uri)
    }

    fun readConfig(p: JavaProfileEntity, fileName: String): String {
        val f = PathSafety.resolveInside(storage.instance(p.gameDirName).config, fileName)
        if (f.length() > 2 * 1024 * 1024) throw IOException("File too large to edit")
        return f.readText()
    }

    fun writeConfig(p: JavaProfileEntity, fileName: String, text: String) {
        val f = PathSafety.resolveInside(storage.instance(p.gameDirName).config, fileName)
        File(f.parentFile, ".${f.name}.bak").let { if (f.isFile) f.copyTo(it, overwrite = true) }
        FileOps.writeAtomically(f, text.toByteArray())
    }

    fun shaderLoaderPresent(mods: List<ModFile>): Boolean =
        mods.any { m -> m.enabled && m.metadata.any { it.id in setOf("iris", "oculus", "optifine", "optifabric") } }
}

object ZipUtil {
    fun zipDirectory(dir: File, out: File) {
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { z ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                z.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(dir).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }
}
