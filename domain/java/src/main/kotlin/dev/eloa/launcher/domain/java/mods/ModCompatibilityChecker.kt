package dev.eloa.launcher.domain.java.mods

import dev.eloa.launcher.core.common.version.FabricVersionPredicate
import dev.eloa.launcher.core.common.version.MavenVersionRange
import dev.eloa.launcher.core.common.version.VersionPredicate
import dev.eloa.launcher.core.common.version.VersionRangeSyntaxException
import dev.eloa.launcher.domain.java.loader.ModLoaderType

enum class IssueSeverity { INFO, WARNING, ERROR }

data class ModIssue(val severity: IssueSeverity, val code: String, val modFile: String?, val message: String, val suggestion: String? = null)

data class ModEnvironment(
    val loader: ModLoaderType,
    val minecraftVersion: String,
    val loaderVersion: String?,
    val javaMajor: Int? = null,
)

/**
 * Validates a set of enabled mods against the profile: loader format, Minecraft and loader
 * versions, required/optional/incompatible dependencies, duplicates and missing metadata.
 * The presence of a jar is never taken as proof of compatibility.
 */
object ModCompatibilityChecker {

    private fun builtins(env: ModEnvironment): Map<String, String?> {
        val m = HashMap<String, String?>()
        m["minecraft"] = env.minecraftVersion
        m["java"] = env.javaMajor?.toString()
        when (env.loader) {
            ModLoaderType.FABRIC -> { m["fabricloader"] = env.loaderVersion; m["fabric-loader"] = env.loaderVersion }
            ModLoaderType.QUILT -> { m["quilt_loader"] = env.loaderVersion; m["fabricloader"] = null }
            ModLoaderType.FORGE -> { m["forge"] = env.loaderVersion; m["javafml"] = null; m["fml"] = null; m["lowcodefml"] = null }
            ModLoaderType.NEOFORGE -> { m["neoforge"] = env.loaderVersion; m["forge"] = null; m["javafml"] = null; m["fml"] = null; m["lowcodefml"] = null }
            ModLoaderType.VANILLA -> Unit
        }
        return m
    }

    /** Picks the metadata block relevant for [loader] from a (possibly multi-loader) jar. */
    fun relevantMetadata(file: ModFile, loader: ModLoaderType): ModMetadata? {
        val accepted = loader.acceptedModFormats
        return file.metadata.firstOrNull { it.format == loader } ?: file.metadata.firstOrNull { it.format in accepted }
    }

    fun check(mods: List<ModFile>, env: ModEnvironment): List<ModIssue> {
        val issues = ArrayList<ModIssue>()
        val enabled = mods.filter { it.enabled }
        if (env.loader == ModLoaderType.VANILLA) {
            if (enabled.isNotEmpty()) issues += ModIssue(IssueSeverity.WARNING, "VANILLA_MODS", null,
                "This profile has no mod loader; ${enabled.size} mod(s) will not be loaded.", "Create a profile with Fabric, Quilt, Forge or NeoForge.")
            return issues
        }
        val builtins = builtins(env)
        val active = LinkedHashMap<ModFile, ModMetadata>()
        for (f in enabled) {
            when {
                f.error != null -> issues += ModIssue(IssueSeverity.ERROR, "UNREADABLE", f.fileName, "Cannot read mod: ${f.error}", "Re-download the mod; the file may be damaged.")
                f.metadata.isEmpty() -> issues += ModIssue(IssueSeverity.WARNING, "NO_METADATA", f.fileName,
                    "No mod metadata found; the loader will probably ignore this jar.", "Make sure this is a mod for ${env.loader.displayName}.")
                else -> {
                    val meta = relevantMetadata(f, env.loader)
                    if (meta == null) {
                        issues += ModIssue(IssueSeverity.ERROR, "WRONG_LOADER", f.fileName,
                            "${f.displayName} is a ${f.metadata.joinToString("/") { it.format.displayName }} mod and cannot run on ${env.loader.displayName}.",
                            "Download the ${env.loader.displayName} build of this mod or disable it.")
                    } else {
                        active[f] = meta
                        if (meta.environment == "server") issues += ModIssue(IssueSeverity.WARNING, "SERVER_ONLY", f.fileName, "${meta.name} is a server-side only mod.", "Disable it for the client.")
                    }
                }
            }
        }
        // provided ids → (file, version)
        val provided = HashMap<String, MutableList<Pair<ModFile, String>>>()
        for ((f, m) in active) {
            provided.getOrPut(m.id) { ArrayList() } += f to m.version
            m.provides.forEach { provided.getOrPut(it) { ArrayList() } += f to m.version }
        }
        for ((id, list) in provided) {
            val files = list.map { it.first }.distinct()
            if (files.size > 1 && active[files[0]]?.id == id) {
                issues += ModIssue(IssueSeverity.ERROR, "DUPLICATE", files.joinToString { it.fileName },
                    "Mod '$id' is installed ${files.size} times (${files.joinToString { it.fileName }}).", "Keep only one version.")
            }
        }
        for ((f, m) in active) {
            for (d in m.dependencies) {
                val predicate = try { predicateOf(d) } catch (e: VersionRangeSyntaxException) {
                    issues += ModIssue(IssueSeverity.WARNING, "BAD_RANGE", f.fileName, "${m.name}: cannot parse version range for ${d.modId}: ${d.range.joinToString()}")
                    continue
                }
                val isBuiltin = builtins.containsKey(d.modId)
                val versions: List<String?> = if (isBuiltin) listOf(builtins[d.modId]) else provided[d.modId]?.map { it.second }.orEmpty()
                val present = isBuiltin || versions.isNotEmpty()
                val satisfied = present && versions.any { v -> v == null || predicate.matches(v) }
                when (d.kind) {
                    DependencyKind.REQUIRED -> when {
                        !present -> issues += ModIssue(IssueSeverity.ERROR, "MISSING_DEP", f.fileName,
                            "${m.name} requires '${d.modId}' ${d.range.joinToString(" or ")}, which is not installed.", "Install '${d.modId}' for ${env.loader.displayName} ${env.minecraftVersion}.")
                        !satisfied -> issues += ModIssue(IssueSeverity.ERROR,
                            if (d.modId == "minecraft") "MC_VERSION" else if (isBuiltin) "LOADER_VERSION" else "DEP_VERSION", f.fileName,
                            "${m.name} requires ${d.modId} ${d.range.joinToString(" or ")}, found ${versions.filterNotNull().joinToString().ifEmpty { "unknown" }}.",
                            if (d.modId == "minecraft") "Use a build of ${m.name} made for Minecraft ${env.minecraftVersion}." else "Update or replace '${d.modId}'.")
                    }
                    DependencyKind.OPTIONAL -> if (present && !satisfied && !isBuiltin) issues += ModIssue(IssueSeverity.WARNING, "OPTIONAL_VERSION", f.fileName,
                        "${m.name} works best with ${d.modId} ${d.range.joinToString()}, found ${versions.filterNotNull().joinToString()}.")
                    DependencyKind.INCOMPATIBLE -> if (present && satisfied && !isBuiltin) issues += ModIssue(IssueSeverity.ERROR, "INCOMPATIBLE", f.fileName,
                        "${m.name} is incompatible with ${d.modId} ${d.range.joinToString()}.", "Disable one of them.")
                        else if (isBuiltin && satisfied && builtins[d.modId] != null) issues += ModIssue(IssueSeverity.ERROR, "INCOMPATIBLE", f.fileName,
                        "${m.name} does not support ${d.modId} ${builtins[d.modId]}.")
                }
            }
        }
        return issues.sortedBy { -it.severity.ordinal }
    }

    private fun predicateOf(d: ModDependency): VersionPredicate = when (d.rangeSyntax) {
        RangeSyntax.FABRIC -> FabricVersionPredicate.parseAny(d.range)
        RangeSyntax.MAVEN -> d.range.map(MavenVersionRange::parse).let { ps -> VersionPredicate { v -> ps.any { it.matches(v) } } }
    }
}
