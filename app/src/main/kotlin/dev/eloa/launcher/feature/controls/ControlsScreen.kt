package dev.eloa.launcher.feature.controls

import androidx.compose.ui.res.stringResource
import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eloa.launcher.R
import dev.eloa.launcher.core.data.settings.SettingsRepository
import dev.eloa.launcher.core.designsystem.component.ChipRow
import dev.eloa.launcher.core.designsystem.component.ConfirmDialog
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.SectionHeader
import dev.eloa.launcher.core.designsystem.component.StatusChip
import dev.eloa.launcher.core.designsystem.component.StatusLevel
import dev.eloa.launcher.core.designsystem.theme.MonoStyle
import dev.eloa.launcher.domain.java.controls.ControlLayout
import dev.eloa.launcher.domain.java.controls.ControlPreset
import dev.eloa.launcher.domain.java.controls.GameKeys
import dev.eloa.launcher.domain.java.controls.GamepadProfile
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

data class InputDeviceInfo(val id: Int, val name: String, val descriptor: String, val kind: Kind, val vendorId: Int, val productId: Int) {
    enum class Kind { GAMEPAD, KEYBOARD, MOUSE }
}

fun listInputDevices(context: Context): List<InputDeviceInfo> {
    val im = context.getSystemService(InputManager::class.java) ?: return emptyList()
    return im.inputDeviceIds.toList().mapNotNull { devId ->
        val d = im.getInputDevice(devId) ?: return@mapNotNull null
        if (d.isVirtual) return@mapNotNull null
        val s = d.sources
        val kind = when {
            s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK -> InputDeviceInfo.Kind.GAMEPAD
            s and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE -> InputDeviceInfo.Kind.MOUSE
            d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC -> InputDeviceInfo.Kind.KEYBOARD
            else -> return@mapNotNull null
        }
        InputDeviceInfo(devId, d.name, d.descriptor, kind, d.vendorId, d.productId)
    }
}

@HiltViewModel
class ControlsViewModel @Inject constructor(
    private val repo: ControlsRepository,
    settings: SettingsRepository,
    private val messages: UserMessages,
) : ViewModel() {
    val layouts: StateFlow<List<ControlLayout>> = repo.layouts
    val active: StateFlow<String> = settings.settings.map { it.activeControlLayout }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val _pad = MutableStateFlow<GamepadProfile?>(null)
    val pad: StateFlow<GamepadProfile?> = _pad

    init { viewModelScope.launch { repo.load() } }

    private fun act(ok: Int?, block: suspend () -> Unit) = viewModelScope.launch { runCatching { block() }.onSuccess { ok?.let { messages.info(it) } }.onFailure { messages.error(it.userMessage()) } }
    fun create(p: ControlPreset, name: String, then: (String) -> Unit) = act(null) { then(repo.createFromPreset(p, name).name) }
    fun setActive(name: String) = act(R.string.msg_layout_active) { repo.setActive(name) }
    fun delete(name: String) = act(R.string.msg_layout_deleted) { repo.delete(name) }
    fun import(uri: android.net.Uri) = act(R.string.msg_layout_imported) { repo.import(uri) }
    fun export(name: String, uri: android.net.Uri) = act(R.string.msg_exported) { repo.export(name, uri) }

    fun loadPad(d: InputDeviceInfo) = viewModelScope.launch { _pad.value = repo.gamepad(d.descriptor, d.name) }
    fun editPad(t: (GamepadProfile) -> GamepadProfile) { _pad.value = _pad.value?.let(t) }
    fun savePad() = act(R.string.msg_gamepad_saved) { _pad.value?.let { repo.saveGamepad(it) } }
    fun resetPad() = act(R.string.msg_gamepad_reset) { _pad.value?.let { repo.resetGamepad(it.descriptor); _pad.value = it.copy(mapping = GamepadProfile.DEFAULT_MAPPING, deadzone = 0.15f, lookSensitivity = 1f, invertY = false) } }
}

@Composable
fun ControlsScreen(padding: PaddingValues, onEdit: (String) -> Unit, vm: ControlsViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.padding(padding).testTag("controls")) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text(stringResource(R.string.touch_layouts)) }, modifier = Modifier.testTag("controls_tab_touch"))
            Tab(tab == 1, { tab = 1 }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text(stringResource(R.string.gamepads_keyboards)) }, modifier = Modifier.testTag("controls_tab_pads"))
        }
        if (tab == 0) Layouts(vm, onEdit) else Devices(vm)
    }
}

@Composable
private fun Layouts(vm: ControlsViewModel, onEdit: (String) -> Unit) {
    val layouts by vm.layouts.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
    var exportName by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> val n = exportName; if (uri != null && n != null) vm.export(n, uri) }
    var delete by remember { mutableStateOf<String?>(null) }
    var preset by rememberSaveable { mutableStateOf(ControlPreset.CUSTOM) }
    val newName = LocalContext.current.getString(R.string.layout_new_name)
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.new_layout), style = MaterialTheme.typography.titleSmall)
                ChipRow(ControlPreset.entries, preset, { it.name.lowercase().replace('_', '-').replaceFirstChar { c -> c.uppercase() } }, { preset = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.create(preset, newName, onEdit) }, modifier = Modifier.testTag("layout_new")) { Text(stringResource(R.string.create_and_edit)) }
                    OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "*/*")) }) { Text(stringResource(R.string.label_import)) }
                }
                Text(stringResource(R.string.positions_are_stored_relative_to_the), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(layouts, key = { it.name }) { l ->
            LauncherCard(Modifier.fillMaxWidth().testTag("layout_item")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.name, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.s_1_s_controls_2_s, l.buttons.size, l.preset.lowercase()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (l.name == active) StatusChip(stringResource(R.string.active), StatusLevel.OK) else TextButton(onClick = { vm.setActive(l.name) }) { Text(stringResource(R.string.use)) }
                }
                Row {
                    IconButton(onClick = { onEdit(l.name) }) { Icon(Icons.Outlined.Edit, LocalContext.current.getString(R.string.action_edit)) }
                    IconButton(onClick = { exportName = l.name; exporter.launch(l.name + ".json") }) { Icon(Icons.Outlined.IosShare, LocalContext.current.getString(R.string.action_export)) }
                    IconButton(onClick = { delete = l.name }, enabled = l.name != active) { Icon(Icons.Outlined.Delete, LocalContext.current.getString(R.string.action_delete)) }
                }
            }
        }
    }
    delete?.let { n -> ConfirmDialog(stringResource(R.string.delete_layout), stringResource(R.string.s_1_s_will_be_deleted_exported, n), stringResource(R.string.action_delete), { vm.delete(n); delete = null }, { delete = null }, destructive = true) }
}

@Composable
private fun Devices(vm: ControlsViewModel) {
    val context = LocalContext.current
    val devices = remember { mutableStateListOf<InputDeviceInfo>() }
    DisposableEffect(Unit) {
        val im = context.getSystemService(InputManager::class.java)
        fun reload() { devices.clear(); devices.addAll(listInputDevices(context)) }
        reload()
        val l = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(id: Int) = reload()
            override fun onInputDeviceRemoved(id: Int) = reload()
            override fun onInputDeviceChanged(id: Int) = reload()
        }
        im.registerInputDeviceListener(l, null)
        onDispose { im.unregisterInputDeviceListener(l) }
    }
    val pad by vm.pad.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<InputDeviceInfo?>(null) }
    LaunchedEffect(selected) { selected?.takeIf { it.kind == InputDeviceInfo.Kind.GAMEPAD }?.let(vm::loadPad) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeader(stringResource(R.string.connected_devices), subtitle = stringResource(R.string.bluetooth_and_usb_gamepads_keyboards_and)) }
        if (devices.isEmpty()) item { Text(stringResource(R.string.no_external_input_devices_connected), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(devices, key = { it.id }) { d ->
            LauncherCard(Modifier.fillMaxWidth().testTag("input_device"), onClick = { selected = d }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(when (d.kind) { InputDeviceInfo.Kind.GAMEPAD -> Icons.Outlined.Gamepad; InputDeviceInfo.Kind.KEYBOARD -> Icons.Outlined.Keyboard; InputDeviceInfo.Kind.MOUSE -> Icons.Outlined.Mouse }, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, fontWeight = FontWeight.SemiBold)
                        Text("%04x:%04x".format(d.vendorId, d.productId), style = MonoStyle)
                    }
                    if (selected?.id == d.id) StatusChip(stringResource(R.string.label_selected), StatusLevel.INFO)
                }
            }
        }
        item { SectionHeader(stringResource(R.string.input_tester), subtitle = stringResource(R.string.press_buttons_or_move_sticks_events)) }
        item { InputTester() }
        val p = pad
        if (selected?.kind == InputDeviceInfo.Kind.GAMEPAD && p != null) {
            item { SectionHeader(stringResource(R.string.mapping_for_1_s, p.name)) }
            item {
                LauncherCard(Modifier.fillMaxWidth().testTag("pad_mapping")) {
                    GamepadProfile.BUTTONS.forEach { b ->
                        var open by remember { mutableStateOf(false) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(b.removePrefix("BUTTON_"), Modifier.weight(1f), style = MonoStyle)
                            Box {
                                TextButton(onClick = { open = true }) { Text(p.mapping[b] ?: "—") }
                                DropdownMenu(open, { open = false }) {
                                    GameKeys.ALL.forEach { k -> DropdownMenuItem(text = { Text(k) }, onClick = { vm.editPad { it.copy(mapping = it.mapping + (b to k)) }; open = false }) }
                                }
                            }
                        }
                    }
                    Text(stringResource(R.string.stick_dead_zone_1_s, (p.deadzone * 100).roundToInt()))
                    Slider(p.deadzone, { v -> vm.editPad { it.copy(deadzone = v) } }, valueRange = 0f..0.6f)
                    Text(stringResource(R.string.look_sensitivity_1_s, (p.lookSensitivity * 100).roundToInt()))
                    Slider(p.lookSensitivity, { v -> vm.editPad { it.copy(lookSensitivity = v) } }, valueRange = 0.1f..3f)
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.invert_y_axis), Modifier.weight(1f)); Switch(p.invertY, { v -> vm.editPad { it.copy(invertY = v) } }) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::savePad) { Text(stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_save)) }
                        OutlinedButton(onClick = vm::resetPad) { Text(stringResource(R.string.reset)) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Focusable Android view that captures key and joystick events and lists them. */
@Composable
private fun InputTester() {
    val events = remember { mutableStateListOf<String>() }
    fun add(s: String) { events.add(0, s); while (events.size > 8) events.removeAt(events.size - 1) }
    LauncherCard(Modifier.fillMaxWidth().testTag("input_tester")) {
        AndroidView(
            factory = { ctx ->
                View(ctx).apply {
                    isFocusable = true; isFocusableInTouchMode = true
                    setOnKeyListener { _, code, ev ->
                        if (ev.action == KeyEvent.ACTION_DOWN) add("${KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")} · ${ev.device?.name ?: "?"}")
                        true
                    }
                    setOnGenericMotionListener { _, ev ->
                        if (ev.isFromSource(InputDevice.SOURCE_JOYSTICK) && ev.action == MotionEvent.ACTION_MOVE) {
                            add("L(%.2f, %.2f) R(%.2f, %.2f)".format(ev.getAxisValue(MotionEvent.AXIS_X), ev.getAxisValue(MotionEvent.AXIS_Y), ev.getAxisValue(MotionEvent.AXIS_Z), ev.getAxisValue(MotionEvent.AXIS_RZ)))
                            true
                        } else false
                    }
                    post { requestFocus() }
                }
            },
            modifier = Modifier.fillMaxWidth().height(1.dp),
        )
        if (events.isEmpty()) Text(stringResource(R.string.waiting_for_input), color = MaterialTheme.colorScheme.onSurfaceVariant)
        events.forEach { Text(it, style = MonoStyle) }
        FilledTonalButton(onClick = { events.clear() }) { Text(stringResource(R.string.clear)) }
    }
}
