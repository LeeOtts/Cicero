package com.leeotts.cicero.ui.theme

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** How the app picks between the light and dark schemes. Persisted in DataStore. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun CiceroTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        // The window is edge-to-edge and the bars are transparent, so the icons
        // have to be told which way to go. values-night handles the cold-start
        // frame; this handles every frame after a mode switch.
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    // Dynamic colour is deliberately absent: Material You would override the two
    // brand colours this palette exists to carry.
    MaterialTheme(
        colorScheme = if (dark) CiceroDark else CiceroLight,
        typography = CiceroTypography,
        content = content,
    )
}

/**
 * Mirrors the chosen mode into the platform.
 *
 * The system persists this per-package and applies it to the app's
 * Configuration *at process start*, which is what makes values-night select on
 * the app's own choice rather than the system's — so a cold start in forced-dark
 * paints the dark window background on the very first frame, with no flash and
 * no blocking read of DataStore.
 *
 * Costs an Activity recreation, so call it only from the selector, never on
 * startup, or the recreation re-triggers it.
 */
fun applyNightMode(context: Context, mode: ThemeMode) {
    val manager = context.getSystemService(UiModeManager::class.java) ?: return
    manager.setApplicationNightMode(
        when (mode) {
            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        }
    )
}
