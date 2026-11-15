package dev.eloa.launcher.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.eloa.launcher.core.designsystem.theme.LocalLauncherExtras

enum class StatusLevel { OK, INFO, WARNING, ERROR }

@Composable
fun statusColor(level: StatusLevel): Color {
    val x = LocalLauncherExtras.current
    return when (level) {
        StatusLevel.OK -> x.success
        StatusLevel.INFO -> MaterialTheme.colorScheme.primary
        StatusLevel.WARNING -> x.warning
        StatusLevel.ERROR -> x.danger
    }
}

fun statusIcon(level: StatusLevel): ImageVector = when (level) {
    StatusLevel.OK -> Icons.Outlined.CheckCircle
    StatusLevel.INFO -> Icons.Outlined.Info
    StatusLevel.WARNING -> Icons.Outlined.WarningAmber
    StatusLevel.ERROR -> Icons.Outlined.ErrorOutline
}

@Composable
fun StatusChip(text: String, level: StatusLevel, modifier: Modifier = Modifier) {
    val c = statusColor(level)
    Row(
        modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(statusIcon(level), null, tint = c, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Elevated card with a subtle gradient; used across the launcher. */
@Composable
fun LauncherCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val x = LocalLauncherExtras.current
    val shape = RoundedCornerShape(20.dp)
    val inner = @Composable {
        Column(
            Modifier.background(Brush.verticalGradient(x.cardGradient)).border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape).padding(contentPadding),
            content = content,
        )
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = CardDefaults.cardColors(containerColor = Color.Transparent)) { inner() }
    } else {
        Card(modifier = modifier, shape = shape, colors = CardDefaults.cardColors(containerColor = Color.Transparent)) { inner() }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke(this)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp).testTag("empty_state"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null, onDismiss: (() -> Unit)? = null) {
    val c = LocalLauncherExtras.current.danger
    Surface(modifier.fillMaxWidth().testTag("error_banner"), shape = RoundedCornerShape(16.dp), color = c.copy(alpha = 0.12f)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = c)
            Spacer(Modifier.width(12.dp))
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry") }
            if (onDismiss != null) IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Dismiss") }
        }
    }
}

@Composable
fun shimmerBrush(): Brush {
    val reduce = LocalLauncherExtras.current.reduceMotion
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val hi = MaterialTheme.colorScheme.surfaceContainerLow
    if (reduce) return Brush.linearGradient(listOf(base, base))
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(0f, 1000f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "x")
    return Brush.linearGradient(listOf(base, hi, base), start = androidx.compose.ui.geometry.Offset(x - 300f, 0f), end = androidx.compose.ui.geometry.Offset(x, 300f))
}

@Composable
fun SkeletonBox(modifier: Modifier = Modifier, height: Dp = 16.dp) {
    Box(modifier.height(height).clip(RoundedCornerShape(8.dp)).background(shimmerBrush()))
}

@Composable
fun SkeletonList(rows: Int = 5, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("skeleton").semantics { contentDescription = "Loading" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonBox(Modifier.size(48.dp), 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonBox(Modifier.fillMaxWidth(0.6f))
                    SkeletonBox(Modifier.fillMaxWidth(0.9f), 12.dp)
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String = "Cancel",
) {
    AlertDialog(
        modifier = Modifier.testTag("confirm_dialog"),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.testTag("confirm_button"),
                colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    validate: (String) -> String? = { if (it.isBlank()) "Must not be empty" else null },
    confirmLabel: String = "Save",
    numeric: Boolean = false,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val error = validate(text)
    AlertDialog(
        modifier = Modifier.testTag("input_dialog"),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true,
                isError = error != null, supportingText = { error?.let { Text(it) } },
                keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth().testTag("input_field"),
            )
        },
        confirmButton = { Button(onClick = { onConfirm(text.trim()) }, enabled = error == null, modifier = Modifier.testTag("input_confirm")) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Search") {
    OutlinedTextField(
        value = query, onValueChange = onQueryChange, singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Outlined.Close, "Clear search") } },
        placeholder = { Text(placeholder) }, shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().testTag("search_field"),
    )
}

@Composable
fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(0.42f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.58f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun MetricTile(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier, level: StatusLevel? = null) {
    LauncherCard(modifier, contentPadding = PaddingValues(14.dp)) {
        Icon(icon, null, tint = level?.let { statusColor(it) } ?: MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(10.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Big gradient call-to-action used for "PLAY". */
@Composable
fun LaunchButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val p = MaterialTheme.colorScheme.primary
    val brush = Brush.horizontalGradient(listOf(p, androidx.compose.ui.graphics.lerp(p, MaterialTheme.colorScheme.tertiary, 0.6f)))
    Box(
        modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(18.dp))
            .background(if (enabled) brush else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant)))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 28.dp, vertical = 14.dp).testTag("launch_button"),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(10.dp)) }
            Text(text, style = MaterialTheme.typography.titleMedium, color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Black)
        }
    }
}

data class ProgressInfo(val title: String, val fraction: Float?, val detail: String?, val cancellable: Boolean = true)

@Composable
fun ProgressPanel(info: ProgressInfo?, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(info != null, enter = fadeIn(), exit = fadeOut()) {
        if (info == null) return@AnimatedVisibility
        LauncherCard(modifier.fillMaxWidth().testTag("progress_panel")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(info.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (info.cancellable) OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
            Spacer(Modifier.height(10.dp))
            if (info.fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { info.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            info.detail?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Horizontal usage bar (storage / memory). */
@Composable
fun UsageBar(fraction: Float, modifier: Modifier = Modifier) {
    val level = when { fraction > 0.9f -> StatusLevel.ERROR; fraction > 0.75f -> StatusLevel.WARNING; else -> StatusLevel.OK }
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(50)).background(statusColor(level)))
    }
}

@Composable
fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), content = content)
}

@Composable
fun rememberToggle(initial: Boolean = false) = remember { mutableStateOf(initial) }
