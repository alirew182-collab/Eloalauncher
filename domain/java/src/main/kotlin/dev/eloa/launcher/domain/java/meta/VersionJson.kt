package dev.eloa.launcher.domain.java.meta

import dev.eloa.launcher.domain.java.net.LauncherJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Artifact(val path: String? = null, val sha1: String? = null, val size: Long = -1, val url: String = "")

@Serializable
data class LibraryDownloads(val artifact: Artifact? = null, val classifiers: Map<String, Artifact>? = null)

@Serializable
data class ExtractRules(val exclude: List<String> = emptyList())

@Serializable
data class Library(
    val name: String,
    val downloads: LibraryDownloads? = null,
    /** Maven repository base used by Fabric/Quilt/Forge profiles when downloads is absent. */
    val url: String? = null,
    val sha1: String? = null,
    val size: Long = -1,
    val rules: List<Rule>? = null,
    val natives: Map<String, String>? = null,
    val extract: ExtractRules? = null,
)

@Serializable
data class Rule(val action: String, val os: OsRule? = null, val features: Map<String, Boolean>? = null)

@Serializable
data class OsRule(val name: String? = null, val version: String? = null, val arch: String? = null)

@Serializable
data class Arguments(val game: List<JsonElement> = emptyList(), val jvm: List<JsonElement> = emptyList())

@Serializable
data class AssetIndexRef(val id: String, val sha1: String? = null, val size: Long = -1, val totalSize: Long = -1, val url: String)

@Serializable
data class JavaVersionReq(val component: String = "jre-legacy", val majorVersion: Int = 8)

@Serializable
data class Downloads(val client: Artifact? = null, val server: Artifact? = null)

@Serializable
data class LoggingFile(val id: String, val sha1: String? = null, val size: Long = -1, val url: String)

@Serializable
data class LoggingClient(val argument: String, val file: LoggingFile, val type: String = "log4j2-xml")

@Serializable
data class Logging(val client: LoggingClient? = null)

/** Mojang version.json — also the format of Fabric/Quilt/Forge profiles (with inheritsFrom). */
@Serializable
data class VersionJson(
    val id: String,
    val inheritsFrom: String? = null,
    val type: String = "release",
    val mainClass: String? = null,
    val minecraftArguments: String? = null,
    val arguments: Arguments? = null,
    val libraries: List<Library> = emptyList(),
    val downloads: Downloads? = null,
    val assetIndex: AssetIndexRef? = null,
    val assets: String? = null,
    val javaVersion: JavaVersionReq? = null,
    val logging: Logging? = null,
    val releaseTime: String? = null,
    val complianceLevel: Int? = null,
) {
    companion object {
        fun parse(text: String): VersionJson = LauncherJson.decodeFromString(serializer(), text)
    }
}

@Serializable
data class AssetObject(val hash: String, val size: Long)

@Serializable
data class AssetIndex(val objects: Map<String, AssetObject>, val virtual: Boolean = false, val map_to_resources: Boolean = false) {
    companion object {
        fun parse(text: String): AssetIndex = LauncherJson.decodeFromString(serializer(), text)
        fun objectUrl(hash: String) = "https://resources.download.minecraft.net/${hash.take(2)}/$hash"
    }
}
