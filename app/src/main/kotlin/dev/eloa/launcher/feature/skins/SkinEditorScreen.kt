package dev.eloa.launcher.feature.skins

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eloa.launcher.R
import dev.eloa.launcher.core.designsystem.component.ChipRow
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.domain.java.skins.Face
import dev.eloa.launcher.domain.java.skins.SkinImage
import dev.eloa.launcher.domain.java.skins.SkinModel
import dev.eloa.launcher.domain.java.skins.Skins
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SkinTool { PENCIL, ERASER, FILL, PICKER }

data class EditorState(
    val img: SkinImage? = null,
    val version: Int = 0,
    val name: String = "",
    val model: SkinModel = SkinModel.CLASSIC,
    val part: String = "head",
    val face: Face = Face.FRONT,
    val tool: SkinTool = SkinTool.PENCIL,
    val color: Int = 0xFF2F7D6D.toInt(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val dirty: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SkinEditorViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: SkinsRepository,
    private val messages: UserMessages,
) : ViewModel() {
    private val id: String = handle.get<String>("id").orEmpty()
    private val _s = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _s
    private val undo = ArrayDeque<IntArray>()
    private val redo = ArrayDeque<IntArray>()

    init {
        viewModelScope.launch {
            val e = repo.load().firstOrNull { it.id == id }
            _s.value = if (e == null) EditorState(error = "not_found")
            else runCatching { EditorState(img = repo.image(id), name = e.name, model = e.model) }.getOrElse { EditorState(error = it.userMessage()) }
        }
    }

    private fun publish(img: SkinImage, dirty: Boolean = true) {
        _s.value = _s.value.copy(img = img, version = _s.value.version + 1, canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty(), dirty = dirty || _s.value.dirty)
    }

    /** Call once at the start of a stroke or tap so the whole stroke undoes in one step. */
    fun beginStroke() {
        val img = _s.value.img ?: return
        undo.addLast(img.pixels.copyOf()); if (undo.size > 60) undo.removeFirst()
        redo.clear()
    }

    fun apply(x: Int, y: Int) {
        val st = _s.value
        val img = st.img ?: return
        if (!img.inBounds(x, y)) return
        when (st.tool) {
            SkinTool.PENCIL -> { if (img[x, y] == st.color) return; img[x, y] = st.color }
            SkinTool.ERASER -> { if (img[x, y] == 0) return; img[x, y] = 0 }
            SkinTool.FILL -> if (!Skins.fill(img, st.model, x, y, st.color)) return
            SkinTool.PICKER -> { val c = img[x, y]; if (Skins.alpha(c) > 0) _s.value = st.copy(color = c, tool = SkinTool.PENCIL); return }
        }
        publish(img)
    }

    fun undo() { val img = _s.value.img ?: return; val prev = undo.removeLastOrNull() ?: return; redo.addLast(img.pixels.copyOf()); prev.copyInto(img.pixels); publish(img) }
    fun redo() { val img = _s.value.img ?: return; val next = redo.removeLastOrNull() ?: return; undo.addLast(img.pixels.copyOf()); next.copyInto(img.pixels); publish(img) }

    fun set(t: (EditorState) -> EditorState) { _s.value = t(_s.value) }

    fun save(onDone: () -> Unit) {
        val st = _s.value
        val img = st.img ?: return
        viewModelScope.launch {
            val snapshot = img.copy() // drawing can continue while the file is written
            runCatching { repo.save(snapshot, st.name, st.model, id) }
                .onSuccess { _s.value = _s.value.copy(dirty = false); messages.info(R.string.msg_skin_saved); onDone() }
                .onFailure { messages.error(it.userMessage()) }
        }
    }
}

private val PALETTE = listOf(
    0xFF1B1B1B, 0xFF4A4A4A, 0xFF8C8C8C, 0xFFD6D6D6, 0xFFFFFFFF, 0xFF5A3A22, 0xFF8A5A3B, 0xFFC69680, 0xFFE8C3A6, 0xFFF2D9C7,
    0xFF7A1F1F, 0xFFC0392B, 0xFFE67E22, 0xFFF1C40F, 0xFF2E7D32, 0xFF66BB6A, 0xFF1F6F8B, 0xFF2F7D6D, 0xFF34466B, 0xFF6C3483,
).map { it.toInt() }

@Composable
fun SkinEditorScreen(padding: PaddingValues, onDone: () -> Unit, vm: SkinEditorViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val img = st.img
    if (img == null) {
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (st.error != null) stringResource(R.string.skin_not_found) else stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_loading))
        }
        return
    }
    val partLabels = Skins.parts(st.model).associate { it.key to partLabel(it.key) }
    val faceLabels = Face.entries.associateWith { faceLabel(it) }
    val modelLabels = mapOf(SkinModel.CLASSIC to stringResource(R.string.skin_model_classic), SkinModel.SLIM to stringResource(R.string.skin_model_slim))
    val part = Skins.parts(st.model).first { it.key == st.part }
    val rect = part.face(st.face)

    LazyColumn(Modifier.padding(padding).testTag("skin_editor"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val front = remember(st.version, st.model) { Skins.preview(img, st.model) }
                val back = remember(st.version, st.model) { Skins.preview(img, st.model, back = true) }
                SkinPicture(front, Modifier.weight(1f).aspectRatio(0.5f))
                SkinPicture(back, Modifier.weight(1f).aspectRatio(0.5f))
                androidx.compose.foundation.layout.Column(Modifier.weight(1.6f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(st.name, { v -> vm.set { it.copy(name = v.take(40), dirty = true) } }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                    ChipRow(SkinModel.entries, st.model, { modelLabels.getValue(it) }, { m -> vm.set { it.copy(model = m, dirty = true) } })
                    Button(onClick = { vm.save(onDone) }, modifier = Modifier.fillMaxWidth().testTag("skin_save")) { Text(stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_save)) }
                }
            }
        }
        item { ChipRow(Skins.parts(st.model).map { it.key }, st.part, { partLabels.getValue(it) }, { p -> vm.set { it.copy(part = p) } }) }
        item { ChipRow(Face.entries, st.face, { faceLabels.getValue(it) }, { f -> vm.set { it.copy(face = f) } }) }
        item {
            val v = st.version
            Box(Modifier.fillMaxWidth().heightIn(max = 420.dp).aspectRatio(rect.w.toFloat() / rect.h, matchHeightConstraintsFirst = rect.h > rect.w)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant).testTag("skin_canvas")) {
                val grid = MaterialTheme.colorScheme.outlineVariant
                Canvas(Modifier.fillMaxSize()
                    .pointerInput(rect, st.tool, st.color) {
                        fun cell(o: Offset) = (rect.x + (o.x / size.width * rect.w).toInt().coerceIn(0, rect.w - 1)) to (rect.y + (o.y / size.height * rect.h).toInt().coerceIn(0, rect.h - 1))
                        detectTapGestures { o -> vm.beginStroke(); val (x, y) = cell(o); vm.apply(x, y) }
                    }
                    .pointerInput(rect, st.tool, st.color) {
                        fun cell(o: Offset) = (rect.x + (o.x / size.width * rect.w).toInt().coerceIn(0, rect.w - 1)) to (rect.y + (o.y / size.height * rect.h).toInt().coerceIn(0, rect.h - 1))
                        detectDragGestures(onDragStart = { o -> vm.beginStroke(); val (x, y) = cell(o); vm.apply(x, y) }) { change, _ ->
                            change.consume(); val (x, y) = cell(change.position); vm.apply(x, y)
                        }
                    }) {
                    if (v < 0) return@Canvas // redraw whenever the texture version changes
                    val cw = size.width / rect.w; val ch = size.height / rect.h
                    for (yy in 0 until rect.h) for (xx in 0 until rect.w) {
                        val c = img[rect.x + xx, rect.y + yy]
                        val tl = Offset(xx * cw, yy * ch)
                        if (Skins.alpha(c) < 255) {
                            // Checkerboard shows transparency (only meaningful on overlay layers).
                            drawRect(if ((xx + yy) % 2 == 0) Color(0xFF3A3A3A) else Color(0xFF2A2A2A), tl, Size(cw, ch))
                        }
                        if (Skins.alpha(c) > 0) drawRect(Color(c), tl, Size(cw, ch))
                    }
                    for (xx in 0..rect.w) drawLine(grid, Offset(xx * cw, 0f), Offset(xx * cw, size.height), 1f)
                    for (yy in 0..rect.h) drawLine(grid, Offset(0f, yy * ch), Offset(size.width, yy * ch), 1f)
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ToolButton(SkinTool.PENCIL, st.tool, Icons.Outlined.Brush, stringResource(R.string.tool_pencil), vm)
                ToolButton(SkinTool.ERASER, st.tool, Icons.Outlined.CleaningServices, stringResource(R.string.tool_eraser), vm)
                ToolButton(SkinTool.FILL, st.tool, Icons.Outlined.FormatColorFill, stringResource(R.string.tool_fill), vm)
                ToolButton(SkinTool.PICKER, st.tool, Icons.Outlined.Colorize, stringResource(R.string.tool_picker), vm)
                IconButton(onClick = vm::undo, enabled = st.canUndo, modifier = Modifier.testTag("skin_undo")) { Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.tool_undo)) }
                IconButton(onClick = vm::redo, enabled = st.canRedo) { Icon(Icons.AutoMirrored.Outlined.Redo, stringResource(R.string.tool_redo)) }
            }
        }
        item {
            LauncherCard(Modifier.fillMaxWidth()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PALETTE.forEach { c ->
                        Box(Modifier.size(32.dp).background(Color(c), RoundedCornerShape(6.dp))
                            .border(if (c == st.color) 2.dp else 1.dp, if (c == st.color) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                            .clickable { vm.set { it.copy(color = c, tool = if (it.tool == SkinTool.ERASER || it.tool == SkinTool.PICKER) SkinTool.PENCIL else it.tool) } })
                    }
                }
                var hex by remember(st.color) { mutableStateOf("%06X".format(st.color and 0xFFFFFF)) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(40.dp).background(Color(st.color), RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)))
                    OutlinedTextField(hex, { v -> hex = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6) }, label = { Text(stringResource(R.string.skin_hex_colour)) },
                        singleLine = true, prefix = { Text("#") }, modifier = Modifier.weight(1f).testTag("skin_hex"))
                    TextButton(onClick = { hex.toIntOrNull(16)?.takeIf { hex.length == 6 }?.let { c -> vm.set { it.copy(color = (0xFF shl 24) or c, tool = SkinTool.PENCIL) } } }, enabled = hex.length == 6) {
                        Text(stringResource(R.string.apply))
                    }
                }
                Text(stringResource(if (part.overlay) R.string.skin_overlay_hint else R.string.skin_base_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            // Whole texture for orientation; tapping a texel jumps to its part.
            val v = st.version
            androidx.compose.foundation.layout.Column {
                Text(stringResource(R.string.skin_full_texture), style = MaterialTheme.typography.titleSmall)
                Canvas(Modifier.fillMaxWidth(0.6f).aspectRatio(1f).padding(top = 6.dp)
                    .pointerInput(st.model) {
                        detectTapGestures { o ->
                            val x = (o.x / size.width * 64).toInt(); val y = (o.y / size.height * 64).toInt()
                            Skins.parts(st.model).firstOrNull { p -> p.faces.any { (x to y) in it } }?.let { p ->
                                val f = Face.entries.first { (x to y) in p.face(it) }
                                vm.set { it.copy(part = p.key, face = f) }
                            }
                        }
                    }) {
                    if (v < 0) return@Canvas
                    val c = size.width / 64f
                    drawRect(Color(0xFF242424))
                    for (y in 0 until 64) for (x in 0 until 64) { val px = img[x, y]; if (Skins.alpha(px) > 0) drawRect(Color(px), Offset(x * c, y * c), Size(c, c)) }
                    drawRect(Color.White, Offset(rect.x * c, rect.y * c), Size(rect.w * c, rect.h * c), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                }
            }
        }
    }
}

@Composable
private fun ToolButton(tool: SkinTool, current: SkinTool, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, vm: SkinEditorViewModel) {
    FilledIconToggleButton(checked = tool == current, onCheckedChange = { vm.set { it.copy(tool = tool) } }, modifier = Modifier.testTag("tool_${tool.name}")) { Icon(icon, label) }
}

@Composable
private fun partLabel(key: String): String = stringResource(when (key) {
    "head" -> R.string.part_head; "hat" -> R.string.part_hat; "body" -> R.string.part_body; "jacket" -> R.string.part_jacket
    "right_arm" -> R.string.part_right_arm; "right_sleeve" -> R.string.part_right_sleeve; "left_arm" -> R.string.part_left_arm; "left_sleeve" -> R.string.part_left_sleeve
    "right_leg" -> R.string.part_right_leg; "right_pants" -> R.string.part_right_pants; "left_leg" -> R.string.part_left_leg; else -> R.string.part_left_pants
})

@Composable
private fun faceLabel(f: Face): String = stringResource(when (f) {
    Face.FRONT -> R.string.face_front; Face.BACK -> R.string.face_back; Face.LEFT -> R.string.face_left
    Face.RIGHT -> R.string.face_right; Face.TOP -> R.string.face_top; Face.BOTTOM -> R.string.face_bottom
})
