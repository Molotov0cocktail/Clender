package com.molotov.clender.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.FontSizePolicy
import com.molotov.clender.ui.foundation.ThemeMode

private const val DISPLAY_LARGE_DEFAULT_SP = 57f
private const val DISPLAY_MEDIUM_DEFAULT_SP = 45f
private const val DISPLAY_SMALL_DEFAULT_SP = 36f
private const val HEADLINE_LARGE_DEFAULT_SP = 32f
private const val HEADLINE_MEDIUM_DEFAULT_SP = 28f
private const val HEADLINE_SMALL_DEFAULT_SP = 24f
private const val TITLE_LARGE_DEFAULT_SP = 22f
private const val TITLE_MEDIUM_DEFAULT_SP = 16f
private const val TITLE_SMALL_DEFAULT_SP = 14f
private const val BODY_MEDIUM_DEFAULT_SP = 14f
private const val BODY_SMALL_DEFAULT_SP = 12f
private const val LABEL_LARGE_DEFAULT_SP = 14f
private const val LABEL_MEDIUM_DEFAULT_SP = 12f
private const val LABEL_SMALL_DEFAULT_SP = 11f

private const val DARK_PRIMARY_HEX = 0xFFB5BFFF
private const val DARK_ON_PRIMARY_HEX = 0xFF003258
private const val DARK_BACKGROUND_HEX = 0xFF111827
private const val DARK_ON_BACKGROUND_HEX = 0xFFE6E1E5
private const val DARK_SURFACE_HEX = 0xFF1D2638
private const val DARK_ON_SURFACE_HEX = 0xFFE6E1E5
private const val LIGHT_PRIMARY_HEX = 0xFF5064C8
private const val LIGHT_ON_PRIMARY_HEX = 0xFFFFFFFF
private const val LIGHT_BACKGROUND_HEX = 0xFFF3F5FC
private const val LIGHT_ON_BACKGROUND_HEX = 0xFF1C1B1F
private const val LIGHT_SURFACE_HEX = 0xFFFFFFFF
private const val LIGHT_ON_SURFACE_HEX = 0xFF1C1B1F

fun resolveIsDark(themeMode: ThemeMode, systemDark: Boolean): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

fun clenderColorScheme(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = Color(DARK_PRIMARY_HEX),
        onPrimary = Color(DARK_ON_PRIMARY_HEX),
        background = Color(DARK_BACKGROUND_HEX),
        onBackground = Color(DARK_ON_BACKGROUND_HEX),
        surface = Color(DARK_SURFACE_HEX),
        onSurface = Color(DARK_ON_SURFACE_HEX)
    )
} else {
    lightColorScheme(
        primary = Color(LIGHT_PRIMARY_HEX),
        onPrimary = Color(LIGHT_ON_PRIMARY_HEX),
        background = Color(LIGHT_BACKGROUND_HEX),
        onBackground = Color(LIGHT_ON_BACKGROUND_HEX),
        surface = Color(LIGHT_SURFACE_HEX),
        onSurface = Color(LIGHT_ON_SURFACE_HEX)
    )
}

fun buildAppTypography(appFontSizeSp: Int): Typography {
    val normalized = FontSizePolicy.normalize(appFontSizeSp)
    val scale = normalized / FontSizePolicy.DEFAULT_SP.toFloat()
    fun scaled(defaultSp: Float): Float = defaultSp * scale
    return Typography(
        displayLarge = TextStyle(fontSize = scaled(DISPLAY_LARGE_DEFAULT_SP).sp),
        displayMedium = TextStyle(fontSize = scaled(DISPLAY_MEDIUM_DEFAULT_SP).sp),
        displaySmall = TextStyle(fontSize = scaled(DISPLAY_SMALL_DEFAULT_SP).sp),
        headlineLarge = TextStyle(fontSize = scaled(HEADLINE_LARGE_DEFAULT_SP).sp),
        headlineMedium = TextStyle(fontSize = scaled(HEADLINE_MEDIUM_DEFAULT_SP).sp),
        headlineSmall = TextStyle(fontSize = scaled(HEADLINE_SMALL_DEFAULT_SP).sp),
        titleLarge = TextStyle(fontSize = scaled(TITLE_LARGE_DEFAULT_SP).sp),
        titleMedium = TextStyle(fontSize = scaled(TITLE_MEDIUM_DEFAULT_SP).sp),
        titleSmall = TextStyle(fontSize = scaled(TITLE_SMALL_DEFAULT_SP).sp),
        bodyLarge = TextStyle(fontSize = normalized.sp),
        bodyMedium = TextStyle(fontSize = scaled(BODY_MEDIUM_DEFAULT_SP).sp),
        bodySmall = TextStyle(fontSize = scaled(BODY_SMALL_DEFAULT_SP).sp),
        labelLarge = TextStyle(fontSize = scaled(LABEL_LARGE_DEFAULT_SP).sp),
        labelMedium = TextStyle(fontSize = scaled(LABEL_MEDIUM_DEFAULT_SP).sp),
        labelSmall = TextStyle(fontSize = scaled(LABEL_SMALL_DEFAULT_SP).sp)
    )
}

@Composable
fun ClenderTheme(appearance: AppearanceUiState, content: @Composable () -> Unit) {
    val dark = resolveIsDark(appearance.themeMode, isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = clenderColorScheme(dark),
        typography = buildAppTypography(appearance.appFontSizeSp),
        content = content
    )
}
