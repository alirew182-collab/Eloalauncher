package dev.eloa.launcher.domain.java.modpack

import dev.eloa.launcher.core.common.archive.SafeZip
import dev.eloa.launcher.core.common.io.PathSafety
import dev.eloa.launcher.domain.java.loader.ModLoaderType
import dev.eloa.launcher.domain.java.net.LauncherJson
import dev.eloa.launcher.domain.java.net.SourcePolicy
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

@Serializable
data class MrpackIndex(
    val formatVersion: Int,
    val game: String,
    val versionId: String = "",
    val name: String,
    val summary: String? = null,
    val files: List<MrFile> = emptyList(),
    val dependencies: Map<String, String> = emptyMap(),
)

@Serializable
data class MrFile(
    val path: String,
    val hashes: Map<String, String>,
    val env: Map<String, String>? = null,
    val downloads: List<String>,
    val fileSize: Long = -1,
)

data class MrpackPlan(
    val name: String,
    val summary: String?,
    val minecraftVersion: String,
    val loader: ModLoaderType,
    val loaderVersion: String?,
    val files: List<MrFile>,
)

class ModpackException(message: String) : IOException(message)

/** Modrinth modpack (.mrpack) reader per the official format specification. */
object Mrpack {
    /** Download hosts allowed by the Modrinth format specification. */
    val ALLOWED_HOSTS = setOf("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com")
    val POLICY = SourcePolicy { url -> url.isHttps && url.host in ALLOWED_HOSTS }

    fun read(file: File): MrpackPlan = SafeZip.open(file).use { zip ->
        val text = zip.readText("modrinth.index.json") ?: throw ModpackException("modrinth.index.json missing — not a Modrinth modpack")
        val index = try { LauncherJson.decodeFromString(MrpackIndex.serializer(), text) } catch (e: Exception) {
            throw ModpackException("modrinth.index.json is invalid: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        plan(index)
    }

    fun plan(index: MrpackIndex): MrpackPlan {
        if (index.formatVersion != 1) throw ModpackException("Unsupported modpack format ${index.formatVersion}")
        if (index.game != "minecraft") throw ModpackException("Modpack is for '${index.game}', not Minecraft")
        val mc = index.dependencies["minecraft"] ?: throw ModpackException("Modpack does not declare a Minecraft version")
        val (loader, ver) = when {
            index.dependencies.containsKey("fabric-loader") -> ModLoaderType.FABRIC to index.dependencies["fabric-loader"]
            index.dependencies.containsKey("quilt-loader") -> ModLoaderType.QUILT to index.dependencies["quilt-loader"]
            index.dependencies.containsKey("neoforge") -> ModLoaderType.NEOFORGE to index.dependencies["neoforge"]
            index.dependencies.containsKey("forge") -> ModLoaderType.FORGE to index.dependencies["forge"]?.let { if (it.startsWith("$mc-")) it else "$mc-$it" }
            else -> ModLoaderType.VANILLA to null
        }
        val files = index.files.filter { it.env?.get("client") != "unsupported" }
        files.forEach { f ->
            validatePath(f.path)
            if (f.hashes["sha1"]?.length != 40) throw ModpackException("File ${f.path} has no valid SHA-1")
            if (f.downloads.isEmpty()) throw ModpackException("File ${f.path} has no download URL")
        }
        return MrpackPlan(index.name, index.summary, mc, loader, ver, files)
    }

    /** Paths must be relative, inside the instance, and never touch launcher internals. */
    fun validatePath(path: String) {
        if (path.startsWith("/") || path.contains("..") || path.contains('\\') || path.contains(':')) throw ModpackException("Unsafe path in modpack: $path")
        PathSafety.resolveInside(File("/instance"), path)
    }

    /** Extracts overrides/ and client-overrides/ (client wins) into the instance directory. */
    fun extractOverrides(file: File, gameDir: File): Int = SafeZip.open(file).use { zip ->
        var count = 0
        for (prefix in listOf("overrides/", "client-overrides/")) {
            count += zip.extractTo(gameDir, filter = { it.startsWith(prefix) && it.length > prefix.length }) { it.removePrefix(prefix) }.size
        }
        count
    }
}
