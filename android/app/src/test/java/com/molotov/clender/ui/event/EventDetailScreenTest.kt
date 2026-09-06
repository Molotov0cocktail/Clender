package com.molotov.clender.ui.event

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.testsupport.RobolectricComposeHost
import java.time.Instant
import java.time.LocalDateTime
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
class EventDetailScreenTest {
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
    fun contentShowsEveryUserFieldButNeverSyncOrAuditMetadata() {
        val event = detailFixture()

        setDetailContent(
            EventCrudUiState(
                loadStatus = EventCrudLoadStatus.CONTENT,
                event = event
            )
        )

        composeRule.onNodeWithText(event.title).assertIsDisplayed()
        composeRule.onNodeWithText(event.description).assertIsDisplayed()
        composeRule.onNodeWithTag("event_detail_type").assertIsDisplayed()
        composeRule.onNodeWithTag("event_detail_start").assertIsDisplayed()
        composeRule.onNodeWithTag("event_detail_end").assertIsDisplayed()
        composeRule.onNodeWithTag("event_detail_duration").assertIsDisplayed()
        composeRule.onNodeWithText(event.syncUid).assertDoesNotExist()
        composeRule.onNodeWithText(event.createdAt.toString()).assertDoesNotExist()
        composeRule.onNodeWithText(event.updatedAt.toString()).assertDoesNotExist()
        composeRule.onNodeWithTag("event_detail_sync_uid").assertDoesNotExist()
        composeRule.onNodeWithTag("event_detail_created_at").assertDoesNotExist()
        composeRule.onNodeWithTag("event_detail_updated_at").assertDoesNotExist()
        composeRule.onNodeWithTag("event_detail_deleted_at").assertDoesNotExist()
    }

    @Test
    fun loadingNotFoundAndLoadErrorHaveBoundedRecoverableSemantics() {
        setDetailContent(EventCrudUiState(loadStatus = EventCrudLoadStatus.LOADING))
        composeRule.onNodeWithTag("event_detail_loading").assertIsDisplayed()

        setDetailContent(EventCrudUiState(loadStatus = EventCrudLoadStatus.NOT_FOUND))
        composeRule.onNodeWithTag("event_detail_not_found").assertIsDisplayed()

        var retries = 0
        setDetailContent(
            state = EventCrudUiState(loadStatus = EventCrudLoadStatus.ERROR),
            onRetry = { retries += 1 }
        )
        composeRule.onNodeWithTag("event_detail_error").assertIsDisplayed()
        composeRule.onNodeWithTag("event_detail_retry")
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
        composeRule.onNodeWithText("SQLiteException: /data/user/0/private.db")
            .assertDoesNotExist()
        composeRule.onNodeWithText("PRIVATE_EVENT_BODY").assertDoesNotExist()
    }

    @Test
    fun contentProvidesExplicitEditAndDestructiveDeleteActions() {
        var edits = 0
        var deletes = 0
        setDetailContent(
            state = EventCrudUiState(
                loadStatus = EventCrudLoadStatus.CONTENT,
                event = detailFixture()
            ),
            onEdit = { edits += 1 },
            onRequestDelete = { deletes += 1 }
        )

        composeRule.onNodeWithTag("event_detail_edit")
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithTag("event_detail_delete")
            .assertHasClickAction()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, edits)
            assertEquals(1, deletes)
        }
        assertMinimumTouchTarget("event_detail_edit")
        assertMinimumTouchTarget("event_detail_delete")
    }

    @Test
    fun nonContentStatesNeverLeakStaleDetailOrActions() {
        listOf(
            EventCrudLoadStatus.LOADING,
            EventCrudLoadStatus.NOT_FOUND,
            EventCrudLoadStatus.ERROR
        ).forEach { status ->
            setDetailContent(
                EventCrudUiState(
                    loadStatus = status,
                    event = detailFixture()
                )
            )
            composeRule.onNodeWithText("Visible event").assertDoesNotExist()
            composeRule.onNodeWithTag("event_detail_edit").assertDoesNotExist()
            composeRule.onNodeWithTag("event_detail_delete").assertDoesNotExist()
        }
    }

    private fun setDetailContent(
        state: EventCrudUiState,
        onRetry: () -> Unit = {},
        onEdit: () -> Unit = {},
        onRequestDelete: () -> Unit = {}
    ) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                EventDetailScreen(
                    state = state,
                    onRetry = onRetry,
                    onEdit = onEdit,
                    onRequestDelete = onRequestDelete
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun detailFixture(): Event = Event(
        id = 17,
        eventType = EventType.TIMESPAN,
        title = "Visible event",
        startTime = LocalDateTime.of(2026, 8, 31, 9, 15),
        endTime = LocalDateTime.of(2026, 9, 1, 10, 45),
        description = "Visible description",
        estimatedDurationMinutes = 37,
        createdAt = Instant.parse("2026-08-01T00:00:00Z"),
        syncUid = "0123456789abcdef0123456789abcdef",
        updatedAt = Instant.parse("2026-08-02T00:00:00Z"),
        deletedAt = null
    )

    private fun assertMinimumTouchTarget(tag: String) {
        val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val minimumPixels = 48f * composeHost.activity.resources.displayMetrics.density
        assert(bounds.width >= minimumPixels && bounds.height >= minimumPixels) {
            "$tag must expose at least a 48dp touch target"
        }
    }
}
