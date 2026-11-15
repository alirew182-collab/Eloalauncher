package dev.eloa.launcher.domain.bedrock.addon

import dev.eloa.launcher.core.common.version.ComparableVersion
import dev.eloa.launcher.domain.bedrock.apk.Finding
import dev.eloa.launcher.domain.bedrock.apk.Severity

/** A pack already present in the user's library. */
data class LibraryPack(val uuid: String, val version: String, val name: String, val enabled: Boolean)

/**
 * Cross-checks new packs against the library: duplicates, updates/downgrades, missing
 * dependencies and engine-version compatibility.
 */
object AddonLibraryChecker {
    /** Script API modules provided by the game itself (dependency by module_name). */
    private val BUILTIN_MODULES = setOf(
        "@minecraft/server", "@minecraft/server-ui", "@minecraft/server-gametest", "@minecraft/server-net",
        "@minecraft/server-admin", "@minecraft/server-editor", "@minecraft/common", "@minecraft/debug-utilities",
        "mojang-minecraft", "mojang-gametest", "mojang-minecraft-ui", "mojang-net", "mojang-minecraft-server-admin",
    )

    fun check(newPacks: List<PackManifest>, library: List<LibraryPack>, gameVersion: String?): List<Finding> {
        val out = ArrayList<Finding>()
        val byUuid = library.associateBy { it.uuid.lowercase() }
        val available = byUuid.keys + newPacks.map { it.uuid } + newPacks.flatMap { it.moduleUuids }
        for (p in newPacks) {
            byUuid[p.uuid]?.let { existing ->
                val c = ComparableVersion.compare(p.version, existing.version)
                out += when {
                    c == 0 -> Finding(Severity.WARNING, "DUPLICATE", "${p.name} ${p.version} is already in the library.")
                    c > 0 -> Finding(Severity.INFO, "UPDATE", "${p.name} will be updated from ${existing.version} to ${p.version}.")
                    else -> Finding(Severity.WARNING, "DOWNGRADE", "${p.name}: library has newer ${existing.version} than ${p.version}.")
                }
            }
            for (d in p.dependencies) {
                when {
                    d.moduleName != null -> if (d.moduleName !in BUILTIN_MODULES) {
                        out += Finding(Severity.WARNING, "UNKNOWN_MODULE", "${p.name} requires script module ${d.moduleName} which is not a known game module.")
                    }
                    d.uuid != null && d.uuid.lowercase() !in available ->
                        out += Finding(Severity.ERROR, "MISSING_DEP", "${p.name} depends on pack ${d.uuid} (${d.version}) which is not in the library.")
                }
            }
            if (gameVersion != null && p.minEngineVersion != null && ComparableVersion.compare(p.minEngineVersion, gameVersion) > 0) {
                out += Finding(Severity.ERROR, "ENGINE", "${p.name} requires Minecraft ${p.minEngineVersion}+, selected version is $gameVersion.")
            }
        }
        return out
    }
}
