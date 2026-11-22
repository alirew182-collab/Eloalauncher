package dev.eloa.launcher.feature.controls

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eloa.launcher.R
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.domain.java.controls.ControlButton
import dev.eloa.launcher.domain.java.controls.ControlKind
import dev.eloa.launcher.domain.java.controls.ControlLayout
import dev.eloa.launcher.domain.java.controls.ControlLayouts
import dev.eloa.launcher.domain.java.controls.ControlShape
import dev.eloa.launcher.domain.java.controls.GameKeys
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@HiltViewModel
class ControlEditorViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: ControlsRepository,
    private val messages: UserMessages,
) : ViewModel() {
    private val originalName: String = handle.get<String>("name").orEmpty()
    private val _layout = MutableStateFlow<ControlLayout?>(null)
    val layout: StateFlow<ControlLayout?> = _layout
    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected

    init { viewModelScope.launch { _layout.value = repo.load().firstOrNull { it.name == originalName } } }

    fun select(id: String?) { _selected.value = id }
    fun edit(t: (ControlLayout) -> ControlLayout) { _layout.value = _layout.value?.let(t) }
    fun editButton(id: String, t: (ControlButton) -> ControlButton) = edit { l -> l.copy(buttons = l.buttons.map { if (it.id == id) t(it) else it }) }
    fun add() = edit { l ->
        val b = ControlButton("b" + UUID.randomUUID().toString().take(6), "?", listOf("KEY_E"), 0.5f, 0.5f)
        _selected.value = b.id
        l.copy(buttons = l.buttons + b)
    }
    fun remove(id: String) { edit { l -> l.copy(buttons = l.buttons.filterNot { it.id == id }) }; _selected.value = null }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val l = _layout.value ?: return@launch
        runCatching { repo.save(l, originalName) }.onSuccess { messages.info(R.string.msg_layout_saved); onDone() }.onFailure { messages.error(it.userMessage()) }
    }
}

@Composable
fun ControlEditorScreen(padding: PaddingValues, onDone: () -> Unit, vm: ControlEditorViewModel = hiltViewModel()) {
    val layout by vm.layout.collectAsStateWithLifecycle()
    val selectedId by vm.selected.collectAsStateWithLifecycle()
    val l = layout
    if (l == null) { Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.layout_not_found)) }; return }
    val cfg = LocalConfiguration.current
    val aspect = max(cfg.screenWidthDp, cfg.screenHeightDp).toFloat() / min(cfg.screenWidthDp, cfg.screenHeightDp).coerceAtLeast(1)
    LazyColumn(Modifier.padding(padding).testTag("control_editor"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            val ratio = "%.2f".format(aspect)
            Text(stringResource(R.string.preview_in_landscape_1_s_1, ratio), style = MaterialTheme.typography.bodySmall)
        }
        item { Canvas(l, selectedId, aspect, vm) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.save(onDone) }, modifier = Modifier.testTag("layout_save")) { Text(stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_save)) }
                OutlinedButton(onClick = vm::add, enabled = l.buttons.size < ControlLayouts.MAX_BUTTONS, modifier = Modifier.testTag("layout_add")) { Text(stringResource(R.string.add_button)) }
                TextButton(onClick = onDone) { Text(stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_cancel)) }
            }
        }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                OutlinedTextField(l.name, { v -> vm.edit { it.copy(name = v.take(60)) } }, label = { Text(stringResource(R.string.layout_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.size_of_all_controls_1_s, (l.scale * 100).roundToInt()))
                Slider(l.scale, { v -> vm.edit { it.copy(scale = v) } }, valueRange = 0.5f..2f)
                Text(stringResource(R.string.opacity_of_all_controls_1_s, (l.globalOpacity * 100).roundToInt()))
                Slider(l.globalOpacity, { v -> vm.edit { it.copy(globalOpacity = v) } }, valueRange = 0.1f..1f)
                Text(stringResource(R.string.camera_sensitivity_1_s, (l.lookSensitivity * 100).roundToInt()))
                Slider(l.lookSensitivity, { v -> vm.edit { it.copy(lookSensitivity = v) } }, valueRange = 0.1f..3f)
            }
        }
        l.buttons.firstOrNull { it.id == selectedId }?.let { b -> item { ButtonEditor(b, vm) } }
    }
}

@Composable
private fun Canvas(l: ControlLayout, selectedId: String?, aspect: Float, vm: ControlEditorViewModel) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().aspectRatio(aspect).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
            .pointerInput(Unit) { detectTapGestures { vm.select(null) } }.testTag("layout_canvas"),
    ) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        // Controls are sized in dp for the real screen; scale them to this preview.
        val realW = with(density) { max(LocalConfiguration.current.screenWidthDp, LocalConfiguration.current.screenHeightDp).dp.toPx() }
        val previewDensity = density.density * (wPx / realW)
        l.buttons.forEach { b ->
            val r = ControlLayouts.place(b, l.scale, wPx, hPx, previewDensity)
            val sel = b.id == selectedId
            Box(
                Modifier.offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                    .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
                    .alpha((b.opacity * l.globalOpacity).coerceIn(0.15f, 1f))
                    .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer, if (b.shape == ControlShape.ROUND || b.kind == ControlKind.JOYSTICK) CircleShape else RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, if (b.shape == ControlShape.ROUND || b.kind == ControlKind.JOYSTICK) CircleShape else RoundedCornerShape(10.dp))
                    .pointerInput(b.id, wPx, hPx) {
                        detectDragGestures(onDragStart = { vm.select(b.id) }) { change, drag ->
                            change.consume()
                            vm.editButton(b.id) { cur -> cur.copy(x = (cur.x + drag.x / wPx).coerceIn(0f, 1f), y = (cur.y + drag.y / hPx).coerceIn(0f, 1f)) }
                        }
                    }
                    .pointerInput(b.id) { detectTapGestures { vm.select(b.id) } }
                    .testTag("ctl_${b.id}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (b.kind == ControlKind.JOYSTICK) "✥" else b.label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
                    color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun ButtonEditor(b: ControlButton, vm: ControlEditorViewModel) {
    LauncherCard(Modifier.fillMaxWidth().testTag("button_editor")) {
        Text(if (b.kind == ControlKind.JOYSTICK) stringResource(R.string.movement_joystick) else stringResource(R.string.button), style = MaterialTheme.typography.titleSmall)
        if (b.kind == ControlKind.BUTTON) {
            OutlinedTextField(b.label, { v -> vm.editButton(b.id) { it.copy(label = v.take(12)) } }, label = { Text(stringResource(R.string.label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            var open by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.action), Modifier.padding(end = 8.dp))
                Box {
                    TextButton(onClick = { open = true }) { Text(b.keys.joinToString(" + ")) }
                    DropdownMenu(open, { open = false }) {
                        GameKeys.ALL.forEach { k -> DropdownMenuItem(text = { Text(k) }, onClick = { vm.editButton(b.id) { it.copy(keys = listOf(k)) }; open = false }) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.toggle_stays_pressed), Modifier.weight(1f)); Switch(b.toggle, { v -> vm.editButton(b.id) { it.copy(toggle = v) } }) }
            Text(stringResource(R.string.width_1_s, (b.widthFactor * 100).roundToInt()))
            Slider(b.widthFactor, { v -> vm.editButton(b.id) { it.copy(widthFactor = v, shape = if (v > 1.05f) ControlShape.ROUNDED_RECT else it.shape) } }, valueRange = 0.5f..4f)
        }
        Text(stringResource(R.string.size_1_s_dp, b.sizeDp.roundToInt()))
        Slider(b.sizeDp, { v -> vm.editButton(b.id) { it.copy(sizeDp = v) } }, valueRange = 24f..220f)
        Text(stringResource(R.string.opacity_1_s, (b.opacity * 100).roundToInt()))
        Slider(b.opacity, { v -> vm.editButton(b.id) { it.copy(opacity = v) } }, valueRange = 0.05f..1f)
        Column { TextButton(onClick = { vm.remove(b.id) }) { Text(stringResource(R.string.remove_this_control), color = MaterialTheme.colorScheme.error) } }
    }
}
