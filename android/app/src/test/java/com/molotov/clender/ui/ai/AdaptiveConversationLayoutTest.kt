package com.molotov.clender.ui.ai

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AdaptiveLayoutPolicy
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

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [26, 36],
    application = ClenderApplication::class
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveConversationLayoutTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val composeHost = RobolectricComposeHost()

    @Before
    fun startComposeHost() {
        composeHost.start()
    }

    @After
    fun closeComposeHost() {
        composeHost.close()
    }

    @Test
    fun widths360And599RenderOnePaneInitiallyShowingMessages() {
        listOf(360, 599).forEach { width ->
            setAdaptive(AdaptiveRenderOptions(widthDp = width))

            composeRule.onNodeWithTag("ai_conversation_single_pane").assertIsDisplayed()
            composeRule.onNodeWithTag("ai_conversation_message_pane").assertIsDisplayed()
            composeRule.onNodeWithTag("ai_conversation_list_pane").assertDoesNotExist()
            composeRule.onNodeWithTag("ai_show_conversation_list")
                .assertDoesNotExist()
        }
    }

    @Test
    fun widths600And840RenderConversationListAndMessagesTogether() {
        listOf(600, 840).forEach { width ->
            setAdaptive(AdaptiveRenderOptions(widthDp = width))

            composeRule.onNodeWithTag("ai_conversation_two_pane").assertIsDisplayed()
            composeRule.onNodeWithTag("ai_conversation_list_pane").assertIsDisplayed()
            composeRule.onNodeWithTag("ai_conversation_message_pane").assertIsDisplayed()
            composeRule.onNodeWithTag("ai_show_conversation_list").assertDoesNotExist()
        }
    }

    @Test
    fun compactPaneInputSwitchesListAndBackReturnsToMessagesBeforeSystem() {
        var pane by mutableStateOf(ConversationPane.MESSAGES)
        var systemBacks = 0
        composeHost.activity.onBackPressedDispatcher.addCallback(
            composeHost.activity,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    systemBacks += 1
                }
            }
        )
        setAdaptive(
            options = AdaptiveRenderOptions(widthDp = 360),
            compactPane = { pane },
            onShowConversationList = { pane = ConversationPane.LIST },
            onBackToMessages = { pane = ConversationPane.MESSAGES }
        )

        composeRule.runOnUiThread { pane = ConversationPane.LIST }
        composeRule.onNodeWithTag("ai_conversation_list_pane").assertIsDisplayed()
        composeHost.activity.onBackPressedDispatcher.onBackPressed()

        composeRule.onNodeWithTag("ai_conversation_message_pane").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, systemBacks) }
    }

    @Test
    fun dialogDismissalLeavesCompactListOpenWithoutTriggeringPaneBack() {
        var dialogVisible by mutableStateOf(true)
        var pane by mutableStateOf(ConversationPane.LIST)
        var compactBacks = 0
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                ConversationAdaptiveLayout(
                    layout = AdaptiveLayoutPolicy.forWidthDp(360),
                    compactPane = pane,
                    actions = ConversationAdaptiveActions(
                        onShowConversationList = { pane = ConversationPane.LIST },
                        onBackToMessages = {
                            compactBacks += 1
                            pane = ConversationPane.MESSAGES
                        }
                    ),
                    content = ConversationAdaptiveContent(
                        conversationList = { TestListPane() },
                        messagePane = { TestMessagePane() }
                    )
                )
                if (dialogVisible) {
                    RenameConversationDialog(
                        currentTitle = "Title",
                        onConfirm = {},
                        onDismiss = { dialogVisible = false }
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("conversation_rename_cancel").performClick()

        composeRule.onNodeWithTag("conversation_rename_dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("ai_conversation_list_pane").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, compactBacks) }
    }

    @Config(qualifiers = "zh-rCN-w360dp-h640dp-420dpi")
    @Test
    fun compactChineseLightEightSpRemainsReachableAtDoubleFontScale() {
        setAdaptive(
            AdaptiveRenderOptions(
                widthDp = 360,
                themeMode = ThemeMode.LIGHT,
                appFontSizeSp = 8,
                fontScale = 2f
            )
        )

        composeRule.onNodeWithTag("ai_show_conversation_list")
            .assertDoesNotExist()
        composeRule.onNodeWithTag("ai_conversation_message_pane").assertIsDisplayed()
    }

    @Config(qualifiers = "en-rUS-w599dp-h800dp-420dpi")
    @Test
    fun compactEnglishDarkTwentySpRemainsReachableAtDoubleFontScale() {
        setAdaptive(
            AdaptiveRenderOptions(
                widthDp = 599,
                themeMode = ThemeMode.DARK,
                appFontSizeSp = 20,
                fontScale = 2f
            )
        )

        composeRule.onNodeWithTag("ai_show_conversation_list")
            .assertDoesNotExist()
        composeRule.onNodeWithTag("ai_conversation_message_pane").assertIsDisplayed()
    }

    @Config(qualifiers = "zh-rCN-w600dp-h800dp-420dpi")
    @Test
    fun mediumSystemThemeAtTwentySpAndDoubleFontScaleKeepsBothPanesReachable() {
        setAdaptive(
            AdaptiveRenderOptions(
                widthDp = 600,
                themeMode = ThemeMode.SYSTEM,
                appFontSizeSp = 20,
                fontScale = 2f
            )
        )

        composeRule.onNodeWithTag("ai_conversation_list_pane").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_conversation_message_pane").assertIsDisplayed()
    }

    @Config(qualifiers = "en-rUS-w840dp-h900dp-420dpi")
    @Test
    fun expandedLightEightSpKeepsBothPanesReachable() {
        setAdaptive(
            AdaptiveRenderOptions(
                widthDp = 840,
                themeMode = ThemeMode.LIGHT,
                appFontSizeSp = 8
            )
        )

        composeRule.onNodeWithTag("ai_conversation_list_pane").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_conversation_message_pane").assertIsDisplayed()
    }

    private fun setAdaptive(
        options: AdaptiveRenderOptions,
        compactPane: () -> ConversationPane = { ConversationPane.MESSAGES },
        onShowConversationList: () -> Unit = {},
        onBackToMessages: () -> Unit = {}
    ) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, options.fontScale)
                ) {
                    ClenderTheme(
                        AppearanceUiState(
                            options.themeMode,
                            options.appFontSizeSp,
                            widgetFontSizeSp = 13
                        )
                    ) {
                        ConversationAdaptiveLayout(
                            layout = AdaptiveLayoutPolicy.forWidthDp(options.widthDp),
                            compactPane = compactPane(),
                            actions = ConversationAdaptiveActions(
                                onShowConversationList,
                                onBackToMessages
                            ),
                            content = ConversationAdaptiveContent(
                                conversationList = { TestListPane() },
                                messagePane = { TestMessagePane() }
                            ),
                            modifier = Modifier.width(options.widthDp.dp)
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @androidx.compose.runtime.Composable
    private fun TestListPane() {
        Text("Conversation list", Modifier.testTag("adaptive_test_list_content"))
    }

    @androidx.compose.runtime.Composable
    private fun TestMessagePane() {
        Text("Messages", Modifier.testTag("adaptive_test_message_content"))
    }

    private data class AdaptiveRenderOptions(
        val widthDp: Int,
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val appFontSizeSp: Int = 13,
        val fontScale: Float = 1f
    )
}
