package dev.eloa.launcher.feature.library

import dev.eloa.launcher.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eloa.launcher.core.designsystem.component.LauncherCard
import dev.eloa.launcher.core.designsystem.component.SectionHeader
import dev.eloa.launcher.nav.Destination

/** Hub for everything that is not a primary section. */
@Composable
fun LibraryScreen(padding: PaddingValues, onNavigate: (Destination) -> Unit) {
    val library = Destination.entries.filter { it.group == 1 }
    val tools = Destination.entries.filter { it.group == 2 }
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp), Modifier.padding(padding).testTag("library"),
        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.games_and_content)) }
        items(library, key = { it.route }) { d -> Tile(d, onNavigate) }
        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.nav_group_tools)) }
        items(tools, key = { it.route }) { d -> Tile(d, onNavigate) }
    }
}

@Composable
private fun Tile(d: Destination, onNavigate: (Destination) -> Unit) {
    LauncherCard(Modifier.fillMaxWidth().testTag("lib_${d.route}"), onClick = { onNavigate(d) }) {
        Icon(d.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(10.dp))
        Text(stringResource(d.label), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
