package com.molotov.clender.ui.ai

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationEntryLayoutRegressionTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private val fixture = ConversationEntryTestHost()

    @Before
    fun start() = fixture.start()

    @After
    fun close() = fixture.close()

    @Test
    fun compactEntryDoesNotOverlapFirstMessageAndOccupiesAppBarRightEdge() {
        render()
        assertEntryGeometry()
    }

    @Config(qualifiers = "en-rUS-w320dp-h800dp-mdpi")
    @Test
    fun narrowEntryAtMaximumFontAndBothThemesDoesNotCoverMessages() {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
            render(theme, 20, 2f)
            assertEntryGeometry()
        }
    }

    @Test
    fun minimumFontKeepsEntryAccessible() {
        render(ThemeMode.DARK, 8)
        assertEntryGeometry()
    }

    @Config(qualifiers = "en-rUS-w320dp-h800dp-mdpi")
    @Test
    fun englishAppBarTitleAtMaximumFontStaysSingleLineWithoutVerticalOverflow() {
        assertMaximumFontTitleLayout()
    }

    @Config(qualifiers = "zh-rCN-w320dp-h800dp-mdpi")
    @Test
    fun chineseAppBarTitleAtMaximumFontStaysSingleLineWithoutVerticalOverflow() {
        assertMaximumFontTitleLayout()
    }

    private fun assertMaximumFontTitleLayout() {
        render(ThemeMode.DARK, 20, 2f)
        val title = fixture.host.activity.getString(R.string.screen_ai)
        val layouts = mutableListOf<TextLayoutResult>()
        val titleNode = composeRule.onNode(
            hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            useUnmergedTree = true
        ).assertIsDisplayed()
        titleNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue("Appbar title must expose its actual text layout", action(layouts))
        }
        assertEquals("Exactly one title layout is expected", 1, layouts.size)
        val layout = layouts.single()
        assertEquals("Title must not wrap inside fixed-height appbar: $title", 1, layout.lineCount)
        val lineTop = layout.getLineTop(0)
        val lineBottom = layout.getLineBottom(0)
        val bounds = titleNode.fetchSemanticsNode().boundsInRoot
        val content = composeRule.onNodeWithTag("clender_app_content")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Title line starts outside text layout: $lineTop", lineTop >= 0f)
        assertTrue(
            "Title line exceeds text layout: $lineBottom / ${layout.size.height}",
            lineBottom <= layout.size.height
        )
        assertTrue(
            "Title bounds cannot contain rendered line: $bounds / $lineTop..$lineBottom",
            bounds.height >= lineBottom - lineTop
        )
        assertTrue(
            "Title crosses appbar content boundary: $bounds / $content",
            bounds.bottom <= content.top
        )
        assertEntryGeometry()
    }

    @Test
    fun actualTopEntryOpensListAndReturnRestoresMessagePane() {
        render()
        assertEntryGeometry()
        composeRule.onNodeWithTag("ai_show_conversation_list").performClick()
        composeRule.onNodeWithTag("ai_conversation_list_pane").assertIsDisplayed()
        assertEquals(ConversationPane.LIST, fixture.conversations.state.value.compactPane)
        composeRule.onNodeWithTag("ai_back_to_messages").assertHasClickAction()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithTag("conversation_message_assistant_1").assertIsDisplayed()
        assertEquals(ConversationPane.MESSAGES, fixture.conversations.state.value.compactPane)
        composeRule.onNodeWithTag("ai_show_conversation_list").performClick()
        composeRule.onNodeWithTag("ai_conversation_list_pane").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(ConversationLoadStatus.READY, fixture.conversations.state.value.loadStatus)
            assertEquals(ConversationPane.LIST, fixture.conversations.state.value.compactPane)
        }
        composeRule.runOnUiThread { fixture.host.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("conversation_message_assistant_1").assertIsDisplayed()
    }

    @Config(qualifiers = "en-rUS-w840dp-h900dp-mdpi")
    @Test
    fun wideLayoutKeepsBothPanesAndNoRedundantCompactEntry() {
        render()
        val list = composeRule.onNodeWithTag("ai_conversation_list_pane").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val message = composeRule.onNodeWithTag("ai_conversation_message_pane").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Wide panes overlap: $list / $message", list.right <= message.left)
        composeRule.onNodeWithTag("ai_show_conversation_list").assertDoesNotExist()
    }

    @Test
    fun loadingAndFailureKeepBoundedStatusAndRetryRecovers() {
        val gate = CompletableDeferred<Unit>()
        fixture.repository.loadGate = gate
        render()
        composeRule.onNodeWithTag("conversation_loading").assertIsDisplayed()
        fixture.repository.failLoad = true
        composeRule.runOnUiThread { gate.complete(Unit) }
        composeRule.onNodeWithTag("conversation_error").assertIsDisplayed()
        fixture.repository.failLoad = false
        composeRule.onNodeWithTag("conversation_retry").performClick()
        assertEntryGeometry()
    }

    @Test
    fun emptyRepositoryInitializesConversationAndRetainsUsableEntry() {
        fixture.repository.empty = true
        render()
        assertEquals(ConversationLoadStatus.READY, fixture.conversations.state.value.loadStatus)
        assertEntryGeometry()
    }

    @Test
    fun appBarEntryCreatesAndSelectsOtherAndCurrentConversation() {
        render()
        val original = fixture.conversations.state.value.activeConversation!!.id
        composeRule.onNodeWithTag("ai_show_conversation_list").performClick()
        composeRule.onNodeWithTag("conversation_create").performClick()
        composeRule.waitForIdle()
        val created = fixture.conversations.state.value.activeConversation!!.id
        assertTrue(original != created)
        composeRule.onNodeWithTag("ai_show_conversation_list").performClick()
        composeRule.onNodeWithTag("conversation_row_$original").performScrollTo().performClick()
        assertEquals(original, fixture.conversations.state.value.activeConversation?.id)
        composeRule.onNodeWithTag("ai_show_conversation_list").performClick()
        composeRule.onNodeWithTag("conversation_row_$original").performScrollTo().performClick()
        composeRule.onNodeWithTag("conversation_message_assistant_1").assertIsDisplayed()
        assertEquals(ConversationPane.MESSAGES, fixture.conversations.state.value.compactPane)
    }

    @Test
    fun drawerAndConfirmationBackTakePriorityOverConversationList() {
        render()
        composeRule.onNodeWithTag("ai_show_conversation_list").performClick()
        composeRule.onNodeWithTag("clender_open_navigation_drawer").performClick()
        composeRule.onNodeWithTag("clender_drawer_ai").assertIsDisplayed()
        composeRule.runOnUiThread { fixture.host.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("clender_drawer_ai").assertIsNotDisplayed()
        assertEquals(ConversationPane.LIST, fixture.conversations.state.value.compactPane)
        val id = fixture.conversations.state.value.activeConversation!!.id
        composeRule.onNodeWithTag("conversation_clear_$id").performScrollTo().performClick()
        composeRule.onNodeWithTag("conversation_clear_dialog").assertIsDisplayed()
        composeRule.runOnUiThread {
            org.robolectric.shadows.ShadowDialog.getLatestDialog().onBackPressed()
        }
        composeRule.onNodeWithTag("conversation_clear_dialog").assertDoesNotExist()
        assertEquals(ConversationPane.LIST, fixture.conversations.state.value.compactPane)
        assertEquals(0, fixture.repository.clearCalls)
        composeRule.onNodeWithTag("conversation_clear_$id").performScrollTo().performClick()
        composeRule.onNodeWithTag("conversation_clear_confirm").performClick()
        composeRule.waitForIdle()
        assertEquals(1, fixture.repository.clearCalls)
    }

    private fun render(theme: ThemeMode = ThemeMode.LIGHT, font: Int = 13, fontScale: Float = 1f) {
        composeRule.runOnUiThread { fixture.render(AppearanceUiState(theme, font, 13), fontScale) }
        composeRule.waitForIdle()
    }

    private fun assertEntryGeometry() {
        val entry = composeRule.onNodeWithTag("ai_show_conversation_list").assertIsDisplayed()
            .assertHasClickAction().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode().boundsInRoot
        val message = composeRule.onNodeWithTag("conversation_message_assistant_1")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val drawer = composeRule.onNodeWithTag("clender_open_navigation_drawer")
            .fetchSemanticsNode().boundsInRoot
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Entry overlaps first message: $entry / $message", entry.bottom <= message.top)
        assertTrue(
            "Entry must share appbar row: $entry / $drawer",
            entry.top < drawer.bottom && drawer.top < entry.bottom
        )
        assertTrue("Entry must be at right edge: $entry / $root", entry.left > root.center.x)
    }
}
