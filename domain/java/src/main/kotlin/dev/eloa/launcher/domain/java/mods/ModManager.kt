package dev.eloa.launcher.domain.java.mods

import dev.eloa.launcher.core.common.archive.SafeZip
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.common.io.PathSafety
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * File-system based manager for a content folder (mods, resourcepacks, shaderpacks …).
 * Disabled entries carry the ".disabled" suffix — the convention understood by other launchers.
 */
class ModManager(private val dir: File, private val backupsDir: File) {
    companion object {
        const val DISABLED_SUFFIX = ".disabled"
        val MOD_EXTENSIONS = setOf("jar")
    }

    fun list(readMetadata: Boolean = true): List<ModFile> {
        val files = dir.listFiles()?.filter { it.isFile && isModFile(it.name) }.orEmpty()
        return files.map { f ->
            var error: String? = null
            val meta = if (readMetadata) try { ModMetadataReader.read(f) } catch (e: IOException) { error = e.message; emptyList() } else emptyList()
            ModFile(f.name, f.length(), !f.name.endsWith(DISABLED_SUFFIX), meta, error, f.lastModified())
        }.sortedBy { it.displayName.lowercase() }
    }

    private fun isModFile(name: String) = name.removeSuffix(DISABLED_SUFFIX).substringAfterLast('.', "").lowercase() in MOD_EXTENSIONS

    fun setEnabled(fileName: String, enabled: Boolean): String {
        val f = PathSafety.resolveInside(dir, fileName)
        if (!f.isFile) throw IOException("Mod not found: $fileName")
        val isEnabled = !fileName.endsWith(DISABLED_SUFFIX)
        if (isEnabled == enabled) return fileName
        val newName = if (enabled) fileName.removeSuffix(DISABLED_SUFFIX) else fileName + DISABLED_SUFFIX
        val target = File(dir, newName)
        if (target.exists()) throw IOException("A file named $newName already exists")
        if (!f.renameTo(target)) throw IOException("Cannot rename $fileName")
        return newName
    }

    fun delete(fileName: String) {
        val f = PathSafety.resolveInside(dir, fileName)
        if (f.isFile && !f.delete()) throw IOException("Cannot delete $fileName")
    }

    /** Validates and copies a jar into the folder. Returns the new mod. */
    fun import(source: File, originalName: String, overwrite: Boolean = false): ModFile {
        val name = PathSafety.sanitizeFileName(originalName, "mod.jar").let { if (it.lowercase().endsWith(".jar")) it else "$it.jar" }
        if (!SafeZip.isZip(source)) throw IOException("$originalName is not a JAR archive")
        SafeZip.open(source).use { it.entries } // structure + limits
        val meta = ModMetadataReader.read(source)
        dir.mkdirs()
        val target = File(dir, name)
        if ((target.exists() || File(dir, name + DISABLED_SUFFIX).exists()) && !overwrite) throw FileAlreadyExistsException(target, reason = "Mod already installed")
        source.copyTo(target, overwrite = true)
        return ModFile(name, target.length(), true, meta, null, target.lastModified())
    }

    /** Creates a ZIP snapshot of the folder. Returns the backup file. */
    fun backup(label: String = "mods"): File {
        backupsDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(backupsDir, "${PathSafety.sanitizeFileName(label)}-$stamp.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            dir.listFiles()?.filter { it.isFile }?.forEach { f ->
                zip.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        return out
    }

    fun backups(label: String = "mods"): List<File> =
        backupsDir.listFiles()?.filter { it.name.startsWith("${PathSafety.sanitizeFileName(label)}-") && it.extension == "zip" }
            ?.sortedByDescending { it.name }.orEmpty()

    /**
     * Restores a snapshot: current content is first backed up, then replaced.
     * Extraction is Zip Slip-safe and flat (no sub-directories).
     */
    fun restore(backup: File, label: String = "mods"): File {
        val safety = backup(label + "-before-restore")
        val staging = File(dir.parentFile, ".${dir.name}.restore")
        staging.deleteRecursively()
        SafeZip.open(backup).use { it.extractTo(staging, filter = { n -> !n.contains('/') }) }
        dir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        staging.listFiles()?.forEach { FileOps.move(it, File(dir, it.name), overwrite = true) }
        staging.deleteRecursively()
        return safety
    }
}
