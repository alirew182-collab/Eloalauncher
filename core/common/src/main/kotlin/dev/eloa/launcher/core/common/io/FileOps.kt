package dev.eloa.launcher.core.common.io

import java.io.File
import java.io.IOException

object FileOps {
    /** Total size in bytes of a file or directory tree (symlinks are not followed). */
    fun sizeOf(file: File): Long {
        if (!file.exists()) return 0
        if (file.isFile) return file.length()
        var total = 0L
        file.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile }.forEach { total += it.length() }
        return total
    }

    /** Copies [source] (file or directory) into [target]; refuses to copy a dir into itself. */
    fun copy(source: File, target: File, overwrite: Boolean = false) {
        if (!source.exists()) throw IOException("Source does not exist: ${source.name}")
        val src = source.canonicalFile
        val dst = target.canonicalFile
        if (dst.path == src.path || dst.path.startsWith(src.path + File.separator)) {
            throw IOException("Cannot copy a folder into itself")
        }
        if (dst.exists() && !overwrite) throw FileAlreadyExistsException(dst)
        if (src.isDirectory) {
            src.copyRecursively(dst, overwrite)
        } else {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite)
        }
    }

    /** Moves via rename when possible, otherwise copy + delete. */
    fun move(source: File, target: File, overwrite: Boolean = false) {
        if (target.exists() && !overwrite) throw FileAlreadyExistsException(target)
        target.parentFile?.mkdirs()
        if (overwrite && target.exists()) target.deleteRecursively()
        if (!source.renameTo(target)) {
            copy(source, target, overwrite)
            if (!source.deleteRecursively()) throw IOException("Copied but could not delete source")
        }
    }

    /** Writes atomically: content goes to a temp file which is then renamed over [target]. */
    fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("Atomic rename failed for ${target.name}")
            }
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var i = 0
        while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
        return String.format(java.util.Locale.US, "%.1f %s", value, units[i])
    }
}
