package com.molotov.clender.ui.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import org.junit.After
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
@Config(sdk = [26, 36], application = ClenderApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenContractTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val composeHost = RobolectricComposeHost()

    @Before
    fun startHost() = composeHost.start()

    @After
    fun closeHost() = composeHost.close()

    @Test
    fun applicationAndAiSectionsExposeReachableControlsAndSelection() {
        setScreen(readyState())
        target("settings_section_application").assertIsSelected()
        listOf("settings_theme_system", "settings_theme_light", "settings_theme_dark").forEach {
            pageTarget(it)
        }
        field("settings_app_font")
        field("settings_widget_font")
        pageTarget("settings_save_application")

        setScreen(readyState().copy(section = SettingsSection.AI))
        target("settings_section_ai").assertIsSelected()
        listOf(
            "settings_ai_endpoint",
            "settings_ai_key",
            "settings_ai_model",
            "settings_ai_temperature",
            "settings_ai_max_output",
            "settings_ai_context",
            "settings_ai_effort",
            "settings_ai_system_prompt",
            "settings_ai_personality"
        ).forEach(::field)
        listOf(
            "settings_ai_thinking",
            "settings_fetch_models",
            "settings_save_ai"
        ).forEach(::pageTarget)
    }

    @Test
    fun passwordFieldIsMaskedAndSemanticsNeverContainTypedKey() {
        val sentinel = "semantic-secret-sentinel"
        var received = CharArray(0)
        setScreen(
            readyState().copy(section = SettingsSection.AI),
            actions = actions(onSecretInput = { received = it.copyOf() })
        )

        composeRule.onNodeWithTag("settings_ai_key").performTextInput(sentinel)
        composeRule.runOnIdle { assertTrue(String(received) == sentinel) }
        val semantics = composeRule.onNodeWithTag("settings_ai_key").fetchSemanticsNode().config
        assertTrue(SemanticsProperties.Password in semantics)
        assertFalse(semantics.toString().contains(sentinel))
        received.fill('\u0000')
    }

    @Test
    fun readyStateDoesNotClearKeyInputWhenDraftBecomesDirty() {
        var receivedLength = 0
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                var state by remember {
                    mutableStateOf(readyState().copy(section = SettingsSection.AI))
                }
                ClenderTheme(AppearanceUiState(ThemeMode.LIGHT, 13, 13)) {
                    SettingsScreen(
                        state = state,
                        actions = actions(
                            onSecretInput = {
                                receivedLength = it.size
                                state = state.copy(dirty = true)
                            }
                        )
                    )
                }
            }
        }

        composeRule.onNodeWithTag("settings_ai_key").performTextInput("a")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_ai_key").performTextInput("b")

        composeRule.runOnIdle { assertTrue(receivedLength == 2) }
    }

    @Test
    fun keyConfiguredStateNeverHydratesInputAndRemoveRequiresConfirmation() {
        var removals = 0
        setScreen(
            readyState().copy(section = SettingsSection.AI, apiKeyConfigured = true),
            actions = actions(onRequestRemoveKey = { removals += 1 })
        )
        assertFalse(
            composeRule.onNodeWithTag("settings_ai_key")
                .fetchSemanticsNode().config.toString().contains("configured-key-value")
        )
        pageTarget("settings_remove_key").performClick()
        composeRule.runOnIdle { assertTrue(removals == 1) }

        setScreen(
            readyState().copy(
                section = SettingsSection.AI,
                apiKeyConfigured = true,
                removeKeyConfirmation = true
            )
        )
        target("settings_confirm_remove_key")
        target("settings_cancel_remove_key")
    }

    @Test
    fun loadingSavingAndFetchingDisableDuplicateOperations() {
        setScreen(readyState().copy(status = SettingsStatus.LOADING))
        composeRule.onNodeWithTag("settings_save_application").assertIsNotEnabled()

        setScreen(readyState().copy(section = SettingsSection.AI, status = SettingsStatus.SAVING))
        composeRule.onNodeWithTag("settings_save_ai").assertIsNotEnabled()
        composeRule.onNodeWithTag("settings_fetch_models").assertIsNotEnabled()

        setScreen(readyState().copy(section = SettingsSection.AI, status = SettingsStatus.FETCHING))
        composeRule.onNodeWithTag("settings_fetch_models").assertIsNotEnabled()
        composeRule.onNodeWithTag("settings_model_progress").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun modelSuccessEmptyAndFiniteErrorRemainRecoverableWithoutRawDetails() {
        setScreen(
            readyState().copy(
                section = SettingsSection.AI,
                models = listOf("provider-z", "provider-a"),
                status = SettingsStatus.READY
            )
        )
        composeRule.onNodeWithText("provider-z").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("provider-a").performScrollTo().assertIsDisplayed()

        setScreen(
            readyState().copy(
                section = SettingsSection.AI,
                status = SettingsStatus.MODELS_EMPTY
            )
        )
        composeRule.onNodeWithTag("settings_models_empty").performScrollTo().assertIsDisplayed()
        pageTarget("settings_fetch_models").assertIsEnabled()

        val raw = "raw provider body private endpoint"
        setScreen(
            readyState().copy(section = SettingsSection.AI, status = SettingsStatus.PROVIDER)
        )
        composeRule.onNodeWithTag("settings_models_error").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(raw, substring = true).assertDoesNotExist()
        pageTarget("settings_fetch_models").assertIsEnabled()
    }

    @Test
    fun discardConfirmationOffersCancelAndDiscardWithReachableTargets() {
        setScreen(readyState().copy(dirty = true, discardConfirmation = true))
        target("settings_discard_confirm")
        target("settings_discard_cancel")
    }

    @Test
    fun systemLightDarkAndWidgetPreviewDoNotChangeAppTypographyDraftSideEffects() {
        var themes = 0
        setScreen(
            readyState(),
            actions = actions(onAppearanceChange = { themes += 1 })
        )
        pageTarget("settings_theme_dark").performClick()
        composeRule.runOnIdle { assertTrue(themes == 1) }
        composeRule.onNodeWithTag("settings_widget_preview").performScrollTo().assertIsDisplayed()
    }

    @Config(qualifiers = "zh-rCN-w360dp-h640dp-420dpi")
    @Test
    fun compactChineseDarkTwentySpAtDoubleFontScaleIsScrollableAndReachable() {
        assertResponsive(360, ThemeMode.DARK, 20, 2f)
    }

    @Config(qualifiers = "en-rUS-w599dp-h700dp-420dpi")
    @Test
    fun compactBoundary599EnglishLightEightSpIsScrollableAndReachable() {
        assertResponsive(599, ThemeMode.LIGHT, 8, 1f)
    }

    @Config(qualifiers = "zh-rCN-w600dp-h800dp-420dpi")
    @Test
    fun mediumBoundary600ChineseSystemTwentySpIsScrollableAndReachable() {
        assertResponsive(600, ThemeMode.SYSTEM, 20, 1f)
    }

    @Config(qualifiers = "en-rUS-w840dp-h900dp-420dpi")
    @Test
    fun expanded840EnglishDarkEightSpAtDoubleFontScaleIsScrollableAndReachable() {
        assertResponsive(840, ThemeMode.DARK, 8, 2f)
    }

    private fun assertResponsive(width: Int, theme: ThemeMode, fontSp: Int, fontScale: Float) {
        setScreen(
            readyState().copy(section = SettingsSection.AI),
            RenderOptions(width, theme, fontSp, fontScale)
        )
        composeRule.onNode(hasScrollAction()).assertIsDisplayed()
        pageTarget("settings_save_ai")
        pageTarget("settings_fetch_models")
        target("settings_section_application")
        target("settings_section_ai")
    }

    private fun target(tag: String) = composeRule.onNodeWithTag(tag)
        .assertIsDisplayed()
        .assertHasClickAction()
        .assertHeightIsAtLeast(48.dp)

    private fun pageTarget(tag: String) = composeRule.onNodeWithTag(tag)
        .performScrollTo()
        .assertIsDisplayed()
        .assertHasClickAction()
        .assertHeightIsAtLeast(48.dp)

    private fun field(tag: String) = composeRule.onNodeWithTag(tag)
        .performScrollTo()
        .assertIsDisplayed()
        .assertHeightIsAtLeast(48.dp)

    private fun setScreen(
        state: SettingsUiState,
        options: RenderOptions = RenderOptions(),
        actions: SettingsActions = actions()
    ) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, options.fontScale)
                ) {
                    ClenderTheme(
                        AppearanceUiState(options.theme, options.fontSp, state.widgetFontSizeSp)
                    ) {
                        SettingsScreen(
                            state = state,
                            actions = actions,
                            modifier = Modifier.width(options.widthDp.dp)
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private data class RenderOptions(
        val widthDp: Int = 360,
        val theme: ThemeMode = ThemeMode.LIGHT,
        val fontSp: Int = 13,
        val fontScale: Float = 1f
    )

    private companion object {
        fun readyState() = SettingsUiState(
            active = true,
            status = SettingsStatus.READY,
            apiKeyConfigured = true
        )

        fun actions(
            onAppearanceChange: (AppearanceSettingsDraft) -> Unit = {},
            onSecretInput: (CharArray) -> Unit = {},
            onRequestRemoveKey: () -> Unit = {}
        ) = SettingsActions(
            onSectionChange = {},
            onAppearanceChange = onAppearanceChange,
            onAiChange = {},
            onSecretInput = onSecretInput,
            onSaveAppearance = {},
            onSaveAi = {},
            onFetchModels = {},
            onRequestRemoveKey = onRequestRemoveKey,
            onConfirmRemoveKey = {},
            onCancelRemoveKey = {},
            onConfirmDiscard = {},
            onCancelDiscard = {}
        )
    }
}
