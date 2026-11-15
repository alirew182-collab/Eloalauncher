package dev.eloa.launcher.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class PerformancePreset { ULTRA_LOW, LOW, BALANCED, HIGH, ULTRA, CUSTOM }
enum class Edition { BEDROCK, JAVA }

data class LauncherSettings(
    val themeMode: String = "SYSTEM",
    val accent: String = "GRASS",
    val enableBlur: Boolean = true,
    val reduceMotion: Boolean = false,
    val performancePreset: PerformancePreset = PerformancePreset.BALANCED,
    val presetChosenByUser: Boolean = false,
    val defaultMaxMemoryMb: Int = 2048,
    val maxConcurrentDownloads: Int = 6,
    val uiRefreshCapHz: Int = 0,
    val limitBackgroundWork: Boolean = true,
    val autoCleanupTemp: Boolean = true,
    val tempMaxAgeHours: Int = 24,
    val cacheLimitMb: Int = 512,
    val msClientId: String = "",
    val selectedEdition: Edition = Edition.JAVA,
    val selectedBedrockId: Long = -1,
    val selectedProfileId: String = "",
    val mojangFolderUri: String = "",
    val minLogLevel: String = "DEBUG",
    val maxLogEntries: Int = 5000,
    val onboardingDone: Boolean = false,
    val lastCleanupAt: Long = 0,
)

class SettingsRepository(private val store: DataStore<Preferences>) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val accent = stringPreferencesKey("accent")
        val blur = booleanPreferencesKey("blur")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val preset = stringPreferencesKey("perf_preset")
        val presetChosen = booleanPreferencesKey("perf_preset_user")
        val maxMem = intPreferencesKey("default_max_mem")
        val concurrency = intPreferencesKey("dl_concurrency")
        val refreshCap = intPreferencesKey("ui_refresh_cap")
        val limitBg = booleanPreferencesKey("limit_bg")
        val autoCleanup = booleanPreferencesKey("auto_cleanup")
        val tempAge = intPreferencesKey("temp_age_h")
        val cacheLimit = intPreferencesKey("cache_limit_mb")
        val clientId = stringPreferencesKey("ms_client_id")
        val edition = stringPreferencesKey("edition")
        val bedrockId = longPreferencesKey("bedrock_id")
        val profileId = stringPreferencesKey("profile_id")
        val mojangUri = stringPreferencesKey("mojang_uri")
        val minLog = stringPreferencesKey("min_log")
        val maxLogs = intPreferencesKey("max_logs")
        val onboarding = booleanPreferencesKey("onboarding")
        val lastCleanup = longPreferencesKey("last_cleanup")
    }

    val settings: Flow<LauncherSettings> = store.data
        .catch { e -> if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e }
        .map { p -> fromPrefs(p) }

    private fun fromPrefs(p: Preferences): LauncherSettings {
            val d = LauncherSettings()
            return LauncherSettings(
                themeMode = p[K.theme] ?: d.themeMode,
                accent = p[K.accent] ?: d.accent,
                enableBlur = p[K.blur] ?: d.enableBlur,
                reduceMotion = p[K.reduceMotion] ?: d.reduceMotion,
                performancePreset = p[K.preset]?.let { runCatching { PerformancePreset.valueOf(it) }.getOrNull() } ?: d.performancePreset,
                presetChosenByUser = p[K.presetChosen] ?: false,
                defaultMaxMemoryMb = p[K.maxMem] ?: d.defaultMaxMemoryMb,
                maxConcurrentDownloads = (p[K.concurrency] ?: d.maxConcurrentDownloads).coerceIn(1, 16),
                uiRefreshCapHz = p[K.refreshCap] ?: 0,
                limitBackgroundWork = p[K.limitBg] ?: true,
                autoCleanupTemp = p[K.autoCleanup] ?: true,
                tempMaxAgeHours = p[K.tempAge] ?: 24,
                cacheLimitMb = p[K.cacheLimit] ?: 512,
                msClientId = p[K.clientId] ?: "",
                selectedEdition = p[K.edition]?.let { runCatching { Edition.valueOf(it) }.getOrNull() } ?: d.selectedEdition,
                selectedBedrockId = p[K.bedrockId] ?: -1,
                selectedProfileId = p[K.profileId] ?: "",
                mojangFolderUri = p[K.mojangUri] ?: "",
                minLogLevel = p[K.minLog] ?: "DEBUG",
                maxLogEntries = p[K.maxLogs] ?: 5000,
                onboardingDone = p[K.onboarding] ?: false,
                lastCleanupAt = p[K.lastCleanup] ?: 0,
            )
    }

    suspend fun current(): LauncherSettings = settings.first()

    suspend fun update(transform: (LauncherSettings) -> LauncherSettings) {
        store.edit { p ->
            val old = fromPrefs(p)
            val n = transform(old)
            p[K.theme] = n.themeMode; p[K.accent] = n.accent; p[K.blur] = n.enableBlur; p[K.reduceMotion] = n.reduceMotion
            p[K.preset] = n.performancePreset.name; p[K.presetChosen] = n.presetChosenByUser
            p[K.maxMem] = n.defaultMaxMemoryMb.coerceIn(256, 32768)
            p[K.concurrency] = n.maxConcurrentDownloads.coerceIn(1, 16)
            p[K.refreshCap] = n.uiRefreshCapHz; p[K.limitBg] = n.limitBackgroundWork
            p[K.autoCleanup] = n.autoCleanupTemp; p[K.tempAge] = n.tempMaxAgeHours.coerceIn(1, 24 * 30)
            p[K.cacheLimit] = n.cacheLimitMb.coerceIn(32, 16384)
            p[K.clientId] = n.msClientId.trim(); p[K.edition] = n.selectedEdition.name
            p[K.bedrockId] = n.selectedBedrockId; p[K.profileId] = n.selectedProfileId
            p[K.mojangUri] = n.mojangFolderUri; p[K.minLog] = n.minLogLevel
            p[K.maxLogs] = n.maxLogEntries.coerceIn(500, 100_000)
            p[K.onboarding] = n.onboardingDone; p[K.lastCleanup] = n.lastCleanupAt
        }
    }
}
