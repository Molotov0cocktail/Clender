package com.molotov.clender.ui.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.app.MainActivity
import com.molotov.clender.testsupport.ProductionActivityTestResources
import com.molotov.clender.ui.ai.ConversationViewModel
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.navigation.AppRoute
import com.molotov.clender.ui.settings.SettingsSection
import com.molotov.clender.ui.settings.SettingsViewModel
import com.molotov.clender.ui.state.AppShellViewModel
import com.molotov.clender.ui.theme.AppearanceViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsAppIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun closeProductionResources() {
        ProductionActivityTestResources.close(
            composeRule.activity.application as ClenderApplication,
            composeRule.activityRule.scenario
        )
    }

    @Test
    fun drawerSettingsOpensRealApplicationSection() {
        composeRule.onNodeWithTag("clender_open_navigation_drawer").performClick()
        composeRule.onNodeWithTag("clender_drawer_settings").performClick()

        composeRule.onNodeWithTag("settings_section_application").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_save_application")
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(SettingsSection.APPLICATION, settingsViewModel().state.value.section)
    }

    @Test
    fun aiAndQuickAiSettingsEntryFocusAiSection() {
        shellViewModel().navigateTo(AppDestination.AI)
        composeRule.waitUntil(5_000) {
            ViewModelProvider(composeRule.activity)[ConversationViewModel::class.java]
                .state.value.activeConversation != null
        }
        composeRule.onNodeWithTag("ai_composer_input").performTextInput("open settings")
        composeRule.onNodeWithTag("ai_composer_send").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("ai_composer_open_settings")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("ai_composer_open_settings").performClick()
        assertEquals(SettingsSection.AI, settingsViewModel().state.value.section)

        shellViewModel().pushRoute(AppRoute.QuickAi)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("quick_ai_open_settings").performClick()
        assertEquals(SettingsSection.AI, settingsViewModel().state.value.section)
    }

    @Test
    fun savedAppearanceImmediatelyDrivesProductionThemeState() {
        shellViewModel().navigateTo(AppDestination.SETTINGS)
        composeRule.waitForIdle()
        val settings = settingsViewModel()
        val loaded = settings.state.value.appearance
        settings.updateAppearance(
            loaded.copy(themeMode = ThemeMode.DARK, appFontSize = "20", widgetFontSize = "8")
        )
        settings.saveAppearance()

        composeRule.waitUntil(5_000) {
            appearanceViewModel().state.value.themeMode == ThemeMode.DARK &&
                appearanceViewModel().state.value.appFontSizeSp == 20 &&
                appearanceViewModel().state.value.widgetFontSizeSp == 8
        }
    }

    private fun shellViewModel(): AppShellViewModel =
        ViewModelProvider(composeRule.activity)[AppShellViewModel::class.java]

    private fun settingsViewModel(): SettingsViewModel =
        ViewModelProvider(composeRule.activity)[SettingsViewModel::class.java]

    private fun appearanceViewModel(): AppearanceViewModel =
        ViewModelProvider(composeRule.activity)[AppearanceViewModel::class.java]
}
