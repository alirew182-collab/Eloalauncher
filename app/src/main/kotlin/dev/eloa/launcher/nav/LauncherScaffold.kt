package dev.eloa.launcher.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.eloa.launcher.feature.common.UserMessages
import kotlinx.coroutines.launch

@Composable
fun LauncherScaffold(
    current: Destination?,
    title: String,
    messages: UserMessages,
    errorBadge: Int,
    onNavigate: (Destination) -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(messages) {
        messages.flow.collect { m ->
            val r = snackbar.showSnackbar(m.text, actionLabel = m.actionLabel, withDismissAction = m.actionLabel == null,
                duration = if (m.isError) SnackbarDuration.Long else SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) m.action?.invoke()
        }
    }
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val drawerContent: @Composable () -> Unit = {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
            Text("ELOA", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 12.dp))
            Text("Minecraft launcher", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(12.dp))
            var lastGroup = 0
            Destination.entries.forEach { d ->
                if (d.group != lastGroup) { HorizontalDivider(Modifier.padding(vertical = 8.dp)); lastGroup = d.group }
                NavigationDrawerItem(
                    label = { Text(d.label) },
                    icon = {
                        if (d == Destination.LOGS && errorBadge > 0) BadgedBox(badge = { Badge { Text("$errorBadge") } }) { Icon(d.icon, null) }
                        else Icon(d.icon, null)
                    },
                    selected = d == current,
                    onClick = { onNavigate(d) },
                    modifier = Modifier.padding(vertical = 1.dp).testTag("nav_${d.route}"),
                )
            }
        }
    }
    val body: @Composable (Boolean, () -> Unit) -> Unit = { showMenu, openMenu ->
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 1) },
                    navigationIcon = { if (showMenu) IconButton(onClick = openMenu, modifier = Modifier.testTag("menu_button")) { Icon(Icons.Outlined.Menu, "Open navigation") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding -> Box(Modifier.fillMaxSize()) { content(padding) } }
    }
    if (wide) {
        PermanentNavigationDrawer(drawerContent = { PermanentDrawerSheet(Modifier.width(280.dp)) { drawerContent() } }) { body(false) {} }
    } else {
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = { ModalDrawerSheet(Modifier.width(300.dp)) { drawerContent() } },
        ) { body(true) { scope.launch { drawerState.open() } } }
        LaunchedEffect(current) { drawerState.close() }
    }
}
