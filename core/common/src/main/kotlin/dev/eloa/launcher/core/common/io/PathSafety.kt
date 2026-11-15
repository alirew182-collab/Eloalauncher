package dev.eloa.launcher.core.common.io

import java.io.File
import java.io.IOException

/** Thrown when an untrusted path tries to escape its intended root directory. */
class PathTraversalException(message: String) : IOException(message)

object PathSafety {
    private val RESERVED_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )
    private val ILLEGAL_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    /**
     * Resolves [relative] (an untrusted path such as a ZIP entry name or a user supplied name)
     * against [root] and guarantees the result stays inside [root].
     */
    fun resolveInside(root: File, relative: String): File {
        if (relative.isEmpty()) throw PathTraversalException("Empty path")
        if (relative.indexOf('\u0000') >= 0) throw PathTraversalException("NUL byte in path")
        val normalizedInput = relative.replace('\\', '/')
        if (normalizedInput.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(normalizedInput)) {
            throw PathTraversalException("Absolute path not allowed: $relative")
        }
        val segments = normalizedInput.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) throw PathTraversalException("Parent reference not allowed: $relative")
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, segments.joinToString(File.separator)).canonicalFile
        val rootPath = canonicalRoot.path
        if (target.path != rootPath && !target.path.startsWith(rootPath + File.separator)) {
            throw PathTraversalException("Path escapes root: $relative")
        }
        return target
    }

    /** Converts arbitrary user text into a safe single file name. */
    fun sanitizeFileName(name: String, fallback: String = "file"): String {
        var cleaned = name.trim().replace(ILLEGAL_CHARS, "_").trim('.', ' ')
        if (cleaned.isEmpty()) cleaned = fallback
        if (cleaned.substringBefore('.').uppercase() in RESERVED_NAMES) cleaned = "_$cleaned"
        if (cleaned.length > 120) {
            val ext = cleaned.substringAfterLast('.', "")
            val keep = if (ext.isNotEmpty() && ext.length < 10) 120 - ext.length - 1 else 120
            cleaned = cleaned.take(keep) + if (ext.isNotEmpty() && ext.length < 10) ".$ext" else ""
        }
        return cleaned
    }

    /** Returns true when [name] is acceptable as-is for a single path segment. */
    fun isValidFileName(name: String): Boolean =
        name.isNotBlank() && name == sanitizeFileName(name) && name != "." && name != ".."
}
