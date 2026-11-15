package dev.eloa.launcher.domain.bedrock.addon

import dev.eloa.launcher.domain.bedrock.apk.Finding

enum class PackType { RESOURCE, BEHAVIOR, SKIN, WORLD_TEMPLATE, UNKNOWN }

enum class AddonKind { MCPACK, MCADDON, MCWORLD, MCSTRUCTURE, MCTEMPLATE, ZIP }

data class PackDependency(val uuid: String?, val moduleName: String?, val version: String)

data class PackManifest(
    val formatVersion: Int,
    val name: String,
    val description: String,
    val uuid: String,
    val version: String,
    val minEngineVersion: String?,
    val type: PackType,
    val moduleTypes: List<String>,
    val moduleUuids: List<String>,
    val dependencies: List<PackDependency>,
    val authors: List<String>,
)

/** A pack located inside an archive at [rootPath] ("" = archive root). */
data class PackEntry(val rootPath: String, val manifest: PackManifest, val hasIcon: Boolean)

data class WorldInfo(
    val levelName: String,
    val gameType: Int?,
    val lastPlayed: Long?,
    val lastOpenedWithVersion: String?,
    val embeddedPacks: Int,
)

data class StructureInfo(val sizeX: Int, val sizeY: Int, val sizeZ: Int, val formatVersion: Int?)

data class AddonAnalysis(
    val fileName: String,
    val kind: AddonKind,
    val packs: List<PackEntry>,
    val world: WorldInfo?,
    val structure: StructureInfo?,
    val findings: List<Finding>,
) {
    val isValid: Boolean get() = findings.none { it.severity == dev.eloa.launcher.domain.bedrock.apk.Severity.ERROR }
    val displayName: String
        get() = world?.levelName ?: packs.firstOrNull()?.manifest?.name ?: fileName.substringBeforeLast('.')
}
