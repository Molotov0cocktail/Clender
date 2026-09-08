package com.molotov.clender.ui.ai

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.app.ai.AiContextUsage
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import org.junit.After
import org.junit.Assert.assertTrue
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
class AiComposerFeedbackLayoutTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private val host = RobolectricComposeHost()
    private val feedback = "已实际完成 2 项日程操作。"

    @Before
    fun start() = host.start()

    @After
    fun close() = host.close()

    @Test
    fun contextRatioAndCumulativeTokensRemainVisibleWithoutSingleRequestStatus() {
        render(AiSubmissionStatus.IDLE, context = AiContextUsage("a", 2000, 8000))
        composeRule.onNodeWithText("上次上下文 ~2000 / 8000 · 25%").assertIsDisplayed()
        composeRule.onNodeWithText("累计 Token：12345").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_composer_completed").assertDoesNotExist()
    }

    @Test
    fun newConversationShowsUnknownContextInsteadOfInventedZero() {
        render(AiSubmissionStatus.IDLE)
        composeRule.onNodeWithText("上次上下文：尚未估算").assertIsDisplayed()
    }

    @Test
    fun actualFeedbackIsAboveInputAndCumulativeTokensAreBelowOnTheSendLeft() {
        render(AiSubmissionStatus.COMPLETED)
        val status = composeRule.onNodeWithText(feedback).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val input = composeRule.onNodeWithTag("ai_composer_input").fetchSemanticsNode().boundsInRoot
        val token = composeRule.onNodeWithTag("ai_conversation_token_count")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val send = composeRule.onNodeWithTag("ai_composer_send").fetchSemanticsNode().boundsInRoot
        assertTrue(status.bottom <= input.top)
        assertTrue(token.top >= input.bottom)
        assertTrue(token.right <= send.left)
        composeRule.onNodeWithText("累计 Token：12345").assertIsDisplayed()
    }

    @Test
    fun failedStateCanShowSpecificFeedbackAndWorkingOrDismissedStatesHideIt() {
        render(AiSubmissionStatus.FAILED)
        composeRule.onNodeWithText(feedback).assertIsDisplayed()
        listOf(AiSubmissionStatus.WORKING, AiSubmissionStatus.IDLE).forEach { status ->
            render(status)
            composeRule.onNodeWithText(feedback).assertDoesNotExist()
        }
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun narrowEnglishDoubleFontKeepsLargeTokenCountSeparateFromSendInBothThemes() {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
            render(AiSubmissionStatus.IDLE, Int.MAX_VALUE, theme, 2f)
            val token = composeRule.onNodeWithTag("ai_conversation_token_count")
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val send = composeRule.onNodeWithTag("ai_composer_send")
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                .fetchSemanticsNode().boundsInRoot
            assertTrue(token.right <= send.left)
        }
    }

    @Test
    fun negativeTokenCountRemainsHiddenForQuickAi() {
        render(AiSubmissionStatus.IDLE, -1)
        composeRule.onNodeWithTag("ai_conversation_token_count").assertDoesNotExist()
        composeRule.onNodeWithTag("ai_composer_send").assertIsDisplayed()
    }

    private fun render(
        status: AiSubmissionStatus,
        tokens: Int = 12345,
        theme: ThemeMode = ThemeMode.LIGHT,
        fontScale: Float = 1f,
        context: AiContextUsage? = null
    ) {
        composeRule.runOnUiThread {
            host.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale)
                ) {
                    ClenderTheme(AppearanceUiState(theme, 13, 13)) {
                        AiComposer(
                            AiSubmissionUiState(isActive = true, draft = "test", status = status),
                            AiComposerActions({}, {}, {}, {}),
                            Modifier.width(280.dp),
                            AiComposerPresentation(
                                approximateTokenCount = tokens,
                                executionFeedback = feedback,
                                contextUsage = context
                            )
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }
}
