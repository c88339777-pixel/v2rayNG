package com.v2ray.ang.ui.compose

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val VpnHubColor = darkColorScheme(
    primary = Color(0xFF19A9FF),
    onPrimary = Color(0xFF070B14),
    primaryContainer = Color(0xFF087AC4),
    onPrimaryContainer = Color(0xFFF1F5F9),

    secondary = Color(0xFF087AC4),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF153B5B),
    onSecondaryContainer = Color(0xFFDCEEFF),

    tertiary = Color(0xFF22C55E),
    onTertiary = Color(0xFF071B0E),
    tertiaryContainer = Color(0xFF14532D),
    onTertiaryContainer = Color(0xFFDCFCE7),

    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2B0808),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),

    background = Color(0xFF070B14),
    onBackground = Color(0xFFF1F5F9),

    surface = Color(0xFF111827),
    onSurface = Color(0xFFF1F5F9),

    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFFCBD5E1),

    outline = Color(0xFF475569),
    outlineVariant = Color(0xFF334155),

    inverseSurface = Color(0xFFF1F5F9),
    inverseOnSurface = Color(0xFF111827),
    inversePrimary = Color(0xFF087AC4),

    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF19A9FF),

    surfaceContainerLowest = Color(0xFF070B14),
    surfaceContainerLow = Color(0xFF0B1220),
    surfaceContainer = Color(0xFF111827),
    surfaceContainerHigh = Color(0xFF1E293B),
    surfaceContainerHighest = Color(0xFF273449)
)

// Semantic Colors
val colorPing = Color(0xFF22C55E)
val colorPingRed = Color(0xFFFF6B6B)
val colorConfigType = Color(0xFF19A9FF)
val colorFabActive = Color(0xFF19A9FF)
val colorFabInactiveLight = Color(0xFF64748B)
val colorFabInactiveDark = Color(0xFF475569)

val dividerColorLight = Color(0xFF334155)
val dividerColorDark = Color(0xFF334155)

// Toast Colors
val toastNormalBgLight = Color(0xD9111827)
val toastNormalBgDark = Color(0xD9111827)
val toastSuccessBg = Color(0xD9145C32)
val toastErrorBg = Color(0xD97F1D1D)
val toastInfoBg = Color(0xD9087AC4)
val toastIconCircleBg = Color(0x33FFFFFF)
val toastTextColor = Color(0xFFF1F5F9)

object ThemeManager {
    private val _themeMode = MutableStateFlow(
        MmkvManager.decodeSettingsString(
            AppConfig.PREF_UI_MODE_NIGHT,
            "0"
        ) ?: "0"
    )

    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(false)
    val dynamicColorEnabled: StateFlow<Boolean> =
        _dynamicColorEnabled.asStateFlow()

    fun setThemeMode(mode: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, mode)
        _themeMode.value = mode
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        // VPN Hub uses its own fixed color palette.
        _dynamicColorEnabled.value = false
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, false)
    }

    fun refresh() {
        _themeMode.value =
            MmkvManager.decodeSettingsString(
                AppConfig.PREF_UI_MODE_NIGHT,
                "0"
            ) ?: "0"

        _dynamicColorEnabled.value = false
    }
}

@Composable
fun resolveDarkTheme(): Boolean {
    val mode by ThemeManager.themeMode.collectAsState()

    // VPN Hub always uses the dark palette.
    return true
}

val LocalDarkTheme = compositionLocalOf { true }

@Composable
fun AppTheme(
    darkTheme: Boolean = resolveDarkTheme(),
    content: @Composable () -> Unit
) {
    val snackbarController = rememberAppSnackbarController()

    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity
                ?: return@SideEffect

            val window = activity.window

            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }

            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
    }

    CompositionLocalProvider(
        LocalDarkTheme provides true,
        LocalAppSnackbar provides snackbarController
    ) {
        MaterialTheme(
            colorScheme = VpnHubColor
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AppSnackbarBridge(controller = snackbarController)
                content()
                AppSnackbarHost(
                    hostState = snackbarController.hostState
                )
            }
        }
    }
}
