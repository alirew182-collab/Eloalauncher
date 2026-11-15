package dev.eloa.launcher.domain.java.runtime

import dev.eloa.launcher.core.common.archive.ArchiveLimits
import dev.eloa.launcher.core.common.archive.SafeZip
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

data class JavaRuntimeInfo(val home: File, val majorVersion: Int, val fullVersion: String, val arch: String, val vendor: String?)

class RuntimeValidationException(message: String) : IOException(message)

/**
 * Validates a Java runtime built for Android/Linux: reads the `release` file and checks that
 * libjvm.so is an ELF for the device architecture. The runtime is never executed here.
 */
object JavaRuntimeInspector {
    private val ABI_TO_ELF_MACHINE = mapOf("arm64-v8a" to 183, "armeabi-v7a" to 40, "x86_64" to 62, "x86" to 3)
    private val ABI_TO_ARCH = mapOf("arm64-v8a" to setOf("aarch64", "arm64"), "armeabi-v7a" to setOf("arm", "aarch32"), "x86_64" to setOf("x86_64", "amd64"), "x86" to setOf("x86", "i386", "i686"))

    fun parseRelease(text: String): Map<String, String> = text.lineSequence()
        .filter { it.contains('=') }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().trim('"') }

    fun majorOf(version: String): Int {
        val v = version.removePrefix("1.")
        return v.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }

    fun inspect(home: File, deviceAbi: String): JavaRuntimeInfo {
        val release = File(home, "release")
        if (!release.isFile) throw RuntimeValidationException("Not a Java runtime: 'release' file missing")
        val props = parseRelease(release.readText())
        val version = props["JAVA_VERSION"] ?: throw RuntimeValidationException("JAVA_VERSION missing in release file")
        val arch = props["OS_ARCH"] ?: "unknown"
        val libjvm = home.walkTopDown().maxDepth(5).firstOrNull { it.isFile && it.name == "libjvm.so" }
            ?: throw RuntimeValidationException("libjvm.so not found — runtime is not built for Linux/Android")
        val machine = elfMachine(libjvm) ?: throw RuntimeValidationException("libjvm.so is not a valid ELF library")
        val expected = ABI_TO_ELF_MACHINE[deviceAbi]
        if (expected != null && machine != expected) throw RuntimeValidationException("Runtime architecture ($arch) does not match device ABI $deviceAbi")
        if (ABI_TO_ARCH[deviceAbi]?.contains(arch.lowercase()) == false) throw RuntimeValidationException("Runtime OS_ARCH $arch does not match $deviceAbi")
        return JavaRuntimeInfo(home, majorOf(version), version, arch, props["IMPLEMENTOR"])
    }

    fun elfMachine(f: File): Int? = try {
        RandomAccessFile(f, "r").use { raf ->
            val h = ByteArray(20)
            if (raf.read(h) != 20) return null
            if (h[0] != 0x7f.toByte() || h[1] != 'E'.code.toByte() || h[2] != 'L'.code.toByte() || h[3] != 'F'.code.toByte()) return null
            val le = h[5].toInt() == 1
            if (le) (h[18].toInt() and 0xFF) or ((h[19].toInt() and 0xFF) shl 8) else ((h[18].toInt() and 0xFF) shl 8) or (h[19].toInt() and 0xFF)
        }
    } catch (_: IOException) { null }

    /** Unpacks a runtime ZIP safely into [target] (stripping a single top-level folder) and validates it. */
    fun importZip(zip: File, target: File, deviceAbi: String): JavaRuntimeInfo {
        val staging = File(target.parentFile, ".${target.name}.staging")
        staging.deleteRecursively()
        try {
            SafeZip.open(zip, ArchiveLimits(maxTotalUncompressed = 1024L * 1024 * 1024)).use { z ->
                val tops = z.entries.map { it.name.substringBefore('/') }.toSet()
                val strip = if (tops.size == 1 && z.entries.any { it.name.contains('/') } && !z.contains("release")) tops.first() + "/" else ""
                z.extractTo(staging) { n -> n.removePrefix(strip).takeIf { it.isNotEmpty() } }
            }
            val info = inspect(staging, deviceAbi)
            target.deleteRecursively()
            if (!staging.renameTo(target)) throw IOException("Cannot move runtime into place")
            return info.copy(home = target)
        } finally {
            staging.deleteRecursively()
        }
    }
}
