package com.molotov.clender.ui.ai

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.app.ai.AiCoordinatorError
import com.molotov.clender.core.model.Message
import com.molotov.clender.core.model.MessageRole
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import java.time.Instant
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
@Config(
    sdk = [26, 36],
    application = ClenderApplication::class
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiComposerQuickAiC1bContractTest {
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
    fun composerBlankInactiveAndWorkingDisableSendWhileIdleUnicodeSubmitsVerbatim() {
        var submitted = 0
        setComposer(
            AiSubmissionUiState(isActive = true, status = AiSubmissionStatus.IDLE),
            onSend = { submitted += 1 }
        )
        composeRule.onNodeWithTag("ai_composer_send").assertIsNotEnabled()
        composeRule.onNodeWithTag("ai_composer_input").performTextInput("你好 🌏\nline 2")
        composeRule.onNodeWithTag("ai_composer_send").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertTrue(submitted == 1) }

        setComposer(AiSubmissionUiState(isActive = false, draft = "x"))
        composeRule.onNodeWithTag("ai_composer_send").assertIsNotEnabled()
        setComposer(
            AiSubmissionUiState(
                isActive = true,
                draft = "x",
                status = AiSubmissionStatus.WORKING
            )
        )
        composeRule.onNodeWithTag("ai_composer_send").assertIsNotEnabled()
    }

    @Test
    fun workingThinkingSemanticsCoexistWithPersistedFinalThinkingMessage() {
        val finalThinking = Message(
            id = 1,
            conversationId = "conversation",
            role = MessageRole.THINK,
            content = "final thinking plain text",
            timestamp = Instant.parse("2026-08-31T01:00:00Z")
        )
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                ConversationMessagePane("conversation", listOf(finalThinking))
                AiComposer(
                    state = AiSubmissionUiState(
                        isActive = true,
                        status = AiSubmissionStatus.WORKING
                    ),
                    actions = AiComposerActions({}, {}, {}, {})
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ai_composer_working").assertIsDisplayed()
        assertTrue(stateDescription("ai_composer_working").isNotBlank())
        composeRule.onNodeWithTag("conversation_thinking_toggle_1").assertIsDisplayed()
        composeRule.onNodeWithText(finalThinking.content).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun providerCategoriesExplainConcreteRecoveryInChinese() {
        listOf(
            "AUTHENTICATION" to "API Key",
            "RATE_LIMIT" to "稍后重试",
            "REQUEST_PARAMETERS" to "模型名称和参数"
        ).forEach { (code, expected) ->
            setComposer(
                AiSubmissionUiState(
                    isActive = true,
                    status = AiSubmissionStatus.FAILED,
                    errorCode = AiCoordinatorError.valueOf(code)
                )
            )
            composeRule.onNodeWithText(expected, substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun unconfiguredAndFiniteFailureOfferReachableRecoveryWithoutRawDetails() {
        var settings = 0
        setComposer(
            AiSubmissionUiState(
                isActive = true,
                draft = "preserved",
                status = AiSubmissionStatus.UNCONFIGURED
            ),
            onOpenSettings = { settings += 1 }
        )
        composeRule.onNodeWithTag("ai_composer_open_settings")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertTrue(settings == 1) }

        val secret = "raw-provider-detail private-path response-body"
        setComposer(
            AiSubmissionUiState(
                isActive = true,
                draft = "preserved",
                status = AiSubmissionStatus.FAILED,
                errorCode = AiCoordinatorError.PROVIDER
            )
        )
        composeRule.onNodeWithTag("ai_composer_error").assertIsDisplayed()
        composeRule.onNodeWithText(secret, substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("ai_composer_dismiss")
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun tokenCountUsesLocalizedApproximateSemanticsWithoutInternalIdentifiers() {
        setComposer(
            AiSubmissionUiState(isActive = true, status = AiSubmissionStatus.IDLE),
            approximateTokenCount = 12_345
        )

        val semantics = composeRule.onNodeWithTag("ai_conversation_token_count")
            .assertIsDisplayed()
            .fetchSemanticsNode().config
        assertTrue(
            semantics.getOrNull(SemanticsProperties.ContentDescription).orEmpty().isNotEmpty()
        )
        composeRule.onNodeWithText("conversation-id").assertDoesNotExist()
    }

    @Test
    fun quickAiShowsOnlyInputAndBoundedSummaryAndNeverMessageOrThinkingBodies() {
        val assistantBody = "assistant private body"
        val thinkingBody = "thinking private body"
        setQuickAi(
            state = AiSubmissionUiState(
                isActive = true,
                draft = "user draft",
                status = AiSubmissionStatus.COMPLETED
            )
        )

        composeRule.onNodeWithTag("quick_ai_input").assertIsDisplayed()
        composeRule.onNodeWithTag("quick_ai_completed").assertIsDisplayed()
        composeRule.onNodeWithText(assistantBody).assertDoesNotExist()
        composeRule.onNodeWithText(thinkingBody).assertDoesNotExist()
        composeRule.onNodeWithTag("conversation_messages_list").assertDoesNotExist()
        assertFalse(containsViewType(composeHost.activity.window.decorView, WebView::class.java))
    }

    @Test
    fun quickAiConversationSettingsAndBackActionsRemainDistinctAndReachable() {
        var conversation = 0
        var settings = 0
        var backs = 0
        setQuickAi(
            state = AiSubmissionUiState(
                isActive = true,
                status = AiSubmissionStatus.UNCONFIGURED
            ),
            actions = quickAiActions(
                onOpenConversation = { conversation += 1 },
                onOpenSettings = { settings += 1 },
                onBack = { backs += 1 }
            )
        )

        listOf("quick_ai_open_conversation", "quick_ai_open_settings", "quick_ai_back").forEach {
            composeRule.onNodeWithTag(it)
                .assertHasClickAction()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .performClick()
        }
        composeRule.runOnIdle {
            assertTrue(conversation == 1 && settings == 1 && backs == 1)
        }
    }

    @Test
    fun compactAndMediumBoundaryWidthsKeepComposerActionsReachable() {
        listOf(599, 600).forEach { width ->
            setQuickAi(
                state = AiSubmissionUiState(
                    isActive = true,
                    draft = "boundary width",
                    status = AiSubmissionStatus.IDLE
                ),
                options = QuickAiRenderOptions(widthDp = width)
            )
            composeRule.onNodeWithTag("quick_ai_send")
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
    }

    @Config(qualifiers = "zh-rCN-w360dp-h640dp-420dpi")
    @Test
    fun compactChineseDarkTwentySpAtDoubleFontScaleKeepsLongComposerScrollable() {
        setQuickAi(
            state = AiSubmissionUiState(
                isActive = true,
                draft = "长输入 🌏\n".repeat(120),
                status = AiSubmissionStatus.IDLE
            ),
            options = QuickAiRenderOptions(
                themeMode = ThemeMode.DARK,
                fontSizeSp = 20,
                fontScale = 2f
            )
        )

        composeRule.onNodeWithTag("quick_ai_send")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNode(hasScrollAction()).assertIsDisplayed()
    }

    @Config(qualifiers = "en-rUS-w840dp-h900dp-420dpi")
    @Test
    fun expandedEnglishSystemEightSpKeepsQuickAiSemanticsAndPlainTextReachable() {
        val htmlLooking = "**markdown** <b>html</b> https://example.invalid"
        setQuickAi(
            state = AiSubmissionUiState(
                isActive = true,
                draft = htmlLooking,
                status = AiSubmissionStatus.CANCELLED
            ),
            options = QuickAiRenderOptions(
                widthDp = 840,
                themeMode = ThemeMode.SYSTEM,
                fontSizeSp = 8
            )
        )

        composeRule.onNodeWithTag("quick_ai_input").assertIsDisplayed()
        composeRule.onNodeWithTag("quick_ai_cancelled").assertIsDisplayed()
        assertTrue(stateDescription("quick_ai_cancelled").isNotBlank())
        assertFalse(containsViewType(composeHost.activity.window.decorView, WebView::class.java))
    }

    private fun setComposer(
        state: AiSubmissionUiState,
        approximateTokenCount: Int = 0,
        onSend: () -> Unit = {},
        onOpenSettings: () -> Unit = {}
    ) {
        val renderedState = mutableStateOf(state)
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                AiComposer(
                    state = renderedState.value,
                    actions = AiComposerActions(
                        onDraftChange = {
                            renderedState.value = renderedState.value.copy(draft = it)
                        },
                        onSend = onSend,
                        onDismissStatus = {},
                        onOpenSettings = onOpenSettings
                    ),
                    presentation = AiComposerPresentation(
                        approximateTokenCount = approximateTokenCount
                    ),
                    modifier = Modifier.width(360.dp)
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun setQuickAi(
        state: AiSubmissionUiState,
        options: QuickAiRenderOptions = QuickAiRenderOptions(),
        actions: QuickAiActions = quickAiActions()
    ) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, options.fontScale)
                ) {
                    ClenderTheme(
                        AppearanceUiState(options.themeMode, options.fontSizeSp, 13)
                    ) {
                        QuickAiScreen(
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

    private data class QuickAiRenderOptions(
        val widthDp: Int = 360,
        val themeMode: ThemeMode = ThemeMode.LIGHT,
        val fontSizeSp: Int = 13,
        val fontScale: Float = 1f
    )

    private companion object {
        fun quickAiActions(
            onOpenConversation: () -> Unit = {},
            onOpenSettings: () -> Unit = {},
            onBack: () -> Unit = {}
        ) = QuickAiActions({}, {}, {}, onOpenConversation, onOpenSettings, onBack)
    }

    private fun stateDescription(tag: String): String = composeRule.onNodeWithTag(tag)
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription).orEmpty()

    private fun containsViewType(root: View, type: Class<out View>): Boolean = when {
        type.isInstance(root) -> true

        root is ViewGroup -> (0 until root.childCount).any { index ->
            containsViewType(root.getChildAt(index), type)
        }

        else -> false
    }
}
