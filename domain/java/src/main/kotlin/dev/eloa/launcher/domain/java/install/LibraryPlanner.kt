package dev.eloa.launcher.domain.java.install

import dev.eloa.launcher.domain.java.download.DownloadRequest
import dev.eloa.launcher.domain.java.meta.Library
import dev.eloa.launcher.domain.java.meta.MavenCoordinate
import dev.eloa.launcher.domain.java.meta.RuleEnvironment
import dev.eloa.launcher.domain.java.meta.VersionJson
import java.io.File

data class ResolvedLibrary(
    val library: Library,
    val file: File,
    /** null when the file is generated locally (e.g. by Forge processors) and cannot be downloaded. */
    val download: DownloadRequest?,
    val isNative: Boolean,
)

object LibraryPlanner {
    const val MOJANG_LIBRARIES = "https://libraries.minecraft.net/"

    fun resolve(version: VersionJson, dirs: GameDirectories, env: RuleEnvironment): List<ResolvedLibrary> {
        val out = ArrayList<ResolvedLibrary>()
        val seen = HashSet<String>()
        for (lib in version.libraries) {
            if (!env.allows(lib.rules)) continue
            val coord = runCatching { MavenCoordinate.parse(lib.name) }.getOrNull() ?: continue
            // Main artifact
            val artifact = lib.downloads?.artifact
            if (artifact != null || lib.natives == null) {
                val path = artifact?.path ?: coord.path
                if (seen.add(path)) {
                    val file = dirs.library(path)
                    val url = when {
                        artifact != null -> artifact.url
                        lib.url != null -> lib.url.trimEnd('/') + "/" + coord.path
                        else -> MOJANG_LIBRARIES + coord.path
                    }
                    val req = url.takeIf { it.isNotBlank() }?.let {
                        DownloadRequest(it, file, artifact?.sha1 ?: lib.sha1, artifact?.size ?: lib.size, coord.artifact)
                    }
                    out += ResolvedLibrary(lib, file, req, isNative = coord.classifier?.startsWith("natives-") == true)
                }
            }
            // Legacy natives (pre-1.19 style classifiers)
            val nativeKey = lib.natives?.get(env.osName)?.replace("\${arch}", if (env.osArch.contains("64")) "64" else "32")
            if (nativeKey != null) {
                val nat = lib.downloads?.classifiers?.get(nativeKey)
                val path = nat?.path ?: coord.copy(classifier = nativeKey).path
                if (seen.add(path)) {
                    val file = dirs.library(path)
                    val url = nat?.url ?: ((lib.url ?: MOJANG_LIBRARIES).trimEnd('/') + "/" + coord.copy(classifier = nativeKey).path)
                    out += ResolvedLibrary(lib, file, DownloadRequest(url, file, nat?.sha1, nat?.size ?: -1, "${coord.artifact}-$nativeKey"), true)
                }
            }
        }
        return out
    }

    fun classpath(version: VersionJson, dirs: GameDirectories, env: RuleEnvironment): List<File> =
        resolve(version, dirs, env).filter { !it.isNative || it.library.natives == null }.map { it.file }.distinct()
}
