package dev.eloa.launcher.domain.java.meta

/**
 * Evaluates Mojang library/argument rules. Android runs a Linux kernel and Java ports for
 * Android identify as Linux, so OS name is "linux".
 */
data class RuleEnvironment(
    val osName: String = "linux",
    val osArch: String = "arm64",
    val features: Map<String, Boolean> = emptyMap(),
) {
    fun allows(rules: List<Rule>?): Boolean {
        if (rules.isNullOrEmpty()) return true
        var allowed = false
        for (rule in rules) {
            if (matches(rule)) allowed = rule.action == "allow"
        }
        return allowed
    }

    private fun matches(rule: Rule): Boolean {
        rule.os?.let { os ->
            if (os.name != null && os.name != osName) return false
            if (os.arch != null && !archMatches(os.arch)) return false
            if (os.version != null && osName != "linux") return false
        }
        rule.features?.let { req ->
            if (req.any { (k, v) -> (features[k] ?: false) != v }) return false
        }
        return true
    }

    private fun archMatches(arch: String) = when (arch) {
        "x86" -> osArch == "x86"
        "x86_64", "amd64" -> osArch == "x86_64"
        "arm64", "aarch64" -> osArch == "arm64"
        "arm", "arm32" -> osArch == "arm"
        else -> arch == osArch
    }

    companion object {
        fun forAbi(abi: String, features: Map<String, Boolean> = emptyMap()) = RuleEnvironment(
            osArch = when (abi) { "arm64-v8a" -> "arm64"; "armeabi-v7a", "armeabi" -> "arm"; "x86_64" -> "x86_64"; "x86" -> "x86"; else -> abi },
            features = features,
        )
    }
}

/** Maven coordinate helper: group:artifact:version[:classifier][@ext]. */
data class MavenCoordinate(val group: String, val artifact: String, val version: String, val classifier: String?, val extension: String) {
    val path: String
        get() = "${group.replace('.', '/')}/$artifact/$version/$artifact-$version${classifier?.let { "-$it" } ?: ""}.$extension"

    /** Key used to de-duplicate libraries (same artifact, different versions). */
    val conflictKey: String get() = "$group:$artifact${classifier?.let { ":$it" } ?: ""}"

    companion object {
        fun parse(coord: String): MavenCoordinate {
            val ext = coord.substringAfter('@', "jar")
            val parts = coord.substringBefore('@').split(':')
            require(parts.size in 3..4) { "Invalid maven coordinate: $coord" }
            parts.forEach { p -> require(p.isNotEmpty() && !p.contains("..") && !p.contains('/') && !p.contains('\\')) { "Unsafe coordinate: $coord" } }
            return MavenCoordinate(parts[0], parts[1], parts[2], parts.getOrNull(3), ext)
        }
    }
}
