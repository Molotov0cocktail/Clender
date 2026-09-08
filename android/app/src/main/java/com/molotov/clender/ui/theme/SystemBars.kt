package com.molotov.clender.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
internal fun ThemeSystemBars(dark: Boolean, background: Color) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val activity = view.context.findActivity() ?: return
    // Window updates may request layout; reapply only when their actual inputs change.
    DisposableEffect(view, dark, background) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, view)
        if (controller.isAppearanceLightStatusBars == dark) {
            controller.isAppearanceLightStatusBars = !dark
        }
        if (controller.isAppearanceLightNavigationBars == dark) {
            controller.isAppearanceLightNavigationBars = !dark
        }
        // Older three-button navigation needs an opaque surface behind the system controls.
        @Suppress("DEPRECATION")
        if (window.navigationBarColor != background.toArgb()) {
            window.navigationBarColor = background.toArgb()
        }
        @Suppress("DEPRECATION")
        if (window.statusBarColor != android.graphics.Color.TRANSPARENT) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
        }
        onDispose { }
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
