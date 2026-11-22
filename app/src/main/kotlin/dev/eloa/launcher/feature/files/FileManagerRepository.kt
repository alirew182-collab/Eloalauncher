package dev.eloa.launcher.feature.files

import android.net.Uri
import dev.eloa.launcher.core.common.hash.HashAlgorithm
import dev.eloa.launcher.core.common.hash.Hashing
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.common.io.PathSafety
import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.feature.java.ZipUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

enum class FileRoot(@param:androidx.annotation.StringRes val labelRes: Int) {
    JAVA_DATA(dev.eloa.launcher.R.string.root_java_data), INSTANCES(dev.eloa.launcher.R.string.root_instances), BEDROCK(dev.eloa.launcher.R.string.root_bedrock),
    EXPORTS(dev.eloa.launcher.R.string.root_exports), BACKUPS(dev.eloa.launcher.R.string.root_backups);
    val label: String get() = dev.eloa.launcher.feature.common.str(labelRes)
}
enum class FileSort { NAME, SIZE, DATE }

data class FileEntry(val name: String, val relativePath: String, val isDirectory: Boolean, val size: Long, val lastModified: Long, val childCount: Int)

/**
 * File manager restricted to the launcher's own storage. Every path is resolved inside a known
 * root; user files outside are reached only through SAF import/export.
 */
@Singleton
class FileManagerRepository @Inject constructor(private val storage: LauncherStorage, private val logger: AppLogger) {

    fun rootDir(root: FileRoot): File = when (root) {
        FileRoot.JAVA_DATA -> storage.javaRoot
        FileRoot.INSTANCES -> storage.instancesRoot
        FileRoot.BEDROCK -> storage.bedrockApks.parentFile!!
        FileRoot.EXPORTS -> storage.exports
        FileRoot.BACKUPS -> storage.backups
    }.apply { mkdirs() }

    fun resolve(root: FileRoot, rel: String): File = if (rel.isEmpty()) rootDir(root).canonicalFile else PathSafety.resolveInside(rootDir(root), rel)

    suspend fun list(root: FileRoot, rel: String, sort: FileSort, computeDirSizes: Boolean): List<FileEntry> = withContext(Dispatchers.IO) {
        val dir = resolve(root, rel)
        if (!dir.isDirectory) throw IOException("Folder no longer exists")
        val base = rootDir(root).canonicalFile
        val entries = dir.listFiles().orEmpty().map { f ->
            FileEntry(f.name, f.canonicalFile.relativeTo(base).invariantSeparatorsPath, f.isDirectory,
                if (f.isDirectory) (if (computeDirSizes) FileOps.sizeOf(f) else -1) else f.length(), f.lastModified(), if (f.isDirectory) f.list()?.size ?: 0 else 0)
        }
        val dirsFirst = compareByDescending<FileEntry> { it.isDirectory }
        entries.sortedWith(when (sort) {
            FileSort.NAME -> dirsFirst.thenBy { it.name.lowercase() }
            FileSort.SIZE -> dirsFirst.thenByDescending { it.size }
            FileSort.DATE -> dirsFirst.thenByDescending { it.lastModified }
        })
    }

    suspend fun search(root: FileRoot, query: String, limit: Int = 500): List<FileEntry> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val base = rootDir(root).canonicalFile
        val q = query.lowercase()
        val out = ArrayList<FileEntry>()
        for (f in base.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }) {
            coroutineContext.ensureActive()
            if (f != base && f.name.lowercase().contains(q)) {
                out += FileEntry(f.name, f.relativeTo(base).invariantSeparatorsPath, f.isDirectory, if (f.isFile) f.length() else -1, f.lastModified(), 0)
                if (out.size >= limit) break
            }
        }
        out
    }

    suspend fun createFolder(root: FileRoot, rel: String, name: String) = withContext(Dispatchers.IO) {
        require(PathSafety.isValidFileName(name)) { "Invalid folder name" }
        val f = File(resolve(root, rel), name)
        if (f.exists()) throw IOException("$name already exists")
        if (!f.mkdirs()) throw IOException("Cannot create $name")
    }

    suspend fun rename(root: FileRoot, rel: String, newName: String) = withContext(Dispatchers.IO) {
        require(PathSafety.isValidFileName(newName)) { "Invalid name" }
        val f = resolve(root, rel)
        if (f == rootDir(root).canonicalFile) throw IOException("Cannot rename the root folder")
        val target = File(f.parentFile, newName)
        if (target.exists()) throw IOException("$newName already exists")
        if (!f.renameTo(target)) throw IOException("Rename failed")
    }

    suspend fun copyOrMove(srcRoot: FileRoot, srcRel: String, dstRoot: FileRoot, dstDirRel: String, move: Boolean) = withContext(Dispatchers.IO) {
        val src = resolve(srcRoot, srcRel)
        if (src == rootDir(srcRoot).canonicalFile) throw IOException("Cannot ${if (move) "move" else "copy"} a root folder")
        var target = File(resolve(dstRoot, dstDirRel), src.name)
        if (target.canonicalPath == src.canonicalPath) target = File(target.parentFile, "${src.nameWithoutExtension} (copy)${if (src.extension.isNotEmpty()) "." + src.extension else ""}")
        if (target.exists()) throw IOException("${target.name} already exists in the destination")
        if (move) FileOps.move(src, target) else FileOps.copy(src, target)
        logger.i("Files", "${if (move) "Moved" else "Copied"} ${src.name}")
    }

    suspend fun delete(root: FileRoot, rel: String) = withContext(Dispatchers.IO) {
        val f = resolve(root, rel)
        if (f == rootDir(root).canonicalFile) throw IOException("Cannot delete a root folder")
        if (!f.deleteRecursively()) throw IOException("Could not delete ${f.name} completely")
        logger.i("Files", "Deleted ${f.name}")
    }

    suspend fun import(root: FileRoot, dirRel: String, uri: Uri): String = withContext(Dispatchers.IO) {
        val tmp = storage.copyToTemp(uri, 4L * 1024 * 1024 * 1024)
        val name = PathSafety.sanitizeFileName(storage.displayName(uri))
        val target = File(resolve(root, dirRel), name)
        if (target.exists()) { tmp.delete(); throw IOException("$name already exists here") }
        FileOps.move(tmp, target)
        name
    }

    /** Exports a file directly, or a folder as ZIP, to a SAF document. */
    suspend fun export(root: FileRoot, rel: String, uri: Uri) = withContext(Dispatchers.IO) {
        val f = resolve(root, rel)
        if (f.isDirectory) {
            val zip = File(storage.importTemp, "${f.name}.zip").apply { parentFile?.mkdirs() }
            try { ZipUtil.zipDirectory(f, zip); storage.exportTo(zip, uri) } finally { zip.delete() }
        } else storage.exportTo(f, uri)
    }

    suspend fun sha256(root: FileRoot, rel: String): String = withContext(Dispatchers.IO) { Hashing.digest(resolve(root, rel), HashAlgorithm.SHA256) }

    /** Integrity of a ZIP/JAR: decompresses every entry and checks CRCs. */
    suspend fun checkArchive(root: FileRoot, rel: String): String = withContext(Dispatchers.IO) {
        val f = resolve(root, rel)
        if (!dev.eloa.launcher.core.common.archive.SafeZip.isZip(f)) return@withContext "Not an archive — SHA-256: ${Hashing.digest(f, HashAlgorithm.SHA256)}"
        try {
            dev.eloa.launcher.core.common.archive.SafeZip.open(f).use { z -> z.verifyIntegrity(); "Archive OK — ${z.entries.size} entries verified" }
        } catch (e: IOException) { "Archive damaged: ${e.message}" }
    }

    /** Zips a folder into Backups/ with a timestamp. */
    suspend fun backup(root: FileRoot, rel: String): String = withContext(Dispatchers.IO) {
        val f = resolve(root, rel)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(storage.backups, "${PathSafety.sanitizeFileName(f.name)}-$stamp.zip")
        if (f.isDirectory) ZipUtil.zipDirectory(f, out) else f.copyTo(File(storage.backups, "${f.nameWithoutExtension}-$stamp.${f.extension}"))
        logger.i("Files", "Backup created", mapOf("name" to out.name))
        out.name
    }
}
