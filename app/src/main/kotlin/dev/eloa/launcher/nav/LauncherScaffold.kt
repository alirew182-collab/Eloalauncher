package dev.eloa.launcher.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eloa.launcher.R
import dev.eloa.launcher.feature.common.UserMessages
import kotlinx.coroutines.launch

/** Data the chrome needs besides navigation. */
data class ChromeState(val unreadNotifications: Int = 0, val errorBadge: Int = 0, val accountName: String? = null)

@Composable
private fun groupTitle(g: Int) = when (g) {
    0 -> stringResource(R.string.nav_group_main)
    1 -> stringResource(R.string.nav_group_library)
    else -> stringResource(R.string.nav_group_tools)
}

/** Logo mark: a flat block, accent top over a darker body. No gradient, no glow. */
@Composable
private fun LogoMark(modifier: Modifier = Modifier) {
    Column(modifier.size(24.dp).clip(RoundedCornerShape(4.dp))) {
        Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.primary))
        Box(Modifier.fillMaxWidth().weight(2f).background(MaterialTheme.colorScheme.primaryContainer))
    }
}

@Composable
fun LauncherScaffold(
    current: Destination?,
    title: String,
    canGoBack: Boolean,
    messages: UserMessages,
    chrome: ChromeState,
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(messages) {
        messages.flow.collect { m ->
            val r = snackbar.showSnackbar(
                m.resolve(context), actionLabel = if (m.actionRes != 0) context.getString(m.actionRes) else null,
                withDismissAction = m.actionRes == 0, duration = if (m.isError) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (r == SnackbarResult.ActionPerformed) m.action?.invoke()
        }
    }
    val widthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val wide = widthDp >= 840.dp
    val medium = !wide && widthDp >= 600.dp

    val drawerContent: @Composable () -> Unit = {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
            Row(Modifier.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                LogoMark()
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            var lastGroup = -1
            Destination.entries.forEach { d ->
                if (d.group != lastGroup) {
                    if (lastGroup >= 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(groupTitle(d.group), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp, 4.dp))
                    lastGroup = d.group
                }
                NavigationDrawerItem(
                    label = { Text(stringResource(d.label)) },
                    icon = { NavIcon(d, chrome) },
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
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (current == Destination.HOME) { LogoMark(Modifier.padding(end = 10.dp)) }
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = {
                        when {
                            showMenu -> IconButton(onClick = openMenu, modifier = Modifier.testTag("menu_button")) { Icon(Icons.Outlined.Menu, stringResource(R.string.cd_open_navigation)) }
                            canGoBack -> IconButton(onClick = onBack, modifier = Modifier.testTag("back_button")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back)) }
                        }
                    },
                    actions = {
                        IconButton(onClick = { onNavigate(Destination.NOTIFICATIONS) }, modifier = Modifier.testTag("top_notifications")) {
                            BadgedBox(badge = { if (chrome.unreadNotifications > 0) Badge { Text(chrome.unreadNotifications.coerceAtMost(99).toString()) } }) {
                                Icon(Icons.Outlined.Notifications, stringResource(R.string.nav_notifications))
                            }
                        }
                        IconButton(onClick = { onNavigate(Destination.ACCOUNTS) }, modifier = Modifier.testTag("top_account")) {
                            Icon(Icons.Outlined.AccountCircle, chrome.accountName ?: stringResource(R.string.nav_accounts))
                        }
                        if (!wide) IconButton(onClick = { onNavigate(Destination.SETTINGS) }, modifier = Modifier.testTag("top_settings")) {
                            Icon(Icons.Outlined.Settings, stringResource(R.string.nav_settings))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            bottomBar = {
                if (!wide && !medium) NavigationBar {
                    Destination.primary.forEach { d ->
                        NavigationBarItem(
                            selected = d == current, onClick = { onNavigate(d) }, alwaysShowLabel = false,
                            icon = { NavIcon(d, chrome) }, label = { Text(stringResource(d.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.testTag("bottom_${d.route}"),
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Row(Modifier.fillMaxSize()) {
                if (medium) NavigationRail(Modifier.padding(top = padding.calculateTopPadding())) {
                    Spacer(Modifier.height(8.dp))
                    Destination.primary.forEach { d ->
                        NavigationRailItem(selected = d == current, onClick = { onNavigate(d) }, icon = { NavIcon(d, chrome) },
                            label = { Text(stringResource(d.label), maxLines = 1) }, modifier = Modifier.testTag("rail_${d.route}"))
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize()) { content(padding) }
            }
        }
    }

    if (wide) {
        PermanentNavigationDrawer(drawerContent = { PermanentDrawerSheet(Modifier.width(280.dp)) { drawerContent() } }) { body(false) {} }
    } else {
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        ModalNavigationDrawer(drawerState = drawerState, drawerContent = { ModalDrawerSheet(Modifier.width(300.dp)) { drawerContent() } }) {
            body(true) { scope.launch { drawerState.open() } }
        }
        LaunchedEffect(current) { drawerState.close() }
    }
}

@Composable
private fun NavIcon(d: Destination, chrome: ChromeState) {
    val badge = when (d) { Destination.LOGS -> chrome.errorBadge; Destination.NOTIFICATIONS -> chrome.unreadNotifications; else -> 0 }
    if (badge > 0) BadgedBox(badge = { Badge { Text(badge.coerceAtMost(99).toString()) } }) { Icon(d.icon, null) } else Icon(d.icon, null)
}
