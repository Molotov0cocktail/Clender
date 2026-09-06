package com.molotov.clender.ui.theme

import androidx.compose.ui.unit.sp
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClenderThemeTest {
    @Test
    fun systemThemeFollowsDeviceDarkSetting() {
        assertTrue(resolveIsDark(ThemeMode.SYSTEM, systemDark = true))
        assertFalse(resolveIsDark(ThemeMode.SYSTEM, systemDark = false))
    }

    @Test
    fun explicitLightAndDarkOverrideDeviceSetting() {
        assertFalse(resolveIsDark(ThemeMode.LIGHT, systemDark = true))
        assertTrue(resolveIsDark(ThemeMode.DARK, systemDark = false))
    }

    @Test
    fun lightAndDarkSchemesUseDistinctBackgrounds() {
        val light = clenderColorScheme(dark = false)
        val dark = clenderColorScheme(dark = true)

        assertNotEquals(light.background, dark.background)
        assertNotEquals(light.surface, dark.surface)
    }

    @Test
    fun appTypographyScalesWithinPolicyBounds() {
        assertEquals(8.sp, buildAppTypography(8).bodyLarge.fontSize)
        assertEquals(20.sp, buildAppTypography(20).bodyLarge.fontSize)
    }

    @Test
    fun appTypographyNormalizesOutOfRangeSizesToDefault() {
        assertEquals(13.sp, buildAppTypography(7).bodyLarge.fontSize)
        assertEquals(13.sp, buildAppTypography(21).bodyLarge.fontSize)
        assertEquals(13.sp, buildAppTypography(0).bodyLarge.fontSize)
    }

    @Test
    fun appTypographyFollowsAppFontSizeNotWidgetFontSize() {
        val appearance = AppearanceUiState(
            themeMode = ThemeMode.SYSTEM,
            appFontSizeSp = 20,
            widgetFontSizeSp = 8
        )
        val typography = buildAppTypography(appearance.appFontSizeSp)

        assertEquals(20.sp, typography.bodyLarge.fontSize)
    }
}
