package com.molotov.clender.ui.settings

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.molotov.clender.app.ClenderApplication
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
@Config(sdk = [26, 36], application = ClenderApplication::class, qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlmThinkingHintTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val host = RobolectricComposeHost()

    @Before
    fun startHost() = host.start()

    @After
    fun closeHost() = host.close()

    @Test
    fun exactGlmModelsDoNotShowUnrequestedProviderExplanation() {
        listOf("glm-5.3-flash", "glm-5.3", "GLM-5.3", "GLM-5.3-Flash").forEach { model ->
            show(model)
            composeRule.onNodeWithTag("settings_ai_glm_thinking_hint").assertDoesNotExist()
            composeRule.onNodeWithTag("settings_ai_model_limits").assertDoesNotExist()
            composeRule.onNodeWithText("无法关闭思考", substring = true).assertDoesNotExist()
            composeRule.onNodeWithText("MEDIUM", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun otherModelsDoNotShowGlmRestrictions() {
        listOf("other-model", "glm-5.3-flash-custom", "", "glm-5.3 ").forEach { model ->
            show(model)
            composeRule.onNodeWithTag("settings_ai_glm_thinking_hint").assertDoesNotExist()
            composeRule.onNodeWithTag("settings_ai_model_limits").assertDoesNotExist()
        }
    }

    private fun show(model: String) {
        composeRule.runOnUiThread {
            host.activity.setContent {
                ClenderTheme(AppearanceUiState(ThemeMode.LIGHT, 13, 13)) {
                    SettingsScreen(
                        state = SettingsUiState(
                            section = SettingsSection.AI,
                            ai = AiSettingsDraft(model = model),
                            models = listOf(model)
                        ),
                        actions = actions()
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun actions() = SettingsActions(
        onSectionChange = {},
        onAppearanceChange = {},
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
