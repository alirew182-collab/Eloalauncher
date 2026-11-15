package dev.eloa.launcher.domain.java.mods

import dev.eloa.launcher.domain.java.loader.ModLoaderType

enum class DependencyKind { REQUIRED, OPTIONAL, INCOMPATIBLE }

/** [range] is in the loader's native syntax; [rangeSyntax] tells how to interpret it. */
data class ModDependency(val modId: String, val range: List<String>, val kind: DependencyKind, val rangeSyntax: RangeSyntax)

enum class RangeSyntax { FABRIC, MAVEN }

data class ModMetadata(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val authors: List<String>,
    val format: ModLoaderType,
    val dependencies: List<ModDependency>,
    val provides: List<String>,
    /** "client", "server" or "*". */
    val environment: String,
    val nestedJars: List<String>,
    val license: String?,
)

/** A mod jar on disk. */
data class ModFile(
    val fileName: String,
    val sizeBytes: Long,
    val enabled: Boolean,
    val metadata: List<ModMetadata>,
    val error: String?,
    val lastModified: Long,
) {
    val primary: ModMetadata? get() = metadata.firstOrNull()
    val displayName: String get() = primary?.name ?: fileName.removeSuffix(".disabled").removeSuffix(".jar")
}
