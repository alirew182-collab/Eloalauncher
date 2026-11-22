package dev.eloa.launcher.domain.java.auth

import dev.eloa.launcher.core.common.hash.HashAlgorithm
import dev.eloa.launcher.core.common.hash.Hashing
import dev.eloa.launcher.domain.java.net.HttpStatusException
import dev.eloa.launcher.domain.java.net.LauncherJson
import dev.eloa.launcher.domain.java.net.SourcePolicy
import dev.eloa.launcher.domain.java.net.await
import dev.eloa.launcher.domain.java.skins.SkinModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

@Serializable
data class ElyTokens(val accessToken: String, val clientToken: String) {
    override fun toString() = "ElyTokens(***)"
}

data class ElySession(val uuid: String, val username: String, val tokens: ElyTokens) {
    override fun toString() = "ElySession(uuid=$uuid, username=$username, tokens=***)"
}

sealed class ElyAuthException(message: String) : IOException(message) {
    class InvalidCredentials : ElyAuthException("Wrong Ely.by login or password.")
    class TwoFactorRequired : ElyAuthException("This Ely.by account uses two-factor authentication. Enter the code from your authenticator app.")
    class NoProfile : ElyAuthException("The Ely.by account has no game profile.")
    class SessionExpired : ElyAuthException("The Ely.by session expired. Sign in again.")
    class Http(code: Int) : ElyAuthException("Ely.by returned HTTP $code")
}

/**
 * Ely.by accounts through its Yggdrasil-compatible auth server. The password is sent once over
 * HTTPS to authserver.ely.by and never stored; only the access and client tokens are kept.
 * Games connect to Ely.by servers through authlib-injector (`-javaagent:authlib-injector.jar=ely.by`).
 */
class ElyByAuth(
    private val client: OkHttpClient,
    private val base: String = "https://authserver.ely.by",
) {
    private val json = "application/json".toMediaType()

    suspend fun authenticate(login: String, password: String, totp: String?, clientToken: String): ElySession {
        val pass = if (totp.isNullOrBlank()) password else "$password:${totp.trim()}"
        val (code, body) = post("/auth/authenticate", buildJsonObject {
            put("username", login.trim()); put("password", pass); put("clientToken", clientToken); put("requestUser", true)
        })
        if (code == 401 || code == 403) {
            val msg = body.s("errorMessage").orEmpty()
            throw if (msg.contains("two factor", true)) ElyAuthException.TwoFactorRequired() else ElyAuthException.InvalidCredentials()
        }
        if (code !in 200..299) throw ElyAuthException.Http(code)
        return session(body, clientToken)
    }

    /** Exchanges the access token for a new one; throws [ElyAuthException.SessionExpired] when it can't. */
    suspend fun refresh(tokens: ElyTokens): ElySession {
        val (code, body) = post("/auth/refresh", buildJsonObject {
            put("accessToken", tokens.accessToken); put("clientToken", tokens.clientToken); put("requestUser", true)
        })
        if (code == 401 || code == 403) throw ElyAuthException.SessionExpired()
        if (code !in 200..299) throw ElyAuthException.Http(code)
        return session(body, tokens.clientToken)
    }

    suspend fun validate(tokens: ElyTokens): Boolean = post("/auth/validate", buildJsonObject { put("accessToken", tokens.accessToken) }).first in 200..299

    suspend fun invalidate(tokens: ElyTokens) {
        runCatching { post("/auth/invalidate", buildJsonObject { put("accessToken", tokens.accessToken); put("clientToken", tokens.clientToken) }) }
    }

    private fun session(body: JsonObject, clientToken: String): ElySession {
        val profile = body["selectedProfile"] as? JsonObject ?: throw ElyAuthException.NoProfile()
        val id = profile.s("id") ?: throw ElyAuthException.NoProfile()
        return ElySession(MicrosoftAuth.formatUuid(id), profile.s("name") ?: throw ElyAuthException.NoProfile(),
            ElyTokens(body.s("accessToken") ?: throw IOException("No access token"), body.s("clientToken") ?: clientToken))
    }

    private suspend fun post(path: String, body: JsonObject): Pair<Int, JsonObject> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(base + path).post(body.toString().toRequestBody(json)).build()
        client.newCall(req).await().use { r ->
            val text = r.body?.string().orEmpty()
            r.code to (runCatching { LauncherJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: JsonObject(emptyMap()))
        }
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** API root passed to authlib-injector. */
        const val AUTHLIB_API = "ely.by"
    }
}

/**
 * Uploads a skin to the signed-in Minecraft Java profile through the official Minecraft Services
 * API (`POST /minecraft/profile/skins`, multipart: variant + PNG file).
 */
class MinecraftSkinApi(
    private val client: OkHttpClient,
    private val url: String = "https://api.minecraftservices.com/minecraft/profile/skins",
) {
    suspend fun upload(minecraftAccessToken: String, png: ByteArray, model: SkinModel) = withContext(Dispatchers.IO) {
        require(png.size <= 24 * 1024) { "Skin PNG is too large" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("variant", model.apiVariant)
            .addFormDataPart("file", "skin.png", png.toRequestBody("image/png".toMediaType()))
            .build()
        val req = Request.Builder().url(url).header("Authorization", "Bearer $minecraftAccessToken").post(body).build()
        client.newCall(req).await().use { r ->
            when {
                r.isSuccessful -> Unit
                r.code == 401 -> throw AuthException.Session()
                else -> throw HttpStatusException(r.code, "Skin upload failed with HTTP ${r.code}")
            }
        }
    }
}

@Serializable
data class AuthlibArtifact(val build_number: Int, val version: String, val download_url: String, val checksums: Map<String, String>)

/**
 * authlib-injector (MIT) is the agent that points the game at a third-party auth server such as
 * Ely.by. It is downloaded from its official artifact API and kept only if the SHA-256 matches.
 */
class AuthlibInjectorDownloader(
    private val client: OkHttpClient,
    private val latestUrl: String = "https://authlib-injector.yushi.moe/artifact/latest.json",
    private val policy: SourcePolicy = SourcePolicy { it.isHttps && it.host == "authlib-injector.yushi.moe" },
) {
    suspend fun ensure(target: File): File = withContext(Dispatchers.IO) {
        val meta = LauncherJson.decodeFromString(AuthlibArtifact.serializer(), get(latestUrl).decodeToString())
        val expected = meta.checksums["sha256"]?.lowercase() ?: throw IOException("authlib-injector metadata has no SHA-256")
        if (target.isFile && Hashing.digest(target, HashAlgorithm.SHA256) == expected) return@withContext target
        val bytes = get(meta.download_url)
        val tmp = File(target.parentFile, target.name + ".part").apply { parentFile?.mkdirs(); writeBytes(bytes) }
        if (Hashing.digest(tmp, HashAlgorithm.SHA256) != expected) { tmp.delete(); throw IOException("authlib-injector checksum mismatch") }
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        target
    }

    private suspend fun get(url: String): ByteArray {
        val u = SourcePolicy.check(policy, url)
        return client.newCall(Request.Builder().url(u).build()).await().use { r ->
            if (!r.isSuccessful) throw HttpStatusException(r.code, "HTTP ${r.code}")
            val src = r.body?.source() ?: throw IOException("Empty body")
            src.request(MAX + 1)
            if (src.buffer.size > MAX) throw IOException("Response too large")
            src.buffer.readByteArray()
        }
    }

    companion object { private const val MAX = 8L * 1024 * 1024 }
}
