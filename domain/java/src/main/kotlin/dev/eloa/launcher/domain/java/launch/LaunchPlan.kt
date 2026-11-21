package dev.eloa.launcher.domain.java.launch

import dev.eloa.launcher.core.common.logging.SecretRedactor
import dev.eloa.launcher.domain.java.install.GameDirectories
import dev.eloa.launcher.domain.java.install.LibraryPlanner
import dev.eloa.launcher.domain.java.meta.RuleEnvironment
import dev.eloa.launcher.domain.java.meta.VersionJson
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/** Identity used for launching. Comes only from a verified Microsoft/Minecraft session. */
data class LaunchIdentity(
    val playerName: String,
    val uuid: String,
    val accessToken: String,
    val xuid: String?,
    val clientId: String?,
    val userType: String = "msa",
) {
    override fun toString() = "LaunchIdentity(playerName=$playerName, uuid=$uuid, accessToken=***)"
}

data class LaunchSettings(
    val minMemoryMb: Int,
    val maxMemoryMb: Int,
    val width: Int? = null,
    val height: Int? = null,
    val extraJvmArgs: List<String> = emptyList(),
    val extraGameArgs: List<String> = emptyList(),
    val launcherName: String = "EloaLauncher",
    val launcherVersion: String = "1.0",
    /** Local world folder name to open directly (Quick Play, Minecraft 1.20+). */
    val quickPlaySingleplayer: String? = null,
    /** Environment for the runtime backend (renderer selection, resolution scale…). */
    val environment: Map<String, String> = emptyMap(),
)

data class LaunchPlan(
    val versionId: String,
    val mainClass: String,
    val classpath: List<File>,
    val jvmArgs: List<String>,
    val gameArgs: List<String>,
    val gameDir: File,
    val nativesDir: File,
    val requiredJavaMajor: Int,
    val environment: Map<String, String> = emptyMap(),
) {
    /** Command line with secrets masked — safe for logs and UI. */
    fun redactedCommandLine(): String = SecretRedactor.redact(
        (jvmArgs + mainClass + gameArgs).joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it },
    ).replace(Regex("(--accessToken\\s+)\\S+"), "$1***")
}

class LaunchPlanException(message: String) : IllegalStateException(message)

/** Builds the exact JVM/game argument lists the way the official launcher does. */
object LaunchPlanBuilder {
    private val UNSAFE_JVM_ARGS = listOf("-javaagent", "-agentlib", "-agentpath", "-Xbootclasspath", "-XX:OnError", "-XX:OnOutOfMemoryError")

    /** Rejects user-supplied JVM arguments that would load arbitrary native/agent code. */
    fun validateExtraJvmArgs(args: List<String>): List<String> = args.filter { a -> UNSAFE_JVM_ARGS.any { a.startsWith(it) } }

    fun build(
        version: VersionJson,
        dirs: GameDirectories,
        gameDir: File,
        identity: LaunchIdentity,
        settings: LaunchSettings,
        env: RuleEnvironment,
        clientJarId: String = version.id,
    ): LaunchPlan {
        val mainClass = version.mainClass ?: throw LaunchPlanException("Version ${version.id} has no main class")
        if (settings.maxMemoryMb < 256) throw LaunchPlanException("At least 256 MB must be allocated")
        if (settings.minMemoryMb > settings.maxMemoryMb) throw LaunchPlanException("Minimum memory exceeds maximum memory")
        validateExtraJvmArgs(settings.extraJvmArgs).firstOrNull()?.let { throw LaunchPlanException("JVM argument not allowed: $it") }

        val classpath = LibraryPlanner.classpath(version, dirs, env).toMutableList()
        val clientJar = dirs.clientJar(clientJarId)
        if (version.downloads?.client != null || clientJar.isFile) classpath += clientJar
        val natives = dirs.nativesDir(clientJarId)
        val assetsId = version.assetIndex?.id ?: version.assets ?: "legacy"
        val legacyAssets = assetsId == "legacy" || assetsId == "pre-1.6"
        val features = env.features + mapOf(
            "has_custom_resolution" to (settings.width != null && settings.height != null),
            "is_quick_play_singleplayer" to (settings.quickPlaySingleplayer != null),
        )
        val ruleEnv = env.copy(features = features)

        val vars = mapOf(
            "auth_player_name" to identity.playerName,
            "version_name" to version.id,
            "game_directory" to gameDir.absolutePath,
            "assets_root" to dirs.assets.absolutePath,
            "game_assets" to (if (legacyAssets) dirs.legacyAssets(assetsId) else dirs.assets).absolutePath,
            "assets_index_name" to assetsId,
            "auth_uuid" to identity.uuid.replace("-", ""),
            "auth_access_token" to identity.accessToken,
            "auth_session" to "token:${identity.accessToken}:${identity.uuid.replace("-", "")}",
            "auth_xuid" to (identity.xuid ?: "0"),
            "clientid" to (identity.clientId ?: ""),
            "user_type" to identity.userType,
            "user_properties" to "{}",
            "version_type" to version.type,
            "natives_directory" to natives.absolutePath,
            "launcher_name" to settings.launcherName,
            "launcher_version" to settings.launcherVersion,
            "classpath" to classpath.joinToString(File.pathSeparator) { it.absolutePath },
            "classpath_separator" to File.pathSeparator,
            "library_directory" to dirs.libraries.absolutePath,
            "resolution_width" to (settings.width?.toString() ?: "854"),
            "resolution_height" to (settings.height?.toString() ?: "480"),
            "quickPlayPath" to "", "quickPlaySingleplayer" to (settings.quickPlaySingleplayer ?: ""), "quickPlayMultiplayer" to "", "quickPlayRealms" to "",
        )

        val jvm = ArrayList<String>()
        jvm += "-Xms${settings.minMemoryMb}M"
        jvm += "-Xmx${settings.maxMemoryMb}M"
        if (version.arguments?.jvm.isNullOrEmpty()) {
            jvm += "-Djava.library.path=${natives.absolutePath}"
            jvm += "-cp"; jvm += vars.getValue("classpath")
        } else {
            jvm += expand(version.arguments!!.jvm, ruleEnv, vars)
        }
        version.logging?.client?.let { lc ->
            jvm += lc.argument.replace("\${path}", File(dirs.assets, "log_configs/${lc.file.id}").absolutePath)
        }
        jvm += settings.extraJvmArgs

        val game = ArrayList<String>()
        if (version.arguments?.game?.isNotEmpty() == true) {
            game += expand(version.arguments.game, ruleEnv, vars)
        } else if (version.minecraftArguments != null) {
            game += version.minecraftArguments.split(' ').filter { it.isNotEmpty() }.map { substitute(it, vars) }
        }
        if (settings.width != null && settings.height != null && game.none { it == "--width" }) {
            game += listOf("--width", settings.width.toString(), "--height", settings.height.toString())
        }
        // Drop quick play / empty-valued options that newer manifests always include.
        game += settings.extraGameArgs
        return LaunchPlan(version.id, mainClass, classpath, jvm, removeEmptyOptions(game), gameDir, natives, version.javaVersion?.majorVersion ?: 8, settings.environment)
    }

    private fun removeEmptyOptions(args: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--") && i + 1 < args.size && args[i + 1].isEmpty()) { i += 2; continue }
            if (a.isNotEmpty()) out += a
            i++
        }
        return out
    }

    private fun expand(args: List<JsonElement>, env: RuleEnvironment, vars: Map<String, String>): List<String> {
        val out = ArrayList<String>()
        for (a in args) {
            when (a) {
                is JsonPrimitive -> a.contentOrNull?.let { out += substitute(it, vars) }
                is JsonObject -> {
                    val rules = (a["rules"] as? JsonArray)?.let { dev.eloa.launcher.domain.java.net.LauncherJson.decodeFromJsonElement<List<dev.eloa.launcher.domain.java.meta.Rule>>(it) }
                    if (!env.allows(rules)) continue
                    when (val v = a["value"]) {
                        is JsonPrimitive -> v.contentOrNull?.let { out += substitute(it, vars) }
                        is JsonArray -> v.forEach { (it as? JsonPrimitive)?.contentOrNull?.let { s -> out += substitute(s, vars) } }
                        else -> Unit
                    }
                }
                else -> Unit
            }
        }
        return out
    }

    private val VAR = Regex("\\$\\{([a-zA-Z_]+)}")
    private fun substitute(s: String, vars: Map<String, String>) = VAR.replace(s) { m -> vars[m.groupValues[1]] ?: m.value }
}
