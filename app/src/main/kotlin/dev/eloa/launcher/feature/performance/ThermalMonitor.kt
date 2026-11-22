package dev.eloa.launcher.feature.performance

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eloa.launcher.R
import dev.eloa.launcher.core.data.di.AppScope
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.feature.notifications.NoticeType
import dev.eloa.launcher.feature.notifications.NotificationCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Mirrors PowerManager.THERMAL_STATUS_* (API 29+). */
enum class ThermalLevel { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN, UNSUPPORTED }

data class ThermalState(
    val level: ThermalLevel,
    /** Forecast headroom (1.0 = throttling starts), API 30+. */
    val headroom: Float?,
    val batteryTempC: Float?,
    val powerSave: Boolean,
) {
    val hot: Boolean get() = level in setOf(ThermalLevel.SEVERE, ThermalLevel.CRITICAL, ThermalLevel.EMERGENCY, ThermalLevel.SHUTDOWN) || (headroom ?: 0f) >= 0.95f
    val warm: Boolean get() = level == ThermalLevel.MODERATE || (headroom ?: 0f) >= 0.8f
}

/**
 * Reads the device's own thermal status through public Android APIs and warns when it gets hot.
 * It never changes system thermal limits; it only recommends lowering the load (FPS cap, view
 * distance, render scale) or switching to Battery Saver mode.
 */
@Singleton
class ThermalMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifications: NotificationCenter,
    private val settings: SettingsRepository,
    @AppScope private val scope: CoroutineScope,
) {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val _state = MutableStateFlow(read())
    val state: StateFlow<ThermalState> = _state
    private var started = false
    /** False when the device does not report thermal changes; the state is then refreshed on demand only. */
    @Volatile var listenerActive = false
        private set

    fun start() {
        if (started) return
        started = true
        if (Build.VERSION.SDK_INT >= 29) {
            // Some devices (and test environments) have no thermal service; the API then throws.
            listenerActive = runCatching { power.addThermalStatusListener(context.mainExecutor) { onChange() } }.isSuccess
        }
    }

    fun refresh() = onChange()

    private fun onChange() {
        val s = read()
        val wasHot = _state.value.hot
        _state.value = s
        if (s.hot && !wasHot) scope.launch {
            if (settings.current().thermalWarnings) {
                notifications.post(NoticeType.OVERHEATING, R.string.notice_overheating_title, context.getString(R.string.notice_overheating_body), "performance")
            }
        }
    }

    fun read(): ThermalState {
        val level = if (Build.VERSION.SDK_INT >= 29) runCatching { ThermalLevel.entries.getOrElse(power.currentThermalStatus) { ThermalLevel.UNSUPPORTED } }.getOrDefault(ThermalLevel.UNSUPPORTED) else ThermalLevel.UNSUPPORTED
        val headroom = if (Build.VERSION.SDK_INT >= 30) runCatching { power.getThermalHeadroom(10) }.getOrNull()?.takeIf { !it.isNaN() } else null
        val temp = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
        return ThermalState(level, headroom, temp, power.isPowerSaveMode)
    }
}
