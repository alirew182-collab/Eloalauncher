package dev.eloa.launcher.feature.gamesettings

import dev.eloa.launcher.feature.common.str
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eloa.launcher.R
import dev.eloa.launcher.core.data.db.JavaProfileEntity
import dev.eloa.launcher.core.data.storage.LauncherStorage
import dev.eloa.launcher.core.designsystem.component.ChipRow
import dev.eloa.launcher.core.designsystem.component.EmptyState
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.SectionHeader
import dev.eloa.launcher.domain.java.options.CloudMode
import dev.eloa.launcher.domain.java.options.GameOptions
import dev.eloa.launcher.domain.java.options.GraphicsQuality
import dev.eloa.launcher.domain.java.options.OptionsTxt
import dev.eloa.launcher.domain.java.options.ParticleLevel
import dev.eloa.launcher.domain.java.runtime.JavaRuntimeInfo
import dev.eloa.launcher.feature.common.ProfileSelection
import dev.eloa.launcher.feature.common.ProfileSelectorBar
import dev.eloa.launcher.feature.common.SelectedProfile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.label
import dev.eloa.launcher.feature.common.userMessage
import dev.eloa.launcher.feature.java.JavaRepository
import dev.eloa.launcher.feature.java.ProfileRepository
import dev.eloa.launcher.nav.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.roundToInt

enum class GcChoice(val flag: String?) { DEFAULT(null), G1("-XX:+UseG1GC"), PARALLEL("-XX:+UseParallelGC"), SERIAL("-XX:+UseSerialGC") }

data class GameSettingsUi(
    val profile: JavaProfileEntity? = null,
    val options: GameOptions = GameOptions(),
    val minMb: Int = 512, val maxMb: Int = 2048,
    val gc: GcChoice = GcChoice.DEFAULT,
    val jvmArgs: String = "", val gameArgs: String = "",
    val width: String = "", val height: String = "",
    val runtimeName: String? = null, val resolutionScale: Float = 1f,
    val runtimes: List<JavaRuntimeInfo> = emptyList(),
    val requiredJava: Int? = null,
    val dirty: Boolean = false,
)

@HiltViewModel
class GameSettingsViewModel @Inject constructor(
    private val selected: SelectedProfile,
    private val profiles: ProfileRepository,
    private val java: JavaRepository,
    private val storage: LauncherStorage,
    private val messages: UserMessages,
) : ViewModel() {
    val selection: StateFlow<ProfileSelection> = selected.selection.stateIn(viewModelScope, SharingStarted.Eagerly, ProfileSelection(emptyList(), null))
    private val _ui = MutableStateFlow(GameSettingsUi())
    val ui: StateFlow<GameSettingsUi> = _ui

    fun bind(p: JavaProfileEntity?) = viewModelScope.launch {
        if (p == null) { _ui.value = GameSettingsUi(); return@launch }
        if (_ui.value.profile?.id == p.id && _ui.value.dirty) return@launch
        val opts = withContext(Dispatchers.IO) { OptionsTxt.load(storage.instance(p.gameDirName).gameDir) }
        val args = p.jvmArgs.split(' ').filter { it.isNotBlank() }
        val gc = GcChoice.entries.firstOrNull { c -> c.flag != null && c.flag in args } ?: GcChoice.DEFAULT
        _ui.value = GameSettingsUi(p, opts, p.minMemoryMb, p.maxMemoryMb, gc, args.filter { a -> GcChoice.entries.none { it.flag == a } }.joinToString(" "),
            p.gameArgs, p.width?.toString().orEmpty(), p.height?.toString().orEmpty(), p.runtimeName, p.resolutionScale,
            withContext(Dispatchers.IO) { java.runtimes() }, p.launchVersionId?.let { withContext(Dispatchers.IO) { java.requiredJava(it) } })
    }

    fun select(id: String) = viewModelScope.launch { selected.select(id) }
    fun edit(t: (GameSettingsUi) -> GameSettingsUi) { _ui.value = t(_ui.value).copy(dirty = true) }
    fun opt(t: (GameOptions) -> GameOptions) = edit { it.copy(options = t(it.options)) }
    fun reset() = edit { it.copy(options = GameOptions()) }

    fun save() = viewModelScope.launch {
        val u = _ui.value
        val p = u.profile ?: return@launch
        runCatching {
            val w = u.width.toIntOrNull(); val h = u.height.toIntOrNull()
            require((w == null) == (h == null) && (w == null || (w in 320..7680 && h!! in 240..4320))) { str(R.string.window_size_must_be_both_empty) }
            withContext(Dispatchers.IO) { OptionsTxt.apply(storage.instance(p.gameDirName).gameDir, u.options, p.minecraftVersion) }
            val jvm = (listOfNotNull(u.gc.flag) + u.jvmArgs.split(' ').filter { it.isNotBlank() }).joinToString(" ")
            profiles.updateLaunchOptions(p.id) {
                it.copy(minMemoryMb = u.minMb, maxMemoryMb = u.maxMb, jvmArgs = jvm, gameArgs = u.gameArgs, width = w, height = h,
                    runtimeName = u.runtimeName, resolutionScale = u.resolutionScale)
            }
        }.onSuccess { _ui.value = _ui.value.copy(dirty = false); messages.info(R.string.msg_game_settings_saved) }
            .onFailure { messages.error(it.userMessage()) }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, tag: String = label, onChange: (Float) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Slider(value.coerceIn(range), onChange, valueRange = range, steps = steps, modifier = Modifier.testTag("slider_$tag"))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
fun GameSettingsScreen(padding: PaddingValues, onNavigate: (Destination) -> Unit, vm: GameSettingsViewModel = hiltViewModel()) {
    val selection by vm.selection.collectAsStateWithLifecycle()
    val u by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(selection.selected) { vm.bind(selection.selected) }
    val o = u.options
    val gfxLabels = GraphicsQuality.entries.associateWith { it.label() }
    val particleLabels = ParticleLevel.entries.associateWith { it.label() }
    val cloudLabels = CloudMode.entries.associateWith { it.label() }
    val gcDefault = stringResource(R.string.gc_default)
    val autoLabel = stringResource(R.string.label_automatic)
    LazyColumn(Modifier.padding(padding).testTag("game_settings"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ProfileSelectorBar(selection, vm::select) }
        if (u.profile == null) { item { EmptyState(Icons.Outlined.Tune, stringResource(R.string.no_profile), stringResource(R.string.game_settings_belong_to_a_java)) }; return@LazyColumn }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::save, enabled = u.dirty, modifier = Modifier.weight(1f).testTag("game_settings_save")) { Text(stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_save)) }
                OutlinedButton(onClick = vm::reset) { Text(stringResource(R.string.defaults)) }
            }
        }
        item { Text(stringResource(R.string.values_are_written_to_the_profile), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { SectionHeader(stringResource(R.string.graphics)) }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                SliderRow(stringResource(R.string.render_distance_1_s_chunks, o.renderDistance), o.renderDistance.toFloat(), 2f..32f, 29, "render") { v -> vm.opt { it.copy(renderDistance = v.roundToInt()) } }
                SliderRow(stringResource(R.string.simulation_distance_1_s_chunks, o.simulationDistance), o.simulationDistance.toFloat(), 5f..32f, 26, "sim") { v -> vm.opt { it.copy(simulationDistance = v.roundToInt()) } }
                Text(stringResource(R.string.graphics_quality), style = MaterialTheme.typography.bodyMedium)
                ChipRow(GraphicsQuality.entries, o.graphics, { gfxLabels.getValue(it) }, { g -> vm.opt { it.copy(graphics = g) } })
                SwitchRow(stringResource(R.string.vsync), o.vsync) { v -> vm.opt { it.copy(vsync = v) } }
                SliderRow(stringResource(R.string.fps_limit) + if (o.maxFps >= 260) "unlimited" else "${o.maxFps}", o.maxFps.toFloat(), 10f..260f, tag = "fps") { v -> vm.opt { it.copy(maxFps = (v / 10).roundToInt() * 10) } }
                SliderRow(stringResource(R.string.resolution_scale_1_s, (u.resolutionScale * 100).roundToInt()), u.resolutionScale, 0.25f..1f, 14, "scale") { v -> vm.edit { it.copy(resolutionScale = (v * 20).roundToInt() / 20f) } }
                SliderRow(stringResource(R.string.mipmap_levels_1_s, o.mipmapLevels), o.mipmapLevels.toFloat(), 0f..4f, 3, "mipmap") { v -> vm.opt { it.copy(mipmapLevels = v.roundToInt()) } }
                Text(stringResource(R.string.particles), style = MaterialTheme.typography.bodyMedium)
                ChipRow(ParticleLevel.entries, o.particles, { particleLabels.getValue(it) }, { p -> vm.opt { it.copy(particles = p) } })
                Text(stringResource(R.string.clouds), style = MaterialTheme.typography.bodyMedium)
                ChipRow(CloudMode.entries, o.clouds, { cloudLabels.getValue(it) }, { c -> vm.opt { it.copy(clouds = c) } })
                SwitchRow(stringResource(R.string.entity_shadows), o.entityShadows) { v -> vm.opt { it.copy(entityShadows = v) } }
                SwitchRow(stringResource(R.string.smooth_lighting), o.smoothLighting) { v -> vm.opt { it.copy(smoothLighting = v) } }
                SwitchRow(stringResource(R.string.fullscreen), o.fullscreen) { v -> vm.opt { it.copy(fullscreen = v) } }
                SliderRow(stringResource(R.string.hud_scale) + if (o.guiScale == 0) "auto" else "${o.guiScale}", o.guiScale.toFloat(), 0f..6f, 5, "gui") { v -> vm.opt { it.copy(guiScale = v.roundToInt()) } }
                SliderRow(stringResource(R.string.field_of_view_1_s, o.fov), o.fov.toFloat(), 30f..110f, tag = "fov") { v -> vm.opt { it.copy(fov = v.roundToInt()) } }
                SliderRow(stringResource(R.string.biome_blend_1_s, o.biomeBlendRadius), o.biomeBlendRadius.toFloat(), 0f..7f, 6, "blend") { v -> vm.opt { it.copy(biomeBlendRadius = v.roundToInt()) } }
                Text(stringResource(R.string.dynamic_lighting_is_provided_by_mods), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { SectionHeader(stringResource(R.string.audio_and_input)) }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                SliderRow(stringResource(R.string.master_volume_1_s, (o.masterVolume * 100).roundToInt()), o.masterVolume.toFloat(), 0f..1f, tag = "master") { v -> vm.opt { it.copy(masterVolume = (v * 100).roundToInt() / 100.0) } }
                SliderRow(stringResource(R.string.music_1_s, (o.musicVolume * 100).roundToInt()), o.musicVolume.toFloat(), 0f..1f, tag = "music") { v -> vm.opt { it.copy(musicVolume = (v * 100).roundToInt() / 100.0) } }
                SliderRow(stringResource(R.string.mouse_touch_sensitivity_1_s, (o.mouseSensitivity * 200).roundToInt()), o.mouseSensitivity.toFloat(), 0f..1f, tag = "sens") { v -> vm.opt { it.copy(mouseSensitivity = (v * 100).roundToInt() / 100.0) } }
                Text(stringResource(R.string.key_bindings_are_set_inside_minecraft), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { onNavigate(Destination.CONTROLS) }) { Text(stringResource(R.string.touch_controls_and_gamepads)) }
            }
        }
        item { SectionHeader(stringResource(R.string.java_and_memory)) }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                SliderRow(stringResource(R.string.maximum_memory_1_s_mb, u.maxMb), u.maxMb.toFloat(), 512f..12288f, tag = "max_mem") { v -> vm.edit { it.copy(maxMb = (v / 128).roundToInt() * 128, minMb = minOf(it.minMb, (v / 128).roundToInt() * 128)) } }
                SliderRow(stringResource(R.string.initial_memory_1_s_mb, u.minMb), u.minMb.toFloat(), 128f..u.maxMb.toFloat().coerceAtLeast(256f), tag = "min_mem") { v -> vm.edit { it.copy(minMb = (v / 128).roundToInt() * 128) } }
                Text(stringResource(R.string.garbage_collector), style = MaterialTheme.typography.bodyMedium)
                ChipRow(GcChoice.entries, u.gc, { if (it == GcChoice.DEFAULT) gcDefault else it.name }, { g -> vm.edit { it.copy(gc = g) } })
                OutlinedTextField(u.jvmArgs, { v -> vm.edit { it.copy(jvmArgs = v) } }, label = { Text(stringResource(R.string.extra_jvm_arguments)) }, modifier = Modifier.fillMaxWidth().testTag("jvm_args"),
                    supportingText = { Text(stringResource(R.string.agents_and_boot_class_path_options)) })
                OutlinedTextField(u.gameArgs, { v -> vm.edit { it.copy(gameArgs = v) } }, label = { Text(stringResource(R.string.extra_game_arguments)) }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(u.width, { v -> vm.edit { it.copy(width = v.filter(Char::isDigit).take(4)) } }, label = { Text(stringResource(R.string.window_width)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(u.height, { v -> vm.edit { it.copy(height = v.filter(Char::isDigit).take(4)) } }, label = { Text(stringResource(R.string.window_height)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                Text(stringResource(R.string.java_runtime) + (u.requiredJava?.let { stringResource(R.string.this_version_needs_java_1_s, it) } ?: ""), style = MaterialTheme.typography.bodyMedium)
                val names = listOf<String?>(null) + u.runtimes.map { it.home.name }
                ChipRow(names, u.runtimeName, { n -> n?.let { name -> u.runtimes.firstOrNull { it.home.name == name }?.let { str(R.string.java_1_s_2_s, it.majorVersion, it.home.name) } } ?: autoLabel }, { n -> vm.edit { it.copy(runtimeName = n) } })
                if (u.runtimes.isEmpty()) Text(stringResource(R.string.no_runtimes_installed_yet), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onNavigate(Destination.RUNTIMES) }) { Text(stringResource(R.string.manage_runtimes)) }
            }
        }
        item { Column(Modifier.padding(bottom = 24.dp)) { OutlinedButton(onClick = { onNavigate(Destination.PERFORMANCE) }) { Text(stringResource(R.string.performance_modes_and_renderers)) } } }
    }
}
