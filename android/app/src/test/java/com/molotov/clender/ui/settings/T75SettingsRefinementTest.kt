package com.molotov.clender.ui.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.data.network.ai.AiModelCapabilities
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T75 settings refinement contract: redundant AI explanation texts are removed, the
 * three settings tabs group their content into Material3 cards with stable group
 * test tags, the theme choice stays a single segmented row, and the shortened
 * reminder/alarm/background hints render in both locales.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class, qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class T75SettingsRefinementTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val composeHost = RobolectricComposeHost()

    @Before
    fun startHost() = composeHost.start()

    @After
    fun closeHost() = composeHost.close()

    @Test
    fun aiSectionShowsNeitherEmbeddedContractNorCapabilityHint() {
        setScreen(
            readyState().copy(
                section = SettingsSection.AI,
                ai = AiSettingsDraft(model = "synthetic-model"),
                models = listOf("synthetic-model"),
                modelCapabilities = mapOf(
                    "synthetic-model" to AiModelCapabilities(128000, 4096)
                )
            )
        )
        composeRule.onNodeWithTag("settings_ai_model_limits").assertDoesNotExist()
        composeRule.onNodeWithText("日程操作指令已内嵌", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("已填入供应商", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("供应商未提供", substring = true).assertDoesNotExist()
    }

    @Test
    fun applicationSectionGroupsAppearanceBackgroundAlarmAndPermissions() {
        setScreen(readyState())
        listOf(
            "settings_group_appearance",
            "settings_group_background",
            "settings_group_alarm_sound",
            "settings_group_alert_permissions"
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).assertExists()
        }
    }

    @Test
    fun aiSectionGroupsConnectionGenerationAndPrompt() {
        setScreen(readyState().copy(section = SettingsSection.AI))
        listOf(
            "settings_group_ai_connection",
            "settings_group_ai_generation",
            "settings_group_ai_prompt"
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).assertExists()
        }
    }

    @Test
    fun webDavSectionGroupsConnectionAndOperations() {
        setScreen(readyState().copy(section = SettingsSection.WEBDAV))
        composeRule.onNodeWithTag("settings_group_webdav_connection").assertExists()
        composeRule.onNodeWithTag("settings_group_webdav_operations").assertExists()
    }

    @Test
    fun themeChoicesStayClickableInOneSegmentedRow() {
        val selections = mutableListOf<ThemeMode>()
        setScreen(
            readyState(),
            actions = actions(onAppearanceChange = { selections += it.themeMode })
        )
        val tops = listOf(
            "settings_theme_system",
            "settings_theme_light",
            "settings_theme_dark"
        ).map { tag ->
            composeRule.onNodeWithTag(tag)
                .performScrollTo()
                .assertIsDisplayed()
                .assertHasClickAction()
                .assertHeightIsAtLeast(48.dp)
                .fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(tops.first(), tops[1], 1f)
        assertEquals(tops.first(), tops[2], 1f)

        composeRule.onNodeWithTag("settings_theme_light").performClick()
        composeRule.onNodeWithTag("settings_theme_dark").performClick()
        composeRule.onNodeWithTag("settings_theme_system").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM), selections)
        }
    }

    @Test
    fun applicationSectionRendersShortenedChineseHints() {
        setScreen(readyState())
        composeRule.onNodeWithText("下次响铃时生效", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("请允许后台运行与自启动", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun applicationSectionRendersShortenedEnglishHints() {
        setScreen(readyState())
        composeRule.onNodeWithText("Local music takes effect", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Allow background running", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    private fun setScreen(state: SettingsUiState, actions: SettingsActions = actions()) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                ClenderTheme(AppearanceUiState(ThemeMode.LIGHT, 13, state.widgetFontSizeSp)) {
                    SettingsScreen(
                        state = state,
                        actions = actions,
                        modifier = Modifier.width(360.dp)
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        fun readyState() = SettingsUiState(
            active = true,
            status = SettingsStatus.READY,
            apiKeyConfigured = true
        )

        fun actions(onAppearanceChange: (AppearanceSettingsDraft) -> Unit = {}) = SettingsActions(
            onSectionChange = {},
            onAppearanceChange = onAppearanceChange,
            onAiChange = {},
            onSecretInput = {},
            onSaveAppearance = {},
            onSaveAi = {},
            onFetchModels = {},
            onRequestRemoveKey = {},
            onConfirmRemoveKey = {},
            onCancelRemoveKey = {},
            onConfirmDiscard = {},
            onCancelDiscard = {}
        )
    }
}
