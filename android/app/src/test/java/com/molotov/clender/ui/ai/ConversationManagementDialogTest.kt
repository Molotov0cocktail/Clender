package com.molotov.clender.ui.ai

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.testsupport.RobolectricComposeHost
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
class ConversationManagementDialogTest {
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
    fun renameRequiresTrimmedNonBlankTitleAndCancelPerformsZeroWrites() {
        val submitted = mutableListOf<String>()
        var dismissals = 0
        setRename(onConfirm = submitted::add, onDismiss = { dismissals += 1 })

        composeRule.onNodeWithTag("conversation_rename_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_rename_input")
            .performTextReplacement("   ")
        composeRule.onNodeWithTag("conversation_rename_confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("conversation_rename_cancel").performClick()

        composeRule.runOnIdle {
            assertEquals(emptyList<String>(), submitted)
            assertEquals(1, dismissals)
        }
    }

    @Test
    fun renamePreservesUnicodeAndSubmitsTrimmedTitleExactlyOnce() {
        val submitted = mutableListOf<String>()
        setRename(onConfirm = submitted::add)

        composeRule.onNodeWithTag("conversation_rename_input")
            .performTextReplacement("  新标题 e\u0301 🌏  ")
        composeRule.onNodeWithTag("conversation_rename_confirm").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("新标题 e\u0301 🌏"), submitted)
        }
    }

    @Test
    fun clearRequiresExplicitConfirmationAndCancelNeverClears() {
        var confirms = 0
        var dismissals = 0
        setClear(onConfirm = { confirms += 1 }, onDismiss = { dismissals += 1 })

        composeRule.onNodeWithTag("conversation_clear_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_clear_confirm").assertHasClickAction()
        composeRule.onNodeWithTag("conversation_clear_cancel").performClick()

        composeRule.runOnIdle {
            assertEquals(0, confirms)
            assertEquals(1, dismissals)
        }
    }

    @Test
    fun deleteRequiresExplicitDestructiveConfirmationAndCancelNeverDeletes() {
        var confirms = 0
        var dismissals = 0
        setDelete(onConfirm = { confirms += 1 }, onDismiss = { dismissals += 1 })

        composeRule.onNodeWithTag("conversation_delete_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_delete_title").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation_delete_confirm").assertHasClickAction()
        composeRule.onNodeWithTag("conversation_delete_cancel").performClick()

        composeRule.runOnIdle {
            assertEquals(0, confirms)
            assertEquals(1, dismissals)
        }
    }

    @Test
    fun operationInProgressDisablesConfirmationAndPreventsDuplicateWrites() {
        var clearWrites = 0
        setClear(operationInProgress = true, onConfirm = { clearWrites += 1 })
        composeRule.onNodeWithTag("conversation_clear_confirm").assertIsNotEnabled()

        var deleteWrites = 0
        setDelete(operationInProgress = true, onConfirm = { deleteWrites += 1 })
        composeRule.onNodeWithTag("conversation_delete_confirm").assertIsNotEnabled()

        composeRule.runOnIdle {
            assertEquals(0, clearWrites)
            assertEquals(0, deleteWrites)
        }
    }

    @Test
    fun everyDialogActionHasAtLeastFortyEightDpTouchTarget() {
        setRename()
        assertMinimumTouchTarget("conversation_rename_confirm")
        assertMinimumTouchTarget("conversation_rename_cancel")

        setClear()
        assertMinimumTouchTarget("conversation_clear_confirm")
        assertMinimumTouchTarget("conversation_clear_cancel")

        setDelete()
        assertMinimumTouchTarget("conversation_delete_confirm")
        assertMinimumTouchTarget("conversation_delete_cancel")
    }

    @Config(qualifiers = "zh-rCN")
    @Test
    fun destructiveDialogsExposeChineseLabels() {
        setDelete()

        composeRule.onNodeWithText("删除对话").assertIsDisplayed()
        composeRule.onNodeWithText("取消").assertIsDisplayed()
    }

    @Config(qualifiers = "en-rUS")
    @Test
    fun destructiveDialogsExposeEnglishLabels() {
        setDelete()

        composeRule.onNodeWithText("Delete conversation").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    private fun setRename(
        operationInProgress: Boolean = false,
        onConfirm: (String) -> Unit = {},
        onDismiss: () -> Unit = {}
    ) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                RenameConversationDialog(
                    currentTitle = "Current title",
                    operationInProgress = operationInProgress,
                    onConfirm = onConfirm,
                    onDismiss = onDismiss
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun setClear(
        operationInProgress: Boolean = false,
        onConfirm: () -> Unit = {},
        onDismiss: () -> Unit = {}
    ) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                ClearConversationDialog(
                    operationInProgress = operationInProgress,
                    onConfirm = onConfirm,
                    onDismiss = onDismiss
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun setDelete(
        operationInProgress: Boolean = false,
        onConfirm: () -> Unit = {},
        onDismiss: () -> Unit = {}
    ) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                DeleteConversationDialog(
                    operationInProgress = operationInProgress,
                    onConfirm = onConfirm,
                    onDismiss = onDismiss
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertMinimumTouchTarget(tag: String) {
        composeRule.onNodeWithTag(tag)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }
}
