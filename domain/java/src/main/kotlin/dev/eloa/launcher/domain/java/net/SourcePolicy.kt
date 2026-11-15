package dev.eloa.launcher.domain.java.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Only official/known-good sources may be used for game files. Everything must be HTTPS.
 * Tests inject a permissive policy for MockWebServer.
 */
fun interface SourcePolicy {
    fun isAllowed(url: HttpUrl): Boolean

    companion object {
        val TRUSTED_HOSTS = setOf(
            // Mojang / Microsoft
            "piston-meta.mojang.com", "piston-data.mojang.com", "launchermeta.mojang.com", "launcher.mojang.com",
            "libraries.minecraft.net", "resources.download.minecraft.net",
            // Mod loaders (official metadata and maven repositories)
            "meta.fabricmc.net", "maven.fabricmc.net",
            "meta.quiltmc.org", "maven.quiltmc.org",
            "maven.minecraftforge.net", "files.minecraftforge.net",
            "maven.neoforged.net",
        )

        val OFFICIAL = SourcePolicy { url -> url.isHttps && url.host in TRUSTED_HOSTS }

        fun check(policy: SourcePolicy, url: String): HttpUrl {
            val parsed = url.toHttpUrlOrNull() ?: throw UntrustedSourceException("Malformed URL")
            if (!policy.isAllowed(parsed)) throw UntrustedSourceException("Source not trusted: ${parsed.host}")
            return parsed
        }
    }
}

class UntrustedSourceException(message: String) : java.io.IOException(message)
