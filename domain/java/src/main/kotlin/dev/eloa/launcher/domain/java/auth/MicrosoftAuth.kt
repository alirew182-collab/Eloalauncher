package dev.eloa.launcher.domain.java.auth

import dev.eloa.launcher.domain.java.net.LauncherJson
import dev.eloa.launcher.domain.java.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Endpoints are injectable for tests; production values are the official Microsoft/Xbox/Mojang hosts. */
data class AuthEndpoints(
    val deviceCode: String = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
    val token: String = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
    val xboxUser: String = "https://user.auth.xboxlive.com/user/authenticate",
    val xsts: String = "https://xsts.auth.xboxlive.com/xsts/authorize",
    val mcLogin: String = "https://api.minecraftservices.com/authentication/login_with_xbox",
    val entitlements: String = "https://api.minecraftservices.com/entitlements/mcstore",
    val profile: String = "https://api.minecraftservices.com/minecraft/profile",
    val authorize: String = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize",
)

data class DeviceCode(val userCode: String, val deviceCode: String, val verificationUri: String, val expiresInSec: Long, val intervalSec: Long, val message: String?) {
    override fun toString() = "DeviceCode(userCode=$userCode, verificationUri=$verificationUri, deviceCode=***)"
}

data class MsTokens(val accessToken: String, val refreshToken: String, val expiresAtMs: Long) {
    override fun toString() = "MsTokens(***)"
}

@Serializable
data class StoredTokens(val msRefreshToken: String, val minecraftAccessToken: String, val minecraftExpiresAtMs: Long, val xuid: String? = null) {
    override fun toString() = "StoredTokens(***)"
}

data class MinecraftSession(
    val uuid: String,
    val username: String,
    val skinUrl: String?,
    val minecraftAccessToken: String,
    val minecraftExpiresAtMs: Long,
    val msRefreshToken: String,
    val xuid: String?,
) {
    override fun toString() = "MinecraftSession(uuid=$uuid, username=$username, tokens=***)"
}

sealed class AuthException(message: String) : IOException(message) {
    class Expired : AuthException("The sign-in code expired. Start again.")
    class Declined : AuthException("Sign-in was cancelled in the browser.")
    class NoXboxAccount : AuthException("This Microsoft account has no Xbox profile. Create one at xbox.com, then sign in again.")
    class ChildAccount : AuthException("This is a child account. An adult must add it to a Microsoft Family to allow sign-in.")
    class RegionBlocked : AuthException("Xbox Live is not available in this account's country/region.")
    class NotOwned : AuthException("This account does not own Minecraft: Java Edition.")
    class NoProfile : AuthException("The account owns the game but has no Minecraft profile yet. Launch the official launcher once to create it.")
    class InvalidClient(detail: String) : AuthException("The configured Azure client ID was rejected: $detail. Use a client ID approved for the Minecraft API.")
    class Session : AuthException("The saved session is no longer valid. Please sign in again.")
    class StateMismatch : AuthException("The sign-in response did not match this request (state mismatch). Start again.")
    class Http(code: Int, where: String) : AuthException("$where failed with HTTP $code")
}

/**
 * Official Microsoft account sign-in for Minecraft using the OAuth 2.0 device code flow,
 * followed by Xbox Live, XSTS and Minecraft Services. Ownership is verified via entitlements.
 * No tokens are ever logged; all types mask secrets in toString().
 */
class MicrosoftAuth(
    private val client: OkHttpClient,
    private val clientId: String,
    private val endpoints: AuthEndpoints = AuthEndpoints(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val jsonType = "application/json".toMediaType()

    init { require(clientId.isNotBlank()) { "Azure client ID is not configured" } }

    suspend fun requestDeviceCode(): DeviceCode {
        val o = postForm(endpoints.deviceCode, mapOf("client_id" to clientId, "scope" to "XboxLive.signin offline_access"), "Device code request")
        return DeviceCode(
            userCode = o.s("user_code") ?: throw IOException("Malformed device code response"),
            deviceCode = o.s("device_code") ?: throw IOException("Malformed device code response"),
            verificationUri = o.s("verification_uri") ?: "https://www.microsoft.com/link",
            expiresInSec = o.l("expires_in") ?: 900,
            intervalSec = (o.l("interval") ?: 5).coerceIn(1, 30),
            message = o.s("message"),
        )
    }

    /** Polls until the user completes sign-in in the browser. Cancellable. */
    suspend fun pollForTokens(code: DeviceCode): MsTokens {
        var interval = code.intervalSec
        val deadline = now() + code.expiresInSec * 1000
        while (now() < deadline) {
            delay(interval * 1000)
            val (status, body) = rawPostForm(endpoints.token, mapOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "client_id" to clientId, "device_code" to code.deviceCode,
            ))
            if (status in 200..299) return tokensFrom(body)
            when (body.s("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "authorization_declined" -> throw AuthException.Declined()
                "expired_token", "bad_verification_code" -> throw AuthException.Expired()
                "invalid_client", "unauthorized_client" -> throw AuthException.InvalidClient(body.s("error_description")?.lineSequence()?.firstOrNull() ?: "invalid client")
                else -> throw AuthException.Http(status, "Token polling")
            }
        }
        throw AuthException.Expired()
    }

    suspend fun refresh(refreshToken: String): MsTokens {
        val (status, body) = rawPostForm(endpoints.token, mapOf(
            "grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refreshToken, "scope" to "XboxLive.signin offline_access",
        ))
        if (status in 200..299) return tokensFrom(body)
        if (body.s("error") == "invalid_grant") throw AuthException.Session()
        if (body.s("error") == "invalid_client" || body.s("error") == "unauthorized_client") throw AuthException.InvalidClient(body.s("error") ?: "")
        throw AuthException.Http(status, "Token refresh")
    }

    /**
     * Authorization-code flow with PKCE (RFC 7636) for the system browser. The app opens
     * [BrowserSignIn.url]; Microsoft redirects to [redirectUri] with `code` and `state`, which are
     * passed to [exchangeCode]. No password ever passes through the launcher.
     */
    fun beginBrowserSignIn(redirectUri: String, random: java.security.SecureRandom = java.security.SecureRandom()): BrowserSignIn {
        val verifier = Pkce.verifier(random)
        val state = Pkce.verifier(random).take(32)
        val url = endpoints.authorize.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", "XboxLive.signin offline_access")
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", Pkce.challenge(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("prompt", "select_account")
            .build()
        return BrowserSignIn(url.toString(), state, verifier, redirectUri)
    }

    /** Validates the redirect (state, errors) and redeems the code with the PKCE verifier. */
    suspend fun exchangeCode(pending: BrowserSignIn, redirect: String): MsTokens {
        val url = redirect.toHttpUrlOrNull() ?: customSchemeAsHttp(redirect) ?: throw AuthException.Declined()
        if (url.queryParameter("state") != pending.state) throw AuthException.StateMismatch()
        url.queryParameter("error")?.let { err ->
            if (err == "access_denied") throw AuthException.Declined()
            throw AuthException.InvalidClient(url.queryParameter("error_description")?.lineSequence()?.firstOrNull() ?: err)
        }
        val code = url.queryParameter("code") ?: throw AuthException.Declined()
        val (status, body) = rawPostForm(endpoints.token, mapOf(
            "grant_type" to "authorization_code", "client_id" to clientId, "code" to code,
            "redirect_uri" to pending.redirectUri, "code_verifier" to pending.verifier, "scope" to "XboxLive.signin offline_access",
        ))
        if (status in 200..299) return tokensFrom(body)
        when (body.s("error")) {
            "invalid_grant" -> throw AuthException.Expired()
            "invalid_client", "unauthorized_client", "invalid_request" -> throw AuthException.InvalidClient(body.s("error_description")?.lineSequence()?.firstOrNull() ?: body.s("error").orEmpty())
            else -> throw AuthException.Http(status, "Code exchange")
        }
    }

    private fun customSchemeAsHttp(uri: String): okhttp3.HttpUrl? {
        val q = uri.substringAfter('?', "")
        if (q.isEmpty()) return null
        return "https://redirect.invalid/?$q".toHttpUrlOrNull()
    }

    /** Microsoft token → Minecraft session with ownership check. */
    suspend fun loginMinecraft(ms: MsTokens): MinecraftSession {
        val xbl = postJson(endpoints.xboxUser, buildJsonObject {
            put("Properties", buildJsonObject { put("AuthMethod", "RPS"); put("SiteName", "user.auth.xboxlive.com"); put("RpsTicket", "d=${ms.accessToken}") })
            put("RelyingParty", "http://auth.xboxlive.com"); put("TokenType", "JWT")
        }, "Xbox Live authentication")
        val xblToken = xbl.s("Token") ?: throw IOException("Xbox Live returned no token")
        val (xstsStatus, xsts) = rawPostJson(endpoints.xsts, buildJsonObject {
            put("Properties", buildJsonObject { put("SandboxId", "RETAIL"); put("UserTokens", buildJsonArray { add(JsonPrimitive(xblToken)) }) })
            put("RelyingParty", "rp://api.minecraftservices.com/"); put("TokenType", "JWT")
        })
        if (xstsStatus == 401) throw when (xsts.l("XErr")) {
            2148916233L -> AuthException.NoXboxAccount()
            2148916235L -> AuthException.RegionBlocked()
            2148916236L, 2148916237L, 2148916238L -> AuthException.ChildAccount()
            else -> AuthException.Http(401, "XSTS authorization")
        }
        if (xstsStatus !in 200..299) throw AuthException.Http(xstsStatus, "XSTS authorization")
        val xstsToken = xsts.s("Token") ?: throw IOException("XSTS returned no token")
        val claims = ((xsts["DisplayClaims"] as? JsonObject)?.get("xui") as? JsonArray)?.firstOrNull() as? JsonObject
        val uhs = claims?.s("uhs") ?: throw IOException("XSTS returned no user hash")
        val mc = postJson(endpoints.mcLogin, buildJsonObject { put("identityToken", "XBL3.0 x=$uhs;$xstsToken") }, "Minecraft login")
        val mcToken = mc.s("access_token") ?: throw IOException("Minecraft login returned no token")
        val mcExpires = now() + (mc.l("expires_in") ?: 86_400) * 1000

        val ent = getJson(endpoints.entitlements, mcToken, "Entitlement check")
        val items = (ent["items"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.s("name") }.orEmpty()
        if (items.none { it == "game_minecraft" || it == "product_minecraft" }) throw AuthException.NotOwned()

        val req = Request.Builder().url(endpoints.profile).header("Authorization", "Bearer $mcToken").build()
        val profile = withContext(Dispatchers.IO) {
            client.newCall(req).await().use { r ->
                if (r.code == 404) throw AuthException.NoProfile()
                if (!r.isSuccessful) throw AuthException.Http(r.code, "Profile request")
                LauncherJson.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
            }
        }
        val skin = ((profile["skins"] as? JsonArray)?.firstOrNull { (it as? JsonObject)?.s("state") == "ACTIVE" } as? JsonObject)?.s("url")
        return MinecraftSession(
            uuid = formatUuid(profile.s("id") ?: throw IOException("Profile has no id")),
            username = profile.s("name") ?: throw IOException("Profile has no name"),
            skinUrl = skin, minecraftAccessToken = mcToken, minecraftExpiresAtMs = mcExpires,
            msRefreshToken = ms.refreshToken, xuid = claims.s("xid"),
        )
    }

    /** Refreshes a stored session if the Minecraft token expires within [marginMs]. */
    suspend fun ensureFresh(stored: StoredTokens, marginMs: Long = 10 * 60_000): MinecraftSession? {
        if (stored.minecraftExpiresAtMs - marginMs > now()) return null
        return loginMinecraft(refresh(stored.msRefreshToken))
    }

    private fun tokensFrom(o: JsonObject) = MsTokens(
        o.s("access_token") ?: throw IOException("No access token"),
        o.s("refresh_token") ?: throw IOException("No refresh token"),
        now() + (o.l("expires_in") ?: 3600) * 1000,
    )

    private suspend fun postForm(url: String, form: Map<String, String>, what: String): JsonObject {
        val (status, body) = rawPostForm(url, form)
        if (status == 400 && (body.s("error") == "invalid_client" || body.s("error") == "unauthorized_client")) {
            throw AuthException.InvalidClient(body.s("error_description")?.lineSequence()?.firstOrNull() ?: "invalid client")
        }
        if (status !in 200..299) throw AuthException.Http(status, what)
        return body
    }

    private suspend fun rawPostForm(url: String, form: Map<String, String>): Pair<Int, JsonObject> {
        val b = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return exec(Request.Builder().url(url).post(b).header("Accept", "application/json").build())
    }

    private suspend fun postJson(url: String, body: JsonObject, what: String): JsonObject {
        val (status, o) = rawPostJson(url, body)
        if (status !in 200..299) throw AuthException.Http(status, what)
        return o
    }

    private suspend fun rawPostJson(url: String, body: JsonObject): Pair<Int, JsonObject> {
        val rb: RequestBody = body.toString().toRequestBody(jsonType)
        return exec(Request.Builder().url(url).post(rb).header("Accept", "application/json").build())
    }

    private suspend fun getJson(url: String, bearer: String, what: String): JsonObject {
        val (status, o) = exec(Request.Builder().url(url).header("Authorization", "Bearer $bearer").build())
        if (status == 401) throw AuthException.Session()
        if (status !in 200..299) throw AuthException.Http(status, what)
        return o
    }

    private suspend fun exec(req: Request): Pair<Int, JsonObject> = withContext(Dispatchers.IO) {
        client.newCall(req).await().use { r ->
            val text = r.body?.string().orEmpty()
            val obj = runCatching { LauncherJson.parseToJsonElement(text).jsonObject }.getOrElse { JsonObject(emptyMap()) }
            r.code to obj
        }
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull

    companion object {
        fun formatUuid(raw: String): String {
            val r = raw.replace("-", "")
            if (r.length != 32) return raw
            return "${r.substring(0, 8)}-${r.substring(8, 12)}-${r.substring(12, 16)}-${r.substring(16, 20)}-${r.substring(20)}"
        }
    }
}

/** Pending browser sign-in; [verifier] must stay in memory only. */
data class BrowserSignIn(val url: String, val state: String, val verifier: String, val redirectUri: String) {
    override fun toString() = "BrowserSignIn(state=***, verifier=***)"
}

object Pkce {
    /** 43..128 chars from the unreserved set (RFC 7636 §4.1); 64 random bytes → 86 chars. */
    fun verifier(random: java.security.SecureRandom): String {
        val bytes = ByteArray(64).also(random::nextBytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun challenge(verifier: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
