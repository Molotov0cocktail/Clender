package com.molotov.clender.ui.theme

import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.WindowCompat
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemBarsThemeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private val host = RobolectricComposeHost()

    @Before
    fun start() = host.start()

    @After
    fun close() = host.close()

    @Test
    fun themeSwitchUpdatesSystemIconsAndRemovesOpaqueStatusBar() {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.LIGHT).forEach { theme ->
            composeRule.runOnUiThread {
                host.activity.setContent {
                    ClenderTheme(AppearanceUiState(theme, 13, 13)) { Text("Screen") }
                }
            }
            composeRule.waitForIdle()
            composeRule.runOnUiThread {
                val window = host.activity.window
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                assertEquals(theme == ThemeMode.LIGHT, controller.isAppearanceLightStatusBars)
                assertEquals(theme == ThemeMode.LIGHT, controller.isAppearanceLightNavigationBars)
                @Suppress("DEPRECATION")
                assertEquals(Color.TRANSPARENT, window.statusBarColor)
            }
        }
    }

    @Test
    fun stateDrivenThemeChangesUpdateTheExistingWindow() {
        val theme = mutableStateOf(ThemeMode.LIGHT)
        composeRule.runOnUiThread {
            host.activity.setContent {
                ClenderTheme(AppearanceUiState(theme.value, 13, 13)) { Text("Screen") }
            }
        }
        listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.LIGHT).forEach { expected ->
            composeRule.runOnUiThread { theme.value = expected }
            composeRule.waitForIdle()
            composeRule.runOnUiThread {
                val window = host.activity.window
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                assertEquals(expected == ThemeMode.LIGHT, controller.isAppearanceLightStatusBars)
                assertEquals(
                    expected == ThemeMode.LIGHT,
                    controller.isAppearanceLightNavigationBars
                )
                @Suppress("DEPRECATION")
                assertEquals(Color.TRANSPARENT, window.statusBarColor)
            }
        }
    }
}
