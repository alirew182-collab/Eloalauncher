package dev.eloa.launcher.feature.skins

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eloa.launcher.R
import dev.eloa.launcher.core.designsystem.component.ChipRow
import dev.eloa.launcher.core.designsystem.component.ConfirmDialog
import dev.eloa.launcher.core.designsystem.component.EmptyState
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.SectionHeader
import dev.eloa.launcher.core.designsystem.component.TextInputDialog
import dev.eloa.launcher.domain.java.skins.SkinImage
import dev.eloa.launcher.domain.java.skins.SkinModel
import dev.eloa.launcher.domain.java.skins.Skins
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.common.str
import dev.eloa.launcher.feature.common.userMessage
import dev.eloa.launcher.feature.java.AccountRepository
import dev.eloa.launcher.feature.java.TYPE_MSA
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Photo being turned into a skin; crop centre and size are fractions of the photo. */
data class PhotoDraft(val photo: Bitmap, val model: SkinModel = SkinModel.CLASSIC, val cropX: Float = 0.5f, val cropY: Float = 0.4f, val scale: Float = 0.8f)

@HiltViewModel
class SkinsViewModel @Inject constructor(
    private val repo: SkinsRepository,
    accounts: AccountRepository,
    private val messages: UserMessages,
) : ViewModel() {
    val skins: StateFlow<List<SkinEntry>> = repo.skins
    val hasMicrosoft: StateFlow<Boolean> = accounts.accounts.map { l -> l.any { it.type == TYPE_MSA } }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val _draft = MutableStateFlow<PhotoDraft?>(null)
    val draft: StateFlow<PhotoDraft?> = _draft
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    init { viewModelScope.launch { repo.load() } }

    fun preview(id: String): SkinImage? = runCatching { repo.image(id) }.getOrNull()

    private fun act(ok: Int?, block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = true
        runCatching { block() }.onSuccess { ok?.let { messages.info(it) } }.onFailure { messages.error(it.userMessage()) }
        _busy.value = false
    }

    /** Imports a skin file; a picture that is not a skin opens the photo builder instead. */
    fun import(uri: android.net.Uri) = viewModelScope.launch {
        try { repo.importPng(uri); messages.info(R.string.msg_skin_imported) }
        catch (e: NotASkinException) { messages.info(R.string.msg_not_a_skin_using_photo); openPhoto(uri) }
        catch (e: Exception) { messages.error(e.userMessage()) }
    }

    fun openPhoto(uri: android.net.Uri) = viewModelScope.launch {
        runCatching { repo.loadPhoto(uri) }.onSuccess { _draft.value = PhotoDraft(it) }.onFailure { messages.error(it.userMessage()) }
    }
    fun editDraft(t: (PhotoDraft) -> PhotoDraft) { _draft.value = _draft.value?.let(t) }
    fun closeDraft() { _draft.value = null }
    fun draftSkin(d: PhotoDraft): SkinImage = repo.skinFromPhoto(d.photo, d.model, d.cropX, d.cropY, d.scale)
    fun saveDraft(name: String, onSaved: (String) -> Unit) {
        val d = _draft.value ?: return
        act(R.string.msg_skin_saved) { val e = repo.save(draftSkin(d), name, d.model, source = "PHOTO"); _draft.value = null; onSaved(e.id) }
    }

    fun newBlank(name: String, model: SkinModel, onCreated: (String) -> Unit) = act(null) { onCreated(repo.save(Skins.blank(model), name, model, source = "EDITOR").id) }
    fun rename(id: String, name: String) = act(null) { repo.rename(id, name) }
    fun delete(id: String) = act(R.string.msg_skin_deleted) { repo.delete(id) }
    fun export(id: String, uri: android.net.Uri) = act(R.string.msg_exported) { repo.exportPng(id, uri) }
    fun upload(id: String) = act(R.string.msg_skin_uploaded) { repo.uploadToMicrosoft(id) }
    fun bedrock(id: String, launch: (Intent) -> Unit) = viewModelScope.launch {
        runCatching { repo.bedrockPackIntent(id) }.onSuccess { i -> runCatching { launch(i) }.onFailure { messages.error(R.string.msg_minecraft_missing) } }
            .onFailure { messages.error(it.userMessage()) }
    }
}

/** Pixel-exact rendering of a small texture (no smoothing). */
@Composable
fun SkinPicture(img: SkinImage?, modifier: Modifier = Modifier) {
    val bmp = remember(img) { img?.toBitmap()?.asImageBitmap() }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, Modifier.padding(6.dp).fillMaxWidth(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
    }
}

@Composable
fun SkinsScreen(padding: PaddingValues, onEdit: (String) -> Unit, vm: SkinsViewModel = hiltViewModel()) {
    val skins by vm.skins.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val hasMs by vm.hasMicrosoft.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::openPhoto) }
    var selected by remember { mutableStateOf<SkinEntry?>(null) }
    val defaultName = stringResource(R.string.skin_default_name)

    LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.padding(padding).testTag("skins"), contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            LauncherCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.skins_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.newBlank(defaultName, SkinModel.CLASSIC, onEdit) }, modifier = Modifier.weight(1f).testTag("skin_new")) { Text(stringResource(R.string.skin_draw_new), maxLines = 1) }
                    FilledTonalButton(onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f).testTag("skin_photo")) {
                        Text(stringResource(R.string.skin_from_photo), maxLines = 1)
                    }
                }
                OutlinedButton(onClick = { filePicker.launch(arrayOf("image/png", "image/*")) }, modifier = Modifier.fillMaxWidth().testTag("skin_import")) { Text(stringResource(R.string.skin_import_png)) }
            }
        }
        if (skins.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(Icons.Outlined.Checkroom, stringResource(R.string.skins_empty_title), stringResource(R.string.skins_empty_body)) }
        items(skins, key = { it.id }) { s ->
            val img = remember(s.id, s.createdAt) { vm.preview(s.id) }
            LauncherCard(Modifier.fillMaxWidth().testTag("skin_item"), onClick = { selected = s }) {
                SkinPicture(img?.let { Skins.preview(it, s.model) }, Modifier.fillMaxWidth().aspectRatio(0.7f))
                Spacer(Modifier.height(8.dp))
                Text(s.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(if (s.model == SkinModel.SLIM) R.string.skin_model_slim else R.string.skin_model_classic), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                SectionHeader(stringResource(R.string.skins_ely_title))
                Text(stringResource(R.string.skins_ely_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://ely.by/skins".toUri())) } }) { Text(stringResource(R.string.skins_open_ely)) }
            }
        }
    }

    selected?.let { s -> SkinActions(s, hasMs, vm, onEdit) { selected = null } }
    draft?.let { d -> PhotoBuilder(d, vm, onSaved = onEdit) }
}

@Composable
private fun SkinActions(s: SkinEntry, hasMs: Boolean, vm: SkinsViewModel, onEdit: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { it?.let { u -> vm.export(s.id, u) } }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var upload by remember { mutableStateOf(false) }
    val img = remember(s.id) { vm.preview(s.id) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("skin_sheet")) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.name, style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SkinPicture(img?.let { Skins.preview(it, s.model) }, Modifier.weight(1f).aspectRatio(0.5f))
                SkinPicture(img?.let { Skins.preview(it, s.model, back = true) }, Modifier.weight(1f).aspectRatio(0.5f))
                SkinPicture(img, Modifier.weight(1f).aspectRatio(1f))
            }
            Button(onClick = { onDismiss(); onEdit(s.id) }, modifier = Modifier.fillMaxWidth().testTag("skin_edit")) { Text(stringResource(R.string.action_edit)) }
            FilledTonalButton(onClick = { upload = true }, enabled = hasMs, modifier = Modifier.fillMaxWidth().testTag("skin_upload")) { Text(stringResource(R.string.skin_upload_ms)) }
            if (!hasMs) Text(stringResource(R.string.skin_upload_needs_ms), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { vm.bedrock(s.id) { context.startActivity(it) } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.skin_bedrock_pack)) }
            OutlinedButton(onClick = { exporter.launch(s.name + ".png") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.skin_export_png)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { rename = true }) { Text(stringResource(R.string.action_rename)) }
                TextButton(onClick = { delete = true }, modifier = Modifier.testTag("skin_delete")) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (rename) TextInputDialog(stringResource(R.string.action_rename), stringResource(R.string.name), s.name, { vm.rename(s.id, it); rename = false }, { rename = false })
    if (delete) ConfirmDialog(stringResource(R.string.delete_1_s_2, s.name), stringResource(R.string.skin_delete_body), stringResource(R.string.action_delete),
        { vm.delete(s.id); delete = false; onDismiss() }, { delete = false }, destructive = true)
    if (upload) ConfirmDialog(stringResource(R.string.skin_upload_ms), stringResource(R.string.skin_upload_confirm), stringResource(R.string.skin_upload_action),
        { vm.upload(s.id); upload = false }, { upload = false })
}

@Composable
private fun PhotoBuilder(d: PhotoDraft, vm: SkinsViewModel, onSaved: (String) -> Unit) {
    val photo = remember(d.photo) { d.photo.asImageBitmap() }
    val skin = remember(d) { vm.draftSkin(d) }
    var name by remember { mutableStateOf(str(R.string.skin_photo_default_name)) }
    val modelLabels = mapOf(SkinModel.CLASSIC to stringResource(R.string.skin_model_classic), SkinModel.SLIM to stringResource(R.string.skin_model_slim))
    ModalBottomSheet(onDismissRequest = vm::closeDraft, modifier = Modifier.testTag("photo_builder")) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.skin_from_photo), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.skin_photo_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1.4f).aspectRatio(d.photo.width.toFloat() / d.photo.height)) {
                    Image(photo, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                    val accent = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.matchParentSize()) {
                        val side = minOf(size.width, size.height) * d.scale
                        val left = (size.width * d.cropX - side / 2).coerceIn(0f, size.width - side)
                        val top = (size.height * d.cropY - side / 2).coerceIn(0f, size.height - side)
                        drawRect(Color.Black.copy(alpha = 0.35f))
                        drawRect(accent, Offset(left, top), Size(side, side), style = Stroke(3f))
                    }
                }
                SkinPicture(Skins.preview(skin, d.model), Modifier.weight(1f).aspectRatio(0.5f))
            }
            Text(stringResource(R.string.skin_crop_horizontal)); Slider(d.cropX, { v -> vm.editDraft { it.copy(cropX = v) } }, valueRange = 0f..1f)
            Text(stringResource(R.string.skin_crop_vertical)); Slider(d.cropY, { v -> vm.editDraft { it.copy(cropY = v) } }, valueRange = 0f..1f)
            Text(stringResource(R.string.skin_crop_size)); Slider(d.scale, { v -> vm.editDraft { it.copy(scale = v) } }, valueRange = 0.1f..1f)
            ChipRow(SkinModel.entries, d.model, { modelLabels.getValue(it) }, { m -> vm.editDraft { it.copy(model = m) } })
            androidx.compose.material3.OutlinedTextField(name, { name = it.take(40) }, label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.saveDraft(name, onSaved) }, modifier = Modifier.weight(1f).testTag("photo_save")) { Text(stringResource(R.string.skin_save_and_edit)) }
                TextButton(onClick = vm::closeDraft) { Text(stringResource(dev.eloa.launcher.core.designsystem.R.string.ds_cancel)) }
            }
        }
    }
}
