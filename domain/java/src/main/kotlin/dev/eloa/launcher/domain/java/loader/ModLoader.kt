package dev.eloa.launcher.domain.java.loader

import dev.eloa.launcher.core.common.archive.SafeZip
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.common.version.ComparableVersion
import dev.eloa.launcher.domain.java.install.GameDirectories
import dev.eloa.launcher.domain.java.meta.Library
import dev.eloa.launcher.domain.java.meta.VersionJson
import dev.eloa.launcher.domain.java.net.LauncherJson
import dev.eloa.launcher.domain.java.net.MetaClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException

enum class ModLoaderType(val displayName: String) {
    VANILLA("Vanilla"), FABRIC("Fabric"), QUILT("Quilt"), FORGE("Forge"), NEOFORGE("NeoForge");

    /** Mod metadata formats this loader can load. Quilt loads Fabric mods too. */
    val acceptedModFormats: Set<ModLoaderType> get() = when (this) {
        VANILLA -> emptySet()
        FABRIC -> setOf(FABRIC)
        QUILT -> setOf(QUILT, FABRIC)
        FORGE -> setOf(FORGE)
        NEOFORGE -> setOf(NEOFORGE, FORGE) // NeoForge 1.20.1 still reads mods.toml
    }
}

data class LoaderVersion(val version: String, val stable: Boolean, val minecraftVersion: String)

/** Result of installing a loader profile. */
data class LoaderProfile(
    val loader: ModLoaderType,
    val versionId: String,
    val profile: VersionJson,
    /** Forge/NeoForge only: processor steps that must be executed by a Java runtime before first launch. */
    val pendingProcessors: Int = 0,
)

interface LoaderProvider {
    val type: ModLoaderType
    suspend fun versions(minecraftVersion: String): List<LoaderVersion>
    suspend fun install(minecraftVersion: String, loaderVersion: String, dirs: GameDirectories): LoaderProfile
}

/** Fabric and Quilt share the same "meta" API shape and produce inheritsFrom profiles. */
class FabricLikeProvider(override val type: ModLoaderType, private val meta: MetaClient) : LoaderProvider {
    private val base = when (type) {
        ModLoaderType.FABRIC -> "https://meta.fabricmc.net/v2"
        ModLoaderType.QUILT -> "https://meta.quiltmc.org/v3"
        else -> throw IllegalArgumentException("Not a Fabric-like loader: $type")
    }

    @Serializable private data class LoaderEntry(val loader: LoaderInfo)
    @Serializable private data class LoaderInfo(val version: String, val stable: Boolean? = null)

    override suspend fun versions(minecraftVersion: String): List<LoaderVersion> {
        val text = meta.getText("$base/versions/loader/${encode(minecraftVersion)}")
        return LauncherJson.decodeFromString<List<LoaderEntry>>(text).map {
            // Quilt has no "stable" flag; betas contain "beta".
            LoaderVersion(it.loader.version, it.loader.stable ?: !it.loader.version.contains("beta"), minecraftVersion)
        }
    }

    override suspend fun install(minecraftVersion: String, loaderVersion: String, dirs: GameDirectories): LoaderProfile {
        val text = meta.getText("$base/versions/loader/${encode(minecraftVersion)}/${encode(loaderVersion)}/profile/json")
        val profile = VersionJson.parse(text)
        if (profile.inheritsFrom != minecraftVersion) throw IOException("Unexpected profile: inherits ${profile.inheritsFrom}")
        FileOps.writeAtomically(dirs.versionJson(profile.id), text.toByteArray())
        return LoaderProfile(type, profile.id, profile)
    }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/**
 * Forge and NeoForge: versions come from the official maven-metadata.xml; installation reads
 * version.json + install_profile.json from the official installer jar.
 */
class ForgeLikeProvider(override val type: ModLoaderType, private val meta: MetaClient) : LoaderProvider {
    private val mavenBase = when (type) {
        ModLoaderType.FORGE -> "https://maven.minecraftforge.net/net/minecraftforge/forge"
        ModLoaderType.NEOFORGE -> "https://maven.neoforged.net/releases/net/neoforged/neoforge"
        else -> throw IllegalArgumentException("Not a Forge-like loader: $type")
    }
    private val artifact = if (type == ModLoaderType.FORGE) "forge" else "neoforge"

    override suspend fun versions(minecraftVersion: String): List<LoaderVersion> {
        val xml = meta.getText("$mavenBase/maven-metadata.xml")
        return parseMavenVersions(xml).mapNotNull { v ->
            val mc = minecraftVersionOf(v) ?: return@mapNotNull null
            if (mc != minecraftVersion) return@mapNotNull null
            LoaderVersion(v, !v.contains("beta") && !v.contains("alpha"), mc)
        }.sortedWith { a, b -> ComparableVersion.compare(b.version, a.version) }
    }

    fun minecraftVersionOf(loaderVersion: String): String? = when (type) {
        ModLoaderType.FORGE -> loaderVersion.substringBefore('-', "").takeIf { it.isNotEmpty() }
        else -> neoForgeMinecraftVersion(loaderVersion)
    }

    override suspend fun install(minecraftVersion: String, loaderVersion: String, dirs: GameDirectories): LoaderProfile {
        require(loaderVersion.matches(Regex("[A-Za-z0-9._+-]+"))) { "Invalid loader version" }
        val url = "$mavenBase/$loaderVersion/$artifact-$loaderVersion-installer.jar"
        val installer = File(dirs.cache, "installers/$artifact-$loaderVersion-installer.jar")
        if (!installer.isFile) {
            val expectedSha1 = runCatching { meta.getText("$url.sha1").trim().take(40) }.getOrNull()
            val bytes = meta.getBytes(url, 64L * 1024 * 1024)
            if (expectedSha1 != null && expectedSha1.length == 40) {
                val actual = dev.eloa.launcher.core.common.hash.Hashing.digest(bytes, dev.eloa.launcher.core.common.hash.HashAlgorithm.SHA1)
                if (actual != expectedSha1.lowercase()) throw IOException("Installer checksum mismatch")
            }
            FileOps.writeAtomically(installer, bytes)
        }
        return readInstaller(installer, minecraftVersion, dirs)
    }

    /** Extracts version.json and install_profile.json from an installer jar. */
    fun readInstaller(installer: File, minecraftVersion: String, dirs: GameDirectories): LoaderProfile {
        SafeZip.open(installer).use { zip ->
            val profileText = zip.readText("install_profile.json") ?: throw IOException("install_profile.json missing in installer")
            val profileJson = LauncherJson.parseToJsonElement(profileText) as? JsonObject ?: throw IOException("Bad install_profile.json")
            val versionPath = (profileJson["json"] as? JsonPrimitive)?.contentOrNull?.removePrefix("/") ?: "version.json"
            val versionText = zip.readText(versionPath)
                // Legacy (≤1.12) installers embed the version in "versionInfo"
                ?: profileJson["versionInfo"]?.toString()
                ?: throw IOException("version.json missing in installer")
            val version = VersionJson.parse(versionText)
            if (version.inheritsFrom != null && version.inheritsFrom != minecraftVersion) {
                throw IOException("Installer is for Minecraft ${version.inheritsFrom}, not $minecraftVersion")
            }
            val profileLibraries = (profileJson["libraries"] as? JsonArray)?.let {
                LauncherJson.decodeFromJsonElement<List<Library>>(it)
            }.orEmpty()
            val processors = (profileJson["processors"] as? JsonArray)?.count { p ->
                val sides = ((p as? JsonObject)?.get("sides") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                sides == null || "client" in sides
            } ?: 0
            // Embedded maven artifacts (e.g. forge universal jar) are copied into libraries/.
            zip.entries.filter { !it.isDirectory && it.name.startsWith("maven/") }.forEach { e ->
                val target = dirs.library(e.name.removePrefix("maven/"))
                if (!target.isFile) zip.readBytes(e.name, 128 * 1024 * 1024)?.let { FileOps.writeAtomically(target, it) }
            }
            val merged = version.copy(libraries = version.libraries + profileLibraries.filter { pl -> version.libraries.none { it.name == pl.name } })
            FileOps.writeAtomically(dirs.versionJson(version.id), versionText.toByteArray())
            File(dirs.versionDir(version.id), "install_profile.json").writeText(profileText)
            return LoaderProfile(type, version.id, merged, processors)
        }
    }

    companion object {
        fun parseMavenVersions(xml: String): List<String> =
            Regex("<version>([^<]+)</version>").findAll(xml).map { it.groupValues[1].trim() }.toList()

        /**
         * NeoForge versions encode the Minecraft version: 20.4.237 → 1.20.4, 21.0.1 → 1.21,
         * and from the year-based scheme (≥ 25.x) 26.1.0.5 → 26.1.
         * Legacy NeoForge 1.20.1 artifacts use "1.20.1-47.1.x".
         */
        fun neoForgeMinecraftVersion(v: String): String? {
            if (v.startsWith("1.")) return v.substringBefore('-')
            val parts = v.substringBefore('-').split('.')
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: return null
            return if (major >= 25) "$major.$minor" else if (minor == 0) "1.$major" else "1.$major.$minor"
        }
    }
}
