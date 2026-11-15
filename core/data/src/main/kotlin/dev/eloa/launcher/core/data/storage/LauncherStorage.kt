package dev.eloa.launcher.core.data.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.common.io.PathSafety
import dev.eloa.launcher.domain.java.install.GameDirectories
import dev.eloa.launcher.domain.java.install.InstanceDirectories
import java.io.File
import java.io.IOException

class ImportTooLargeException(limit: Long) : IOException("File exceeds the import limit of ${FileOps.formatSize(limit)}")

data class StorageStats(val totalBytes: Long, val freeBytes: Long, val launcherBytes: Long, val cacheBytes: Long)

/**
 * All launcher data lives in app-specific storage (no broad storage permission needed).
 * User files enter/leave only through the Storage Access Framework.
 */
class LauncherStorage(private val context: Context) {
    val root: File get() = context.filesDir
    val javaRoot = File(context.filesDir, "minecraft")
    val instancesRoot = File(context.filesDir, "instances")
    val bedrockApks = File(context.filesDir, "bedrock/apks")
    val bedrockAddons = File(context.filesDir, "bedrock/addons")
    val exports = File(context.filesDir, "exports")
    val backups = File(context.filesDir, "backups")
    val importTemp: File get() = File(context.cacheDir, "imports")
    val shareDir: File get() = File(context.cacheDir, "share")

    val gameDirectories: GameDirectories get() = GameDirectories(javaRoot)

    fun instance(gameDirName: String) = InstanceDirectories(PathSafety.resolveInside(instancesRoot, gameDirName))

    fun ensureBaseDirs() = listOf(javaRoot, instancesRoot, bedrockApks, bedrockAddons, exports, backups, importTemp).forEach { it.mkdirs() }

    fun displayName(uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return File(uri.path ?: "file").name
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun size(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return -1
    }

    /**
     * Copies a SAF document into a private temp file (the original is never modified or deleted).
     * Enforces [maxBytes] and free space.
     */
    fun copyToTemp(uri: Uri, maxBytes: Long, onProgress: (Long) -> Unit = {}): File {
        importTemp.mkdirs()
        val name = PathSafety.sanitizeFileName(displayName(uri))
        val declared = size(uri)
        if (declared > maxBytes) throw ImportTooLargeException(maxBytes)
        if (declared > 0 && declared + 32L * 1024 * 1024 > freeBytes()) throw IOException("Not enough free space to import $name")
        val target = File.createTempFile("imp_", "_$name", importTemp)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw ImportTooLargeException(maxBytes)
                        out.write(buf, 0, n)
                        onProgress(total)
                    }
                }
            } ?: throw IOException("Cannot open $name")
            return target
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    /** Writes a private file to a SAF document chosen by the user. */
    fun exportTo(source: File, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out -> source.inputStream().use { it.copyTo(out) } }
            ?: throw IOException("Cannot write to the selected location")
    }

    fun freeBytes(): Long = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(0)
    fun totalBytes(): Long = runCatching { StatFs(context.filesDir.path).totalBytes }.getOrDefault(0)

    fun stats(): StorageStats = StorageStats(totalBytes(), freeBytes(), FileOps.sizeOf(context.filesDir), FileOps.sizeOf(context.cacheDir))

    /** Removes import temp files and stale partial downloads older than [maxAgeMs]. Returns bytes freed. */
    fun cleanupTemp(maxAgeMs: Long): Long {
        val now = System.currentTimeMillis()
        var freed = 0L
        listOf(importTemp, shareDir).forEach { dir ->
            dir.listFiles()?.filter { now - it.lastModified() > maxAgeMs }?.forEach { f ->
                val len = FileOps.sizeOf(f); if (f.deleteRecursively()) freed += len
            }
        }
        freed += dev.eloa.launcher.domain.java.download.DownloadManager.cleanupPartials(javaRoot, maxAgeMs, now)
        return freed
    }
}
