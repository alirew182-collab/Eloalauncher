package dev.eloa.launcher.feature.performance

import androidx.compose.ui.res.stringResource
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eloa.launcher.R
import dev.eloa.launcher.core.common.io.FileOps
import dev.eloa.launcher.core.data.db.JavaProfileEntity
import dev.eloa.launcher.core.data.device.DeviceInfo
import dev.eloa.launcher.core.data.device.DeviceInfoProvider
import dev.eloa.launcher.core.data.performance.MemoryWarning
import dev.eloa.launcher.core.data.performance.PerformanceAdvisor
import dev.eloa.launcher.core.data.performance.PresetSpec
import dev.eloa.launcher.core.data.settings.LauncherSettings
import dev.eloa.launcher.core.data.settings.PerformancePreset
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.core.designsystem.component.LabeledValue
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.SectionHeader
import dev.eloa.launcher.core.designsystem.component.SkeletonList
import dev.eloa.launcher.core.designsystem.component.StatusChip
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.core.designsystem.component.UsageBar
import dev.eloa.launcher.domain.java.options.OptionsTxt
import dev.eloa.launcher.domain.java.render.RendererCatalog
import dev.eloa.launcher.domain.java.render.RendererId
import dev.eloa.launcher.domain.java.render.RendererVerdict
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.description
import dev.eloa.launcher.feature.common.label
import dev.eloa.launcher.feature.common.text
import dev.eloa.launcher.feature.common.userMessage
import dev.eloa.launcher.feature.java.ProfileRepository
import dev.eloa.launcher.feature.launch.gpuProfile
import dev.eloa.launcher.feature.settings.summary
import dev.eloa.launcher.nav.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.roundToInt

data class DisplayFacts(val widthPx: Int, val heightPx: Int, val refreshHz: Float)
data class MemoryFacts(val totalMb: Long, val availMb: Long, val launcherPssMb: Long, val lowMemory: Boolean)
data class EnvFacts(val backgroundRestricted: Boolean, val freeStorage: Long, val totalStorage: Long)

data class PerfUi(
    val device: DeviceInfo? = null,
    val display: DisplayFacts? = null,
    val memory: MemoryFacts? = null,
    val env: EnvFacts? = null,
    val settings: LauncherSettings = LauncherSettings(),
    val profile: JavaProfileEntity? = null,
    val renderers: List<RendererVerdict> = emptyList(),
)

@HiltViewModel
class PerformanceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceInfo: DeviceInfoProvider,
    private val settings: SettingsRepository,
    private val profiles: ProfileRepository,
    private val storage: LauncherStorage,
    private val thermalMonitor: ThermalMonitor,
    private val messages: UserMessages,
) : ViewModel() {
    private val _device = MutableStateFlow<DeviceInfo?>(null)
    private val _memory = MutableStateFlow<MemoryFacts?>(null)
    private val _env = MutableStateFlow<EnvFacts?>(null)
    val thermal = thermalMonitor.state

    val ui: StateFlow<PerfUi> = combine(_device, _memory, _env, settings.settings, profiles.profiles) { d, m, e, s, profs ->
        val p = profs.firstOrNull { it.id == s.selectedProfileId } ?: profs.firstOrNull()
        PerfUi(d, display(), m, e, s, p, d?.let { RendererCatalog.evaluate(it.gpuProfile(), p?.minecraftVersion, INSTALLED_RENDERERS) }.orEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PerfUi())

    init {
        refresh()
        viewModelScope.launch { while (true) { _memory.value = memory(); delay(3_000) } }
    }

    fun refresh() = viewModelScope.launch {
        _device.value = withContext(Dispatchers.Default) { deviceInfo.collect() }
        _env.value = withContext(Dispatchers.IO) {
            val am = context.getSystemService(ActivityManager::class.java)
            EnvFacts(Build.VERSION.SDK_INT >= 28 && am.isBackgroundRestricted, storage.freeBytes(), storage.totalBytes())
        }
        thermalMonitor.refresh()
    }

    private fun memory(): MemoryFacts {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pss = runCatching { am.getProcessMemoryInfo(intArrayOf(Process.myPid())).first().totalPss / 1024L }.getOrDefault(Debug.getPss() / 1024)
        return MemoryFacts(mi.totalMem / 1_048_576, mi.availMem / 1_048_576, pss, mi.lowMemory)
    }

    @Suppress("DEPRECATION")
    private fun display(): DisplayFacts? = runCatching {
        val d = context.getSystemService(WindowManager::class.java).defaultDisplay
        val mode = d.mode
        DisplayFacts(mode.physicalWidth, mode.physicalHeight, mode.refreshRate)
    }.getOrNull()

    fun spec(p: PerformancePreset): PresetSpec? = _device.value?.let { PerformanceAdvisor.spec(p, it.summary()) }
    fun recommended(): PerformancePreset? = _device.value?.let { PerformanceAdvisor.recommend(it.summary()) }

    /** Applies the mode to launcher settings and, if a profile exists, to its options.txt, heap and render scale. */
    fun apply(p: PerformancePreset) = viewModelScope.launch {
        val spec = spec(p) ?: return@launch
        settings.update {
            if (p == PerformancePreset.CUSTOM) it.copy(performancePreset = p, presetChosenByUser = true)
            else it.copy(performancePreset = p, presetChosenByUser = true, defaultMaxMemoryMb = spec.javaMaxMemoryMb, maxConcurrentDownloads = spec.maxConcurrentDownloads,
                reduceMotion = !spec.uiAnimations, enableBlur = spec.blur, uiRefreshCapHz = spec.uiRefreshCapHz, cacheLimitMb = spec.cacheLimitMb,
                limitBackgroundWork = p == PerformancePreset.BATTERY_SAVER || p == PerformancePreset.EXTREME_PERFORMANCE)
        }
        val profile = ui.value.profile
        if (p != PerformancePreset.CUSTOM && profile != null) {
            runCatching {
                withContext(Dispatchers.IO) { OptionsTxt.apply(storage.instance(profile.gameDirName).gameDir, spec.game, profile.minecraftVersion) }
                profiles.updateLaunchOptions(profile.id) { it.copy(maxMemoryMb = spec.javaMaxMemoryMb, minMemoryMb = minOf(it.minMemoryMb, spec.javaMaxMemoryMb), resolutionScale = spec.resolutionScale, graphicsPreset = p.name) }
            }.onSuccess { messages.info(R.string.msg_mode_applied_profile, profile.name) }.onFailure { messages.error(it.userMessage()) }
        } else messages.info(R.string.msg_mode_applied)
    }

    fun setHeap(mb: Int) = viewModelScope.launch {
        val p = ui.value.profile ?: return@launch
        runCatching { profiles.updateLaunchOptions(p.id) { it.copy(maxMemoryMb = mb, minMemoryMb = minOf(it.minMemoryMb, mb), graphicsPreset = PerformancePreset.CUSTOM.name) } }
            .onFailure { messages.error(it.userMessage()) }
        settings.update { it.copy(performancePreset = PerformancePreset.CUSTOM) }
    }

    fun setRenderer(id: RendererId?) = viewModelScope.launch {
        val p = ui.value.profile ?: return@launch
        profiles.updateLaunchOptions(p.id) { it.copy(renderer = id?.name ?: "AUTO") }
    }

    fun setThermalWarnings(on: Boolean) = viewModelScope.launch { settings.update { it.copy(thermalWarnings = on) } }

    companion object {
        /** Renderer libraries bundled in this build. None yet: see ARCHITECTURE.md (runtime backend). */
        val INSTALLED_RENDERERS: Set<RendererId> = emptySet()
    }
}

@Composable
fun PerformanceScreen(padding: PaddingValues, onNavigate: (Destination) -> Unit, vm: PerformanceViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val th by vm.thermal.collectAsStateWithLifecycle()
    LazyColumn(Modifier.padding(padding).testTag("performance"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { SectionHeader(stringResource(R.string.device), subtitle = stringResource(R.string.detected_with_public_android_apis_only)) }
        item {
            LauncherCard(Modifier.fillMaxWidth().testTag("device_card")) {
                val d = ui.device
                if (d == null) SkeletonList(3) else {
                    LabeledValue(stringResource(R.string.model), "${d.manufacturer} ${d.model}")
                    LabeledValue(stringResource(R.string.android), stringResource(R.string.s_1_s_api_2_s, d.androidRelease, d.sdkInt))
                    LabeledValue(stringResource(R.string.soc), listOfNotNull(d.socManufacturer, d.socModel).joinToString(" ").ifEmpty { d.cpuName ?: d.hardware })
                    LabeledValue("CPU", "${d.cpuCores} × " + (d.cpuMaxFreqMhz.maxOrNull()?.let { stringResource(R.string.s_1_s_mhz, it) } ?: stringResource(R.string.not_reported)) + " · ${d.supportedAbis.joinToString()}")
                    LabeledValue("GPU", listOfNotNull(d.gpu.vendor, d.gpu.renderer).joinToString(" · ").ifEmpty { stringResource(R.string.not_reported) })
                    LabeledValue(stringResource(R.string.opengl_es), d.gpu.glVersion ?: d.glesVersion)
                    LabeledValue(stringResource(R.string.vulkan), if (d.vulkanSupported) d.vulkanVersion ?: "1.0" else "—")
                    ui.display?.let { LabeledValue(stringResource(R.string.display), stringResource(R.string.s_1_s_2_s_3_s, it.widthPx, it.heightPx, it.refreshHz.roundToInt())) }
                    LabeledValue("RAM", "${d.totalRamMb} MB" + if (d.isLowRamDevice) stringResource(R.string.low_ram_device) else "")
                    ui.env?.let {
                        LabeledValue(stringResource(R.string.storage), "${FileOps.formatSize(it.freeStorage)} / ${FileOps.formatSize(it.totalStorage)}")
                        LabeledValue(stringResource(R.string.background), if (it.backgroundRestricted) stringResource(R.string.restricted_by_the_system) else stringResource(R.string.not_restricted))
                    }
                    TextButton(onClick = { vm.refresh() }) { Text(stringResource(R.string.re_measure)) }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.performance_mode), subtitle = stringResource(R.string.modes_set_view_distance_fps_cap)) }
        item {
            LauncherCard(Modifier.fillMaxWidth().testTag("modes_card")) {
                val rec = vm.recommended()
                PerformancePreset.entries.forEach { p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        RadioButton(ui.settings.performancePreset == p, { vm.apply(p) }, modifier = Modifier.testTag("preset_${p.name}"))
                        Column(Modifier.weight(1f).padding(top = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.label(), style = MaterialTheme.typography.titleSmall)
                                if (p == rec) { Spacer(Modifier.padding(4.dp)); StatusChip(stringResource(R.string.recommended), StatusLevel.INFO) }
                            }
                            Text(p.description(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            vm.spec(p)?.takeIf { p != PerformancePreset.CUSTOM }?.let { s ->
                                Text(stringResource(R.string.s_1_s_mb_view_2_s, s.javaMaxMemoryMb, s.game.renderDistance, s.game.maxFps, (s.resolutionScale * 100).roundToInt()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                Text(if (ui.profile != null) stringResource(R.string.applies_to_profile_1_s, ui.profile?.name.orEmpty()) else stringResource(R.string.no_java_profile_yet_only_launcher), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onNavigate(Destination.GAME_SETTINGS) }) { Text(stringResource(R.string.fine_tune_game_settings)) }
            }
        }

        item { SectionHeader(stringResource(R.string.memory)) }
        item {
            LauncherCard(Modifier.fillMaxWidth().testTag("memory_card")) {
                val m = ui.memory
                if (m != null) {
                    LabeledValue(stringResource(R.string.total_ram), "${m.totalMb} MB")
                    LabeledValue(stringResource(R.string.used), "${m.totalMb - m.availMb} MB")
                    LabeledValue(stringResource(R.string.available), "${m.availMb} MB" + if (m.lowMemory) stringResource(R.string.low) else "")
                    LabeledValue(stringResource(R.string.launcher_itself), "${m.launcherPssMb} MB")
                    UsageBar((m.totalMb - m.availMb).toFloat() / m.totalMb.coerceAtLeast(1))
                    Spacer(Modifier.height(8.dp))
                    val safe = PerformanceAdvisor.safeMaxMemoryMb(m.totalMb)
                    val p = ui.profile
                    if (p != null) {
                        var heap by remember(p.id, p.maxMemoryMb) { mutableFloatStateOf(p.maxMemoryMb.toFloat()) }
                        Text(stringResource(R.string.java_heap_for_1_s_2, p.name, heap.roundToInt(), safe, PerformanceAdvisor.suggestedMemoryMb(m.totalMb)), fontWeight = FontWeight.Medium)
                        val max = (m.totalMb - 512).coerceAtLeast(1024).toFloat()
                        Slider(heap.coerceIn(512f, max), { heap = (it / 128).roundToInt() * 128f }, onValueChangeFinished = { vm.setHeap(heap.roundToInt()) }, valueRange = 512f..max, modifier = Modifier.testTag("heap_slider"))
                        when (PerformanceAdvisor.memoryWarning(heap.roundToInt(), m.totalMb)) {
                            MemoryWarning.ABOVE_SAFE -> StatusChip(stringResource(R.string.above_the_safe_limit_android_may), StatusLevel.ERROR)
                            MemoryWarning.TOO_LOW -> StatusChip(stringResource(R.string.too_little_memory_to_start_minecraft), StatusLevel.ERROR)
                            null -> StatusChip(stringResource(R.string.within_the_safe_range), StatusLevel.OK)
                        }
                    }
                    Text(stringResource(R.string.memory_used_by_the_running_game), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else SkeletonList(2)
            }
        }

        item { SectionHeader(stringResource(R.string.renderers), subtitle = stringResource(R.string.opengl_translation_layers_evaluated_for_this)) }
        item {
            LauncherCard(Modifier.fillMaxWidth().testTag("renderers_card")) {
                val chosen = ui.profile?.renderer ?: "AUTO"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(chosen == "AUTO", { vm.setRenderer(null) }, enabled = ui.profile != null)
                    Text(stringResource(R.string.label_automatic), Modifier.weight(1f))
                }
                ui.renderers.forEach { v ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        RadioButton(chosen == v.spec.id.name, { vm.setRenderer(v.spec.id) }, enabled = ui.profile != null && v.compatible)
                        Column(Modifier.weight(1f).padding(top = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(v.spec.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                when {
                                    !v.compatible -> StatusChip(stringResource(R.string.incompatible), StatusLevel.ERROR)
                                    v.recommended -> StatusChip(stringResource(R.string.auto_choice), StatusLevel.OK)
                                    else -> StatusChip(stringResource(R.string.compatible), StatusLevel.INFO)
                                }
                            }
                            Text(stringResource(R.string.s_1_s_2_s_mc_3, v.spec.provides, v.spec.uses, v.spec.supportedMc, v.spec.license), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.mods_1_s, v.spec.modCompat), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            v.blockers.forEach { Text(it.text(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            v.warnings.forEach { Text(it.text(), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.thermal)) }
        item {
            LauncherCard(Modifier.fillMaxWidth().testTag("thermal_card")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.status) + th.level.label(), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    when { th.hot -> StatusChip(stringResource(R.string.hot), StatusLevel.ERROR); th.warm -> StatusChip(stringResource(R.string.warm), StatusLevel.WARNING); else -> StatusChip("OK", StatusLevel.OK) }
                }
                th.headroom?.let { LabeledValue(stringResource(R.string.thermal_headroom), "%.2f".format(it)) }
                th.batteryTempC?.let { LabeledValue(stringResource(R.string.battery), "%.1f °C".format(it)) }
                LabeledValue(stringResource(R.string.battery_saver), if (th.powerSave) stringResource(R.string.on) else stringResource(R.string.clouds_off))
                if (th.hot || th.warm) {
                    Text(stringResource(R.string.recommendations_cap_fps_at_30_60), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.apply(PerformancePreset.BATTERY_SAVER) }) { Text(stringResource(R.string.use_battery_saver)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.warn_me_when_the_device_overheats), Modifier.weight(1f))
                    Switch(ui.settings.thermalWarnings, vm::setThermalWarnings, modifier = Modifier.testTag("switch_thermal"))
                }
                Text(stringResource(R.string.the_launcher_only_reads_android_s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = { vm.refresh() }) { Text(stringResource(R.string.re_check)) }
            }
        }
        item { FilterChipsSpacer() }
    }
    LaunchedEffect(Unit) { vm.refresh() }
}

@Composable
private fun FilterChipsSpacer() { Spacer(Modifier.height(24.dp)) }
