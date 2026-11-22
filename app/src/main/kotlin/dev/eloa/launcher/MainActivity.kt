package dev.eloa.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.eloa.launcher.core.data.settings.UiDensity
import dev.eloa.launcher.core.designsystem.theme.AccentColor
import dev.eloa.launcher.core.designsystem.theme.LauncherTheme
import dev.eloa.launcher.core.designsystem.theme.ThemeMode
import dev.eloa.launcher.feature.common.Locales
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.feature.java.REDIRECT_URI
import dev.eloa.launcher.nav.AppNavHost
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var messages: UserMessages
    private val vm: MainViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Locales.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.eloa.launcher.feature.common.Strings.init(this)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            getSharedPreferences("ui", MODE_PRIVATE).getBoolean("asked_notifications", false).not()
        ) {
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("asked_notifications", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val s by vm.settings.collectAsStateWithLifecycle()
            val chrome by vm.chrome.collectAsStateWithLifecycle()
            val route by vm.requestedRoute.collectAsStateWithLifecycle()
            LaunchedEffect(s.uiRefreshCapHz) { applyRefreshCap(s.uiRefreshCapHz) }
            LauncherTheme(
                themeMode = runCatching { ThemeMode.valueOf(s.themeMode) }.getOrDefault(ThemeMode.SYSTEM),
                accent = runCatching { AccentColor.valueOf(s.accent) }.getOrDefault(AccentColor.GRASS),
                enableBlur = s.enableBlur,
                reduceMotion = s.reduceMotion,
            ) {
                // Interface density and element size scale every dp/sp of the UI.
                val base = LocalDensity.current
                val f = s.uiScale * when (s.uiDensity) { UiDensity.COMPACT -> 0.92f; UiDensity.DEFAULT -> 1f; UiDensity.COMFORTABLE -> 1.08f }
                CompositionLocalProvider(LocalDensity provides Density(base.density * f, base.fontScale)) {
                    AppNavHost(messages = messages, chrome = chrome, requestedRoute = route, onRouteHandled = { vm.requestRoute(null) })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data != null && data.toString().startsWith(REDIRECT_URI)) {
            vm.onAuthRedirect(data.toString())
            return
        }
        intent?.getStringExtra(EXTRA_ROUTE)?.let { vm.requestRoute(it) }
    }

    /** Caps only this window's refresh rate (saves battery on weak devices); system settings are untouched. */
    private fun applyRefreshCap(hz: Int) {
        val attrs = window.attributes
        if (hz <= 0) {
            attrs.preferredDisplayModeId = 0
        } else {
            @Suppress("DEPRECATION")
            val target = window.decorView.display ?: runCatching { windowManager.defaultDisplay }.getOrNull()
            val mode = target?.supportedModes?.filter { it.refreshRate <= hz + 0.5f }?.maxByOrNull { it.refreshRate }
            attrs.preferredDisplayModeId = mode?.modeId ?: 0
        }
        window.attributes = attrs
    }

    companion object { const val EXTRA_ROUTE = "route" }
}
