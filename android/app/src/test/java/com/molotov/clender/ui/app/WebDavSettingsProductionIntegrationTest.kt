package com.molotov.clender.ui.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.app.MainActivity
import com.molotov.clender.testsupport.ProductionActivityTestResources
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.settings.SettingsSection
import com.molotov.clender.ui.settings.SettingsViewModel
import com.molotov.clender.ui.state.AppShellViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T46-C2b production integration: the real Settings/WebDAV section wired
 * through MainActivity while the app-scoped sync runtime reports DISABLED
 * (zero network) and a disabled preconfigured draft saves end-to-end into the
 * isolated production DataStore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebDavSettingsProductionIntegrationTest {
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
    fun webDavTabOrderAndSectionAreReachableFromTheProductionShell() {
        shellViewModel().navigateTo(AppDestination.SETTINGS)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_section_application").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_section_ai").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_section_webdav").assertIsDisplayed()
        assertEquals(SettingsSection.APPLICATION, settingsViewModel().state.value.section)

        composeRule.onNodeWithTag("settings_section_webdav").performClick()
        composeRule.waitForIdle()

        assertEquals(SettingsSection.WEBDAV, settingsViewModel().state.value.section)
        listOf(
            "settings_webdav_enabled",
            "settings_webdav_url",
            "settings_webdav_username",
            "settings_webdav_password",
            "settings_webdav_save",
            "settings_webdav_test_connection",
            "settings_webdav_sync_now"
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun disabledPreconfiguredDraftSavesEndToEndWhileRuntimeReportsDisabled() {
        shellViewModel().navigateTo(AppDestination.SETTINGS)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_section_webdav").performClick()
        composeRule.waitForIdle()

        val settings = settingsViewModel()
        settings.updateWebDav(
            settings.state.value.webDav.copy(
                enabled = false,
                url = "https://dav.example.invalid/calendar/",
                username = "fixture-user"
            )
        )
        settings.saveWebDav()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val snapshot = settings.state.value
            if (!snapshot.dirty ||
                snapshot.status in setOf(
                    com.molotov.clender.ui.settings.SettingsStatus.VALIDATION_FAILED,
                    com.molotov.clender.ui.settings.SettingsStatus.SAVE_FAILED,
                    com.molotov.clender.ui.settings.SettingsStatus.INTERNAL
                )
            ) {
                break
            }
            composeRule.waitForIdle()
            Thread.sleep(100)
        }
        assertFalse(settings.state.value.dirty)

        assertFalse(settings.state.value.dirty)
        assertEquals(settings.state.value.webDav.username, "fixture-user")
        assertEquals(
            SettingsSection.WEBDAV,
            settings.state.value.section
        )
        val disabledLabel = composeRule.activity.getString(
            com.molotov.clender.R.string.settings_webdav_sync_disabled
        )
        val textDeadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < textDeadline) {
            if (composeRule.onAllNodesWithText(disabledLabel)
                    .fetchSemanticsNodes().isNotEmpty()
            ) {
                break
            }
            composeRule.waitForIdle()
            Thread.sleep(100)
        }
        composeRule.onNodeWithText(disabledLabel).performScrollTo().assertIsDisplayed()
    }

    private fun shellViewModel(): AppShellViewModel =
        ViewModelProvider(composeRule.activity)[AppShellViewModel::class.java]

    private fun settingsViewModel(): SettingsViewModel =
        ViewModelProvider(composeRule.activity)[SettingsViewModel::class.java]
}
