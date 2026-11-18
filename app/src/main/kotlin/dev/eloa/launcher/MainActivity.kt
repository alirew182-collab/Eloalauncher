package dev.eloa.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.eloa.launcher.core.designsystem.theme.AccentColor
import dev.eloa.launcher.core.designsystem.theme.LauncherTheme
import dev.eloa.launcher.core.designsystem.theme.ThemeMode
import dev.eloa.launcher.feature.common.UserMessages
import dev.eloa.launcher.nav.AppNavHost
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var messages: UserMessages
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val s by vm.settings.collectAsStateWithLifecycle()
            val errors by vm.recentErrors.collectAsStateWithLifecycle()
            LauncherTheme(
                themeMode = runCatching { ThemeMode.valueOf(s.themeMode) }.getOrDefault(ThemeMode.SYSTEM),
                accent = runCatching { AccentColor.valueOf(s.accent) }.getOrDefault(AccentColor.GRASS),
                enableBlur = s.enableBlur,
                reduceMotion = s.reduceMotion,
            ) {
                AppNavHost(messages = messages, errorBadge = errors)
            }
        }
    }
}
