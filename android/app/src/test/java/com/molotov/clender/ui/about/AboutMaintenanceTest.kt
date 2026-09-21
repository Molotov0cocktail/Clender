package com.molotov.clender.ui.about

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en-rUS-w320dp-h640dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutMaintenanceTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val host = RobolectricComposeHost()

    @Before
    fun start() = host.start()

    @After
    fun close() = host.close()

    @Test
    fun maintenanceActionsRemainReachableInLightAndLargeDarkThemes() {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
            render(theme)
            listOf("about_export_logs", "about_clear_logs", "about_clear_cache").forEach { tag ->
                composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            }
            composeRule.onNodeWithTag("about_maintenance_hint").performScrollTo()
                .assertTextEquals(
                    "Logs contain diagnostic codes and timing only. " +
                        "Clearing keeps schedules, chats and settings."
                )
        }
    }

    @Test
    fun bothCleanupActionsRequireConfirmationAndCancelLeavesNoResult() {
        render()
        listOf("about_clear_logs", "about_clear_cache").forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().performClick()
            composeRule.onNodeWithTag("about_maintenance_confirm").assertIsDisplayed()
            composeRule.onNodeWithTag("about_maintenance_cancel").performClick()
            composeRule.onNodeWithTag("about_maintenance_confirm").assertDoesNotExist()
            composeRule.onNodeWithTag("about_maintenance_status").assertDoesNotExist()
        }
    }

    private fun render(theme: ThemeMode = ThemeMode.LIGHT) {
        host.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density.density,
                    if (theme == ThemeMode.DARK) 2f else 1f
                )
            ) {
                ClenderTheme(
                    AppearanceUiState(theme, if (theme == ThemeMode.DARK) 20 else 13, 13)
                ) {
                    AboutScreen(
                        model = AboutUiModel("Clender", "1.3.1", "3"),
                        modifier = Modifier.width(320.dp).height(640.dp)
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }
}
