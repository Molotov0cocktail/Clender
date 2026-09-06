package com.molotov.clender.ui.event

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
@Config(sdk = [26, 36], application = ClenderApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EventConfirmationDialogTest {
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
    fun deleteDialogHasExplicitTitleConfirmAndCancelAndCancelNeverDeletes() {
        var confirms = 0
        var cancels = 0
        setDeleteDialog(
            onConfirm = { confirms += 1 },
            onDismiss = { cancels += 1 }
        )

        composeRule.onNodeWithTag("event_delete_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("event_delete_title").assertIsDisplayed()
        composeRule.onNodeWithTag("event_delete_confirm").assertHasClickAction()
        composeRule.onNodeWithTag("event_delete_cancel")
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(0, confirms)
            assertEquals(1, cancels)
        }
    }

    @Test
    fun deleteConfirmInvokesOnceAndDeletingStatePreventsDoubleConfirm() {
        var confirms = 0
        setDeleteDialog(onConfirm = { confirms += 1 })
        composeRule.onNodeWithTag("event_delete_confirm").performClick()
        composeRule.runOnIdle { assertEquals(1, confirms) }

        setDeleteDialog(deleting = true, onConfirm = { confirms += 1 })
        composeRule.onNodeWithTag("event_delete_busy").assertIsDisplayed()
        composeRule.onNodeWithTag("event_delete_confirm").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(1, confirms) }
    }

    @Test
    fun deleteFailureIsBoundedRecoverableAndNeverIncludesEventOrExceptionBody() {
        setDeleteDialog(error = EventDeleteErrorCode.DELETE_FAILED)

        composeRule.onNodeWithTag("event_delete_error").assertIsDisplayed()
        composeRule.onNodeWithText("PRIVATE EVENT TITLE").assertDoesNotExist()
        composeRule.onNodeWithText("SQLiteException: /data/user/0/private.db")
            .assertDoesNotExist()
        composeRule.onNodeWithTag("event_delete_confirm").assertHasClickAction()
        composeRule.onNodeWithTag("event_delete_cancel").assertHasClickAction()
    }

    @Test
    fun discardDialogDistinguishesKeepEditingFromDiscard() {
        var discards = 0
        var keeps = 0
        setDiscardDialog(onDiscard = { discards += 1 }, onDismiss = { keeps += 1 })

        composeRule.onNodeWithTag("event_discard_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("event_discard_title").assertIsDisplayed()
        composeRule.onNodeWithTag("event_discard_keep").performClick()
        composeRule.runOnIdle {
            assertEquals(0, discards)
            assertEquals(1, keeps)
        }

        setDiscardDialog(onDiscard = { discards += 1 }, onDismiss = { keeps += 1 })
        composeRule.onNodeWithTag("event_discard_confirm").performClick()
        composeRule.runOnIdle {
            assertEquals(1, discards)
            assertEquals(1, keeps)
        }
    }

    private fun setDeleteDialog(
        deleting: Boolean = false,
        error: EventDeleteErrorCode? = null,
        onConfirm: () -> Unit = {},
        onDismiss: () -> Unit = {}
    ) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                DeleteConfirmationDialog(
                    deleting = deleting,
                    error = error,
                    onConfirm = onConfirm,
                    onDismiss = onDismiss
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun setDiscardDialog(onDiscard: () -> Unit, onDismiss: () -> Unit) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                DiscardChangesDialog(onDiscard = onDiscard, onDismiss = onDismiss)
            }
        }
        composeRule.waitForIdle()
    }
}
