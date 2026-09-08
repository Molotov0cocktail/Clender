package com.molotov.clender.ui.ai

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.core.model.Message
import com.molotov.clender.core.model.MessageRole
import com.molotov.clender.testsupport.RobolectricComposeHost
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class ConversationMessagePaneTest {
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
    fun userAssistantAndThinkingHaveDistinctBoundedRoleSemantics() {
        val messages = listOf(
            message(1, MessageRole.USER, "user body", "2026-08-31T01:00:00Z"),
            message(2, MessageRole.ASSISTANT, "assistant body", "2026-08-31T02:00:00Z"),
            message(3, MessageRole.THINK, "thinking body", "2026-08-31T03:00:00Z")
        )

        setMessageContent(messages = messages)

        val descriptions = messages.associate { current ->
            current.id to roleDescription(current)
        }
        assertTrue(descriptions.values.all(String::isNotBlank))
        assertEquals(3, descriptions.values.toSet().size)
        assertFalse(descriptions.values.any { it.contains("${messages.first().id}") })
        composeRule.onNodeWithTag("conversation_message_user_1").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_message_assistant_2").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_message_think_3").assertIsDisplayed()
    }

    @Test
    fun thinkingIsCollapsedByDefaultAndEachMessageTogglesIndependently() {
        val first = message(11, MessageRole.THINK, "first private trace")
        val second = message(12, MessageRole.THINK, "second private trace")
        setMessageContent(messages = listOf(first, second))

        composeRule.onNodeWithText(first.content).assertDoesNotExist()
        composeRule.onNodeWithText(second.content).assertDoesNotExist()
        val collapsed = thinkingState(11)
        composeRule.onNodeWithTag("conversation_thinking_toggle_11")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        composeRule.onNodeWithTag("conversation_thinking_toggle_11").performClick()

        composeRule.onNodeWithText(first.content).assertIsDisplayed()
        composeRule.onNodeWithText(second.content).assertDoesNotExist()
        assertNotEquals(collapsed, thinkingState(11))

        composeRule.onNodeWithTag("conversation_thinking_toggle_11").performClick()
        composeRule.onNodeWithText(first.content).assertDoesNotExist()
        assertEquals(collapsed, thinkingState(11))
    }

    @Test
    fun changingConversationResetsEveryInMemoryThinkingExpansion() {
        val thinking = message(21, MessageRole.THINK, "expanded only in first conversation")
        var activeConversation by mutableStateOf("first-conversation")

        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                ConversationMessagePane(
                    conversationId = activeConversation,
                    messages = listOf(thinking),
                    modifier = Modifier.width(360.dp)
                )
            }
        }
        composeRule.onNodeWithTag("conversation_thinking_toggle_21").performClick()
        composeRule.onNodeWithText(thinking.content).assertIsDisplayed()

        composeRule.runOnUiThread { activeConversation = "second-conversation" }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(thinking.content).assertDoesNotExist()
    }

    @Test
    fun unmatchedBoldMarkerAndUserInputRemainVerbatim() {
        val content = "保留 **未闭合 <script>text</script>"
        setMessageContent(messages = listOf(message(29, MessageRole.ASSISTANT, content)))
        composeRule.onNodeWithText(content).assertIsDisplayed()
        setMessageContent(messages = listOf(message(29, MessageRole.USER, "**原始输入**")))
        composeRule.onNodeWithText("**原始输入**").assertIsDisplayed()
    }

    @Test
    fun assistantBoldFormattingRemovesMarkersAndRetainsSafeLiteralHtmlAndLinks() {
        setMessageContent(
            messages = listOf(
                message(30, MessageRole.ASSISTANT, "- **周六课程**\n<b>文字</b> https://example.invalid")
            )
        )
        val node = composeRule.onNodeWithText("- 周六课程\n<b>文字</b> https://example.invalid")
            .assertIsDisplayed()
            .fetchSemanticsNode()
        val text = node.config[SemanticsProperties.Text].single()
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertFalse(node.config.contains(SemanticsActions.OnClick))
    }

    @Test
    fun unicodeEmojiCombiningCharactersAndLineBreaksRemainVerbatim() {
        val content = "第一行 🌏\nSecond line e\u0301\n第三行"
        setMessageContent(messages = listOf(message(31, MessageRole.ASSISTANT, content)))

        composeRule.onNodeWithText(content, substring = false).assertIsDisplayed()
    }

    @Test
    fun emptyConversationHasAReachableBoundedEmptyState() {
        setMessageContent(messages = emptyList())

        composeRule.onNodeWithTag("conversation_messages_empty").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_messages_list").assertDoesNotExist()
    }

    @Test
    fun messagesAreSortedByTimestampThenIdAndLongHistoryRemainsScrollable() {
        val messages = buildList {
            add(message(42, MessageRole.ASSISTANT, "same timestamp second", "2026-08-31T01:00:00Z"))
            add(message(41, MessageRole.USER, "same timestamp first", "2026-08-31T01:00:00Z"))
            repeat(24) { index ->
                add(
                    message(
                        id = 100L + index,
                        role = MessageRole.ASSISTANT,
                        content = "long history $index " + "长文本 ".repeat(24),
                        timestamp = "2026-08-31T02:00:00Z"
                    )
                )
            }
        }
        setMessageContent(messages = messages)

        val firstTop = composeRule.onNodeWithTag("conversation_message_user_41")
            .fetchSemanticsNode().boundsInRoot.top
        val secondTop = composeRule.onNodeWithTag("conversation_message_assistant_42")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(firstTop < secondTop)
        composeRule.onNode(hasScrollAction()).assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_messages_list")
            .performScrollToNode(hasTestTag("conversation_message_assistant_123"))
        composeRule.onNodeWithTag("conversation_message_assistant_123").assertIsDisplayed()
    }

    @Test
    fun urlAndHtmlLookingContentStayPlainTextWithoutWebViewOrInternalIds() {
        val internalConversationId = "0123456789abcdef0123456789abcdef"
        val internalMessageId = 987_654_321L
        val content = "https://example.invalid/private?q=1 <b>plain</b>"
        setMessageContent(
            conversationId = internalConversationId,
            messages = listOf(message(internalMessageId, MessageRole.USER, content))
        )

        val textNode = composeRule.onNodeWithText(content, substring = false)
            .assertIsDisplayed()
            .fetchSemanticsNode()
        assertFalse(textNode.config.contains(SemanticsActions.OnClick))
        composeRule.onNodeWithText(internalConversationId).assertDoesNotExist()
        composeRule.onNodeWithText(internalMessageId.toString()).assertDoesNotExist()
        assertFalse(containsViewType(composeHost.activity.window.decorView, WebView::class.java))
    }

    private fun setMessageContent(
        conversationId: String = "conversation-under-test",
        messages: List<Message>
    ) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                ConversationMessagePane(
                    conversationId = conversationId,
                    messages = messages,
                    modifier = Modifier.width(360.dp)
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun roleDescription(message: Message): String = composeRule
        .onNodeWithTag(
            when (message.role) {
                MessageRole.USER -> "conversation_message_user_${message.id}"
                MessageRole.ASSISTANT -> "conversation_message_assistant_${message.id}"
                MessageRole.THINK -> "conversation_message_think_${message.id}"
            }
        )
        .fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ContentDescription)
        ?.joinToString(" ")
        .orEmpty()

    private fun thinkingState(id: Long): String = composeRule
        .onNodeWithTag("conversation_thinking_toggle_$id")
        .fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.StateDescription)
        .orEmpty()

    private fun message(
        id: Long,
        role: MessageRole,
        content: String,
        timestamp: String = "2026-08-31T01:00:00Z"
    ): Message = Message(
        id = id,
        conversationId = "conversation-under-test",
        role = role,
        content = content,
        timestamp = Instant.parse(timestamp)
    )

    private fun containsViewType(root: View, type: Class<out View>): Boolean = when {
        type.isInstance(root) -> true

        root is ViewGroup -> (0 until root.childCount).any { index ->
            containsViewType(root.getChildAt(index), type)
        }

        else -> false
    }
}
