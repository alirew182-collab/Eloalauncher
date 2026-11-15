package dev.eloa.launcher.domain.java.meta

/**
 * Merges a child profile (Fabric/Quilt/Forge `inheritsFrom`) onto its parent the same way the
 * official launcher does: child libraries take precedence, arguments are appended,
 * scalar fields of the child override the parent.
 */
object VersionResolver {
    fun merge(child: VersionJson, parent: VersionJson): VersionJson {
        val childKeys = child.libraries.mapNotNull { runCatching { MavenCoordinate.parse(it.name).conflictKey }.getOrNull() }.toSet()
        val parentLibs = parent.libraries.filter { lib ->
            val key = runCatching { MavenCoordinate.parse(lib.name).conflictKey }.getOrNull()
            key == null || key !in childKeys
        }
        val args = if (child.arguments != null || parent.arguments != null) {
            Arguments(
                game = parent.arguments?.game.orEmpty() + child.arguments?.game.orEmpty(),
                jvm = parent.arguments?.jvm.orEmpty() + child.arguments?.jvm.orEmpty(),
            )
        } else null
        return parent.copy(
            id = child.id,
            inheritsFrom = parent.inheritsFrom,
            type = child.type,
            mainClass = child.mainClass ?: parent.mainClass,
            minecraftArguments = child.minecraftArguments ?: parent.minecraftArguments,
            arguments = args,
            libraries = child.libraries + parentLibs,
            downloads = child.downloads ?: parent.downloads,
            assetIndex = child.assetIndex ?: parent.assetIndex,
            assets = child.assets ?: parent.assets,
            javaVersion = child.javaVersion ?: parent.javaVersion,
            logging = child.logging ?: parent.logging,
        )
    }

    /** Resolves the full inheritance chain using [load] (max depth 5 to stop cycles). */
    suspend fun resolve(version: VersionJson, load: suspend (String) -> VersionJson): VersionJson {
        var current = version
        var depth = 0
        while (current.inheritsFrom != null) {
            if (++depth > 5) throw IllegalStateException("Profile inheritance too deep or cyclic for ${version.id}")
            current = merge(current, load(current.inheritsFrom!!))
        }
        return current
    }
}
