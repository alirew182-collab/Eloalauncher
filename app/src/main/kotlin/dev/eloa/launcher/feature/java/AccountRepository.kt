package dev.eloa.launcher.feature.java

import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.data.db.AccountDao
import dev.eloa.launcher.core.data.db.AccountEntity
import dev.eloa.launcher.core.data.security.SecretCipher
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.domain.java.auth.AuthException
import dev.eloa.launcher.domain.java.auth.BrowserSignIn
import dev.eloa.launcher.domain.java.launch.AccountSnapshot
import dev.eloa.launcher.domain.java.launch.LaunchMode
import dev.eloa.launcher.domain.java.launch.LaunchModes
import dev.eloa.launcher.feature.common.NetworkStatus
import dev.eloa.launcher.domain.java.auth.DeviceCode
import dev.eloa.launcher.domain.java.auth.MicrosoftAuth
import dev.eloa.launcher.domain.java.auth.MinecraftSession
import dev.eloa.launcher.domain.java.auth.StoredTokens
import dev.eloa.launcher.domain.java.launch.LaunchIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

class NotSignedInException : IOException("Sign in with a Microsoft account that owns Minecraft: Java Edition (Accounts).")
class OfflineSessionExpiredException : IOException("You are offline and the saved session has expired. Use Offline mode for singleplayer, or connect to the internet.")
class NoNetworkException : IOException("Online mode needs an internet connection.")

/**
 * Microsoft accounts. Tokens are stored only encrypted (Android Keystore AES-GCM) and never logged.
 * Account data is independent from local game files.
 */
@Singleton
class AccountRepository @Inject constructor(
    private val dao: AccountDao,
    private val cipher: SecretCipher,
    private val settings: SettingsRepository,
    private val http: OkHttpClient,
    private val logger: AppLogger,
    private val network: NetworkStatus,
) {
    /** Pending browser sign-in. Kept only in memory: the PKCE verifier must never be persisted. */
    @Volatile private var pendingBrowser: BrowserSignIn? = null
    val accounts: Flow<List<AccountEntity>> = dao.observeAll()

    private suspend fun auth(): MicrosoftAuth {
        val id = settings.current().msClientId
        if (id.isBlank()) throw IOException("Set your Azure application (client) ID in Settings → Accounts first.")
        return MicrosoftAuth(http, id)
    }

    suspend fun startSignIn(): DeviceCode = auth().requestDeviceCode()

    /** Starts the system-browser (authorization code + PKCE) flow and returns the URL to open. */
    suspend fun beginBrowserSignIn(): String {
        val p = auth().beginBrowserSignIn(REDIRECT_URI)
        pendingBrowser = p
        return p.url
    }

    fun hasPendingBrowserSignIn() = pendingBrowser != null

    /** Completes the browser flow with the redirect the app received. */
    suspend fun completeBrowserSignIn(redirect: String): AccountEntity {
        val pending = pendingBrowser ?: throw AuthException.StateMismatch()
        try {
            val a = auth()
            val session = a.loginMinecraft(a.exchangeCode(pending, redirect))
            val e = save(session, makeActive = true)
            logger.i("Accounts", "Signed in (browser)", mapOf("player" to session.username))
            return e
        } finally {
            pendingBrowser = null
        }
    }

    suspend fun snapshot(): AccountSnapshot? = dao.active()?.let { AccountSnapshot(it.username, it.minecraftTokenExpiresAt) }

    suspend fun completeSignIn(code: DeviceCode): AccountEntity {
        val a = auth()
        val session = a.loginMinecraft(a.pollForTokens(code))
        val e = save(session, makeActive = true)
        logger.i("Accounts", "Signed in", mapOf("player" to session.username))
        return e
    }

    private suspend fun save(s: MinecraftSession, makeActive: Boolean): AccountEntity {
        val tokens = StoredTokens(s.msRefreshToken, s.minecraftAccessToken, s.minecraftExpiresAtMs, s.xuid)
        val old = dao.byId(s.uuid)
        val e = AccountEntity(
            id = s.uuid, username = s.username, xuid = s.xuid,
            encryptedTokens = cipher.encrypt(Json.encodeToString(StoredTokens.serializer(), tokens).toByteArray()),
            minecraftTokenExpiresAt = s.minecraftExpiresAtMs, active = old?.active ?: makeActive, skinUrl = s.skinUrl,
            addedAt = old?.addedAt ?: System.currentTimeMillis(), lastRefreshAt = System.currentTimeMillis(),
        )
        dao.upsert(e)
        if (makeActive) dao.setActive(e.id)
        return e
    }

    suspend fun setActive(id: String) = dao.setActive(id)

    /** Signs out: deletes the stored tokens for this account. */
    suspend fun signOut(id: String) {
        dao.delete(id)
        if (dao.active() == null) dao.observeAll().first().firstOrNull()?.let { dao.setActive(it.id) }
        logger.i("Accounts", "Signed out")
    }

    private fun tokensOf(e: AccountEntity): StoredTokens = try {
        Json.decodeFromString(StoredTokens.serializer(), cipher.decrypt(e.encryptedTokens).decodeToString())
    } catch (_: Exception) { throw AuthException.Session() }

    /**
     * Identity for [mode]. Offline modes never touch the network and use the stored identity of an
     * account that was verified as a game owner when it was added; nothing is generated or faked.
     */
    suspend fun launchIdentity(mode: LaunchMode): LaunchIdentity {
        val acc = dao.active() ?: throw NotSignedInException()
        if (!LaunchModes.usesNetwork(mode)) {
            val t = tokensOf(acc)
            return LaunchIdentity(acc.username, acc.id, t.minecraftAccessToken, t.xuid ?: acc.xuid, settings.current().msClientId.ifBlank { null })
        }
        val online = network.isOnline()
        if (mode == LaunchMode.ONLINE && !online) throw NoNetworkException()
        if (!online) {
            if (acc.minecraftTokenExpiresAt <= System.currentTimeMillis()) throw OfflineSessionExpiredException()
            val t = tokensOf(acc)
            return LaunchIdentity(acc.username, acc.id, t.minecraftAccessToken, t.xuid ?: acc.xuid, settings.current().msClientId)
        }
        return launchIdentity()
    }

    /** Returns a launch identity, refreshing an expired Minecraft token transparently. */
    suspend fun launchIdentity(): LaunchIdentity {
        val acc = dao.active() ?: throw NotSignedInException()
        var tokens = tokensOf(acc)
        val refreshed = try { auth().ensureFresh(tokens) } catch (e: AuthException.Session) {
            logger.w("Accounts", "Session expired; re-login required"); throw e
        }
        if (refreshed != null) {
            save(refreshed, makeActive = true)
            tokens = StoredTokens(refreshed.msRefreshToken, refreshed.minecraftAccessToken, refreshed.minecraftExpiresAtMs, refreshed.xuid)
            logger.i("Accounts", "Minecraft session refreshed")
        }
        return LaunchIdentity(acc.username, acc.id, tokens.minecraftAccessToken, tokens.xuid ?: acc.xuid, settings.current().msClientId)
    }

    suspend fun refresh(id: String) {
        val acc = dao.byId(id) ?: return
        val s = auth().loginMinecraft(auth().refresh(tokensOf(acc).msRefreshToken))
        save(s, makeActive = acc.active)
    }
}

const val REDIRECT_URI = "eloa-launcher://auth"
