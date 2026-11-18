package dev.eloa.launcher.feature.java

import dev.eloa.launcher.core.common.logging.AppLogger
import dev.eloa.launcher.core.data.db.AccountDao
import dev.eloa.launcher.core.data.db.AccountEntity
import dev.eloa.launcher.core.data.security.SecretCipher
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.domain.java.auth.AuthException
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

class NotSignedInException : IOException("Sign in with a Microsoft account that owns Minecraft: Java Edition (Settings → Accounts).")

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
) {
    val accounts: Flow<List<AccountEntity>> = dao.observeAll()

    private suspend fun auth(): MicrosoftAuth {
        val id = settings.current().msClientId
        if (id.isBlank()) throw IOException("Set your Azure application (client) ID in Settings → Accounts first.")
        return MicrosoftAuth(http, id)
    }

    suspend fun startSignIn(): DeviceCode = auth().requestDeviceCode()

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
