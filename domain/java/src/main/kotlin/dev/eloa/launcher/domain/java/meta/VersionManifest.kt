package dev.eloa.launcher.domain.java.meta

import dev.eloa.launcher.domain.java.net.LauncherJson
import dev.eloa.launcher.domain.java.net.MetaClient
import kotlinx.serialization.Serializable

@Serializable
data class VersionManifest(val latest: Latest, val versions: List<Entry>) {
    @Serializable data class Latest(val release: String, val snapshot: String)

    @Serializable
    data class Entry(
        val id: String,
        val type: String,
        val url: String,
        val time: String = "",
        val releaseTime: String = "",
        val sha1: String? = null,
        val complianceLevel: Int = 0,
    ) {
        val channel: VersionChannel get() = VersionChannel.of(type)
    }

    companion object {
        const val URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        fun parse(text: String): VersionManifest = LauncherJson.decodeFromString(serializer(), text)
        suspend fun fetch(client: MetaClient): VersionManifest = parse(client.getText(URL))
    }
}

enum class VersionChannel {
    RELEASE, SNAPSHOT, OLD_BETA, OLD_ALPHA, MODDED;

    companion object {
        fun of(type: String) = when (type) {
            "release" -> RELEASE
            "snapshot" -> SNAPSHOT
            "old_beta" -> OLD_BETA
            "old_alpha" -> OLD_ALPHA
            else -> MODDED
        }
    }
}
