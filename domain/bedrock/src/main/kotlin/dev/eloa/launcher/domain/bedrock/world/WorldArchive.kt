package dev.eloa.launcher.domain.bedrock.world

import dev.eloa.launcher.core.common.archive.ArchiveLimits
import dev.eloa.launcher.core.common.archive.CorruptArchiveException
import dev.eloa.launcher.core.common.archive.SafeZip
import dev.eloa.launcher.core.common.hash.HashAlgorithm
import dev.eloa.launcher.core.common.hash.Hashing
import dev.eloa.launcher.domain.bedrock.nbt.Nbt
import dev.eloa.launcher.domain.bedrock.nbt.NbtException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One file of a world folder, independent of where it lives (java.io.File or a SAF document). */
class WorldFile(val path: String, val size: Long, val open: () -> InputStream)

data class WorldMeta(
    val levelName: String,
    val gameType: Int?,
    val lastPlayed: Long?,
    val lastOpenedWithVersion: String?,
    val hasDb: Boolean,
    val fileCount: Int,
    val uncompressedBytes: Long,
    /** world_icon.jpeg bytes (bounded), if present. */
    val thumbnail: ByteArray?,
) {
    override fun equals(other: Any?) = other is WorldMeta && other.levelName == levelName && other.fileCount == fileCount && other.uncompressedBytes == uncompressedBytes
    override fun hashCode() = levelName.hashCode() * 31 + fileCount
}

enum class WorldProblem { NO_LEVEL_DAT, LEVEL_DAT_UNREADABLE, NO_DB, CORRUPT_ARCHIVE, CHECKSUM_MISMATCH, UNSAFE_PATH }

class WorldArchiveException(val problem: WorldProblem, message: String, cause: Throwable? = null) : IOException(message, cause)

data class VerifyResult(val ok: Boolean, val problems: List<WorldProblem>, val sha256: String?)

/**
 * Bedrock worlds as `.mcworld` archives (a ZIP whose root is the world folder: level.dat, db/,
 * levelname.txt, world_icon.jpeg). Backups use exactly this format so Minecraft can import them.
 */
object WorldArchive {
    private val LIMITS = ArchiveLimits(maxTotalUncompressed = 8L * 1024 * 1024 * 1024)
    private const val MAX_THUMB = 2 * 1024 * 1024
    private val SAFE_SEGMENT = Regex("^[^/\\\\:*?\"<>|\\u0000]+$")

    fun isSafePath(p: String): Boolean {
        if (p.isEmpty() || p.startsWith("/") || p.contains('\\') || p.length > 400) return false
        return p.trimEnd('/').split('/').all { it != ".." && it != "." && SAFE_SEGMENT.matches(it) }
    }

    /** Packs world files into [out] (stored as `.mcworld`). Returns the SHA-256 of the archive. */
    fun pack(files: Sequence<WorldFile>, out: File, isCancelled: () -> Boolean = { false }, onProgress: (Long) -> Unit = {}): String {
        val tmp = File(out.parentFile, out.name + ".part")
        out.parentFile?.mkdirs()
        var hasLevelDat = false
        var done = 0L
        try {
            ZipOutputStream(tmp.outputStream().buffered()).use { zos ->
                val buf = ByteArray(64 * 1024)
                for (f in files) {
                    if (isCancelled()) throw kotlinx.coroutines.CancellationException("Backup cancelled")
                    if (!isSafePath(f.path)) throw WorldArchiveException(WorldProblem.UNSAFE_PATH, "Unsafe file name in world: ${f.path.take(80)}")
                    if (f.path == "level.dat") hasLevelDat = true
                    zos.putNextEntry(ZipEntry(f.path))
                    f.open().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            zos.write(buf, 0, n)
                            done += n
                            onProgress(done)
                        }
                    }
                    zos.closeEntry()
                }
            }
            if (!hasLevelDat) throw WorldArchiveException(WorldProblem.NO_LEVEL_DAT, "The folder is not a Bedrock world (level.dat missing).")
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) throw IOException("Cannot finalise backup file")
            return Hashing.digest(out, HashAlgorithm.SHA256)
        } finally {
            tmp.delete()
        }
    }

    /** Files of a world folder on the local file system, with paths relative to [dir]. */
    fun filesOf(dir: File): Sequence<WorldFile> = dir.walkTopDown().filter { it.isFile }.map { f ->
        WorldFile(f.relativeTo(dir).invariantSeparatorsPath, f.length()) { f.inputStream() }
    }

    /** Reads metadata from a `.mcworld`; the world root may be the archive root or one folder deep. */
    fun inspect(archive: File): WorldMeta = SafeZip.open(archive, LIMITS).use { zip ->
        val names = zip.entries.filter { !it.isDirectory }
        val levelDat = names.map { it.name }.firstOrNull { it == "level.dat" || (it.count { c -> c == '/' } == 1 && it.endsWith("/level.dat")) }
            ?: throw WorldArchiveException(WorldProblem.NO_LEVEL_DAT, "No level.dat — not a Bedrock world.")
        val root = levelDat.removeSuffix("level.dat")
        val nbt = try { zip.readBytes(levelDat)?.let(::readLevelDat) } catch (e: NbtException) { null }
        val name = zip.readText(root + "levelname.txt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: (nbt?.get("LevelName") as? String) ?: archive.nameWithoutExtension
        WorldMeta(
            levelName = name.take(120),
            gameType = nbt?.get("GameType") as? Int,
            lastPlayed = (nbt?.get("LastPlayed") as? Long)?.let { if (it < 10_000_000_000L) it * 1000 else it },
            lastOpenedWithVersion = (nbt?.get("lastOpenedWithVersion") as? List<*>)?.mapNotNull { it as? Int }?.joinToString("."),
            hasDb = names.any { it.name.startsWith(root + "db/") },
            fileCount = names.size,
            uncompressedBytes = names.sumOf { it.size },
            thumbnail = zip.readBytes(root + "world_icon.jpeg", MAX_THUMB),
        )
    }

    /** Full CRC read of every entry, level.dat parse and (optionally) checksum comparison. */
    fun verify(archive: File, expectedSha256: String?): VerifyResult {
        val problems = ArrayList<WorldProblem>()
        val sha = runCatching { Hashing.digest(archive, HashAlgorithm.SHA256) }.getOrNull()
        if (expectedSha256 != null && !expectedSha256.equals(sha, ignoreCase = true)) problems += WorldProblem.CHECKSUM_MISMATCH
        try {
            SafeZip.open(archive, LIMITS).use { zip ->
                zip.verifyIntegrity()
                if (zip.entries.any { !isSafePath(it.name) }) problems += WorldProblem.UNSAFE_PATH
            }
            val meta = inspect(archive)
            if (!meta.hasDb) problems += WorldProblem.NO_DB
        } catch (e: WorldArchiveException) {
            problems += e.problem
        } catch (e: CorruptArchiveException) {
            problems += WorldProblem.CORRUPT_ARCHIVE
        } catch (e: IOException) {
            problems += WorldProblem.CORRUPT_ARCHIVE
        }
        return VerifyResult(problems.isEmpty(), problems.distinct(), sha)
    }

    /**
     * Streams every file of the world (root-relative, root folder stripped) to [sink]. Paths are
     * validated; the archive is verified first so a damaged backup never half-overwrites a world.
     */
    fun restore(archive: File, sink: (path: String, input: InputStream) -> Unit) {
        val v = verify(archive, null)
        if (!v.ok && (WorldProblem.CORRUPT_ARCHIVE in v.problems || WorldProblem.NO_LEVEL_DAT in v.problems || WorldProblem.UNSAFE_PATH in v.problems)) {
            throw WorldArchiveException(v.problems.first(), "Backup is damaged: ${v.problems.joinToString()}")
        }
        SafeZip.open(archive, LIMITS).use { zip ->
            val levelDat = zip.entries.map { it.name }.first { it == "level.dat" || it.endsWith("/level.dat") && it.count { c -> c == '/' } == 1 }
            val root = levelDat.removeSuffix("level.dat")
            for (e in zip.entries) {
                if (e.isDirectory || !e.name.startsWith(root)) continue
                val rel = e.name.removePrefix(root)
                if (!isSafePath(rel)) throw WorldArchiveException(WorldProblem.UNSAFE_PATH, "Unsafe path ${rel.take(80)}")
                zip.openStream(e.name) { sink(rel, it) }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readLevelDat(bytes: ByteArray): Map<String, Any?> {
        if (bytes.size < 9) throw NbtException("level.dat too small")
        return Nbt.readLittleEndian(ByteArrayInputStream(bytes, 8, bytes.size - 8)).second
    }
}
