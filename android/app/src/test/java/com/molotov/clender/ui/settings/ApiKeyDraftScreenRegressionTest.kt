package com.molotov.clender.ui.settings

import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModelStore
import com.molotov.clender.R
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
class ApiKeyDraftScreenRegressionTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val host = RobolectricComposeHost()
    private val models = ViewModelStore()
    private val port = DraftSettingsPort()
    private lateinit var model: SettingsViewModel

    @Before
    fun startHost() = host.start()

    @After
    fun closeHost() {
        try {
            models.clear()
        } finally {
            host.close()
        }
    }

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun unsavedKeySurvivesBothSettingsTabsAndContinuedTyping() {
        showScreen()
        typeSyntheticInput()
        listOf("settings_section_application", "settings_section_webdav").forEach { tab ->
            changeTabAndReturn(tab)
            assertMaskedLength(12)
            assertDraftContent(12)
        }
        composeRule.onNodeWithTag("settings_ai_key")
            .performTextInputSelection(TextRange(12))
        composeRule.onNodeWithTag("settings_ai_key").performTextInput("z")
        assertDraftContent(12, appendSuffix = true)
        assertMaskedLength(13)
        assertEquals(0, port.saves)
    }

    @Test
    fun failedSaveKeepsDraftVisibleAfterTabChangeInDarkTheme() {
        port.decision = SettingsSaveDecision.SAVE_FAILED
        showScreen(ThemeMode.DARK)
        typeSyntheticInput()
        composeRule.onNodeWithTag("settings_save_ai").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(SettingsStatus.SAVE_FAILED, model.state.value.status)
            assertTrue(model.state.value.dirty)
            assertFalse(model.state.value.apiKeyConfigured)
        }
        changeTabAndReturn("settings_section_application")
        assertMaskedLength(12)
        assertDraftContent(12)
        assertEquals(ApiKeyMutation.REPLACE, port.lastMutation)
    }

    @Test
    fun successfulSaveShowsConfiguredLabelAndNeverRefillsSavedKey() {
        showScreen()
        typeSyntheticInput()
        composeRule.onNodeWithTag("settings_save_ai").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1, port.saves)
            assertEquals(ApiKeyMutation.REPLACE, port.lastMutation)
            assertTrue(model.state.value.apiKeyConfigured)
            assertFalse(model.state.value.dirty)
        }
        changeTabAndReturn("settings_section_application")
        assertMaskedLength(0)
        assertDraftContent(0)
        assertConfiguredLabel()
    }

    @Test
    fun successfulKeySaveClearsFieldWhileApplicationDraftRemainsDirty() {
        showScreen()
        composeRule.onNodeWithTag("settings_section_application").performClick()
        composeRule.onNodeWithTag("settings_theme_dark").performScrollTo().performClick()
        composeRule.onNodeWithTag("settings_section_ai").performClick()
        typeSyntheticInput()
        composeRule.onNodeWithTag("settings_save_ai").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(1, port.saves)
            assertEquals(ApiKeyMutation.REPLACE, port.lastMutation)
            assertEquals(SettingsStatus.READY, model.state.value.status)
            assertTrue(model.state.value.apiKeyConfigured)
            assertTrue(model.state.value.dirty)
            assertEquals(ThemeMode.DARK, model.state.value.appearance.themeMode)
        }
        composeRule.onNodeWithTag("settings_ai_key").performScrollTo()
        assertConfiguredLabel()
        assertDraftContent(0)
        assertMaskedLength(0)
        composeRule.onNodeWithTag("settings_ai_key").performTextInput("z")
        assertMaskedLength(1)
        composeRule.runOnIdle {
            val actual = model.state.value.secretInput.copyChars()
            val expected = charArrayOf('z')
            try {
                assertTrue(
                    "New input must not contain the saved draft",
                    actual.contentEquals(expected)
                )
            } finally {
                actual.fill('\u0000')
                expected.fill('\u0000')
            }
        }
    }

    @Test
    fun existingConfiguredKeyStartsEmptyAndSaveWithoutInputUsesKeep() {
        port.configured = true
        showScreen(ThemeMode.DARK)
        assertMaskedLength(0)
        assertConfiguredLabel()
        composeRule.onNodeWithTag("settings_save_ai").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(ApiKeyMutation.KEEP, port.lastMutation) }
    }

    @Test
    fun dirtyNavigationKeepPreservesInputAndConfirmedDiscardClearsIt() {
        showScreen()
        typeSyntheticInput()
        var navigated = false
        composeRule.runOnIdle {
            assertFalse(model.requestNavigation { navigated = true })
        }
        composeRule.onNodeWithTag("settings_discard_cancel").performClick()
        assertMaskedLength(12)
        assertDraftContent(12)
        assertFalse(navigated)
        composeRule.runOnIdle {
            assertFalse(model.requestNavigation { navigated = true })
        }
        composeRule.onNodeWithTag("settings_discard_confirm").performClick()
        composeRule.runOnIdle { assertTrue(navigated) }
        assertMaskedLength(0)
        assertDraftContent(0)
        assertEquals(0, port.saves)
    }

    private fun showScreen(theme: ThemeMode = ThemeMode.LIGHT) {
        composeRule.runOnUiThread {
            model = SettingsViewModel(port)
            models.put("t49-settings", model)
            model.showAiSection()
            model.activate()
            host.activity.setContent {
                val state by model.state.collectAsState()
                ClenderTheme(AppearanceUiState(theme, 13, 13)) {
                    SettingsScreen(state, actions())
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun actions() = SettingsActions(
        onSectionChange = model.selectSection,
        onAppearanceChange = model.updateAppearance,
        onAiChange = model.updateAi,
        onSecretInput = model.updateSecretInput,
        onSaveAppearance = model.saveAppearance,
        onSaveAi = model.saveAi,
        onFetchModels = model.fetchModels,
        onRequestRemoveKey = model.requestApiKeyRemoval,
        onConfirmRemoveKey = model.confirmApiKeyRemoval,
        onCancelRemoveKey = model.cancelApiKeyRemoval,
        onConfirmDiscard = model.confirmDiscard,
        onCancelDiscard = model.cancelDiscard
    )

    private fun typeSyntheticInput() {
        composeRule.onNodeWithTag("settings_ai_key").performScrollTo()
            .performTextInput("qwertyabcdef")
        composeRule.waitForIdle()
    }

    private fun changeTabAndReturn(tab: String) {
        composeRule.onNodeWithTag(tab).performClick()
        composeRule.onNodeWithTag("settings_section_ai").performClick()
        composeRule.onNodeWithTag("settings_ai_key").performScrollTo()
    }

    private fun assertMaskedLength(expected: Int) {
        val config = composeRule.onNodeWithTag("settings_ai_key").fetchSemanticsNode().config
        assertTrue(SemanticsProperties.Password in config)
        val visible = config[SemanticsProperties.EditableText].text
        assertEquals("Masked draft length", expected, visible.length)
        assertTrue("Secret must remain masked", visible.all { it == '\u2022' })
    }

    private fun assertDraftContent(length: Int, appendSuffix: Boolean = false) {
        composeRule.runOnIdle {
            val copy = model.state.value.secretInput.copyChars()
            val expected = when {
                appendSuffix -> "qwertyabcdefz".toCharArray()
                length == 0 -> CharArray(0)
                else -> "qwertyabcdef".toCharArray()
            }
            try {
                assertTrue("Draft characters and order must match", copy.contentEquals(expected))
            } finally {
                copy.fill('\u0000')
                expected.fill('\u0000')
            }
        }
    }

    private fun assertConfiguredLabel() {
        val config = composeRule.onNodeWithTag("settings_ai_key").fetchSemanticsNode().config
        val label = host.activity.getString(R.string.settings_ai_key_configured)
        assertTrue(config[SemanticsProperties.Text].any { it.text == label })
    }
}

private class DraftSettingsPort : SettingsPort {
    var configured = false
    var decision = SettingsSaveDecision.SUCCESS
    var saves = 0
    var lastMutation: ApiKeyMutation? = null

    override suspend fun load(): PersistedSettings = PersistedSettings(
        ai = AiSettingsDraft(endpoint = "https://example.invalid/v1", model = "test-model"),
        apiKeyConfigured = configured
    )

    override suspend fun saveAppearance(draft: AppearanceSettingsDraft): SettingsSaveDecision =
        error("Unexpected appearance write")

    override suspend fun saveAi(
        draft: AiSettingsDraft,
        mutation: ApiKeyMutation,
        key: CharArray?
    ): SettingsSaveDecision {
        saves++
        lastMutation = mutation
        if (mutation == ApiKeyMutation.REPLACE) {
            val copy = requireNotNull(key).copyOf()
            val expected = "qwertyabcdef".toCharArray()
            try {
                assertTrue("Saved replacement must match draft", copy.contentEquals(expected))
            } finally {
                copy.fill('\u0000')
                expected.fill('\u0000')
            }
        }
        return decision
    }

    override suspend fun fetchModels(
        draft: AiSettingsDraft,
        mutation: ApiKeyMutation,
        key: CharArray?
    ): ModelFetchResult = error("Network must not be requested")

    override fun cancelModelFetch() = Unit
}
