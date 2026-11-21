package dev.eloa.launcher.domain.java.launch

import dev.eloa.launcher.core.common.version.ComparableVersion

/**
 * How a Java Edition session is started. Every mode maps to a concrete technical scenario:
 *
 * * [OFFICIAL_ACCOUNT] — the signed-in Microsoft account; the Minecraft token is refreshed when the
 *   network is available, otherwise a still-valid stored token is used.
 * * [ONLINE] — same account, but the token must be validated/refreshed right now (needed for
 *   multiplayer on online-mode servers and Realms). Requires network.
 * * [OFFLINE] — no network calls. Uses the identity of an account that was already verified as a
 *   game owner on this device and its stored token (possibly expired). Singleplayer and LAN work;
 *   online-mode servers will reject the session. Nothing is forged: no account → mode unavailable.
 * * [LOCAL_SINGLEPLAYER] — [OFFLINE] plus Minecraft's Quick Play option that opens a chosen local
 *   world directly (Minecraft 1.20+).
 */
enum class LaunchMode { OFFICIAL_ACCOUNT, ONLINE, OFFLINE, LOCAL_SINGLEPLAYER }

enum class ModeBlocker {
    NO_ACCOUNT,
    NO_NETWORK,
    TOKEN_EXPIRED_OFFLINE,
    QUICK_PLAY_UNSUPPORTED,
    NO_LOCAL_WORLDS,
}

data class AccountSnapshot(val username: String, val tokenExpiresAtMs: Long)

data class ModeContext(
    val account: AccountSnapshot?,
    val networkAvailable: Boolean,
    val nowMs: Long,
    val minecraftVersion: String?,
    val localWorlds: List<String>,
)

data class ModeAvailability(val mode: LaunchMode, val blockers: List<ModeBlocker>) {
    val available: Boolean get() = blockers.isEmpty()
}

object LaunchModes {
    /** Minecraft added `--quickPlaySingleplayer` in 1.20 (23w14a). */
    fun supportsQuickPlay(mcVersion: String?): Boolean {
        if (mcVersion == null) return false
        Regex("^(\\d{2})w(\\d{2})[a-z]$").find(mcVersion)?.let { m ->
            val (y, w) = m.destructured
            return y.toInt() > 23 || (y.toInt() == 23 && w.toInt() >= 14)
        }
        val rel = Regex("^1\\.\\d+(\\.\\d+)?").find(mcVersion)?.value ?: return false
        return ComparableVersion(rel) >= ComparableVersion("1.20")
    }

    fun evaluate(mode: LaunchMode, ctx: ModeContext): ModeAvailability {
        val b = ArrayList<ModeBlocker>()
        if (ctx.account == null) b += ModeBlocker.NO_ACCOUNT
        when (mode) {
            LaunchMode.OFFICIAL_ACCOUNT -> if (ctx.account != null && !ctx.networkAvailable && ctx.account.tokenExpiresAtMs <= ctx.nowMs) b += ModeBlocker.TOKEN_EXPIRED_OFFLINE
            LaunchMode.ONLINE -> if (!ctx.networkAvailable) b += ModeBlocker.NO_NETWORK
            LaunchMode.OFFLINE -> Unit
            LaunchMode.LOCAL_SINGLEPLAYER -> {
                if (!supportsQuickPlay(ctx.minecraftVersion)) b += ModeBlocker.QUICK_PLAY_UNSUPPORTED
                if (ctx.localWorlds.isEmpty()) b += ModeBlocker.NO_LOCAL_WORLDS
            }
        }
        return ModeAvailability(mode, b)
    }

    fun evaluateAll(ctx: ModeContext) = LaunchMode.entries.map { evaluate(it, ctx) }

    /** Whether this mode is allowed to contact Microsoft/Mojang services before launch. */
    fun usesNetwork(mode: LaunchMode) = mode == LaunchMode.ONLINE || mode == LaunchMode.OFFICIAL_ACCOUNT
}
