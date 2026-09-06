package com.molotov.clender.ui.event

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.navigation.AppRoute
import java.time.LocalDateTime
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = ClenderApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EventListScreenTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private val composeHost = RobolectricComposeHost()
    private val englishLabels = EventListLabels(reminder = "Reminder", timespan = "Time span")

    @Before
    fun startComposeHost() {
        composeHost.start()
    }

    @After
    fun closeComposeHost() {
        composeHost.close()
    }

    @Test
    fun loadingEmptyAndErrorHaveDistinctBoundedSemanticsAndRetry() {
        setEventListContent(EventListUiState.Loading)
        composeRule.onNodeWithTag("event_list_loading").assertIsDisplayed()

        setEventListContent(EventListUiState.Empty)
        composeRule.onNodeWithTag("event_list_empty").assertIsDisplayed()

        var retries = 0
        setEventListContent(
            state = EventListUiState.Error(EventListErrorCode.LOAD_FAILED),
            onRetry = { retries += 1 }
        )
        composeRule.onNodeWithTag("event_list_error").assertIsDisplayed()
        composeRule.onNodeWithTag("event_list_retry").assertHasClickAction().performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
        composeRule.onNodeWithText("SQLiteException: /data/user/0/private.db")
            .assertDoesNotExist()
    }

    @Test
    fun contentRendersOnlyPublicTitleAndLocalizedSummary() {
        val event = eventListFixture(
            id = 7,
            startTime = LocalDateTime.of(2026, 8, 7, 9, 5),
            title = "Visible title"
        )
        val state = EventListPresenter.present(listOf(event), Locale.US, englishLabels)

        setEventListContent(state)

        composeRule.onNodeWithTag("event_list_item_7").assertIsDisplayed()
        composeRule.onNodeWithTag("event_list_summary_7", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Visible title").assertIsDisplayed()
        composeRule.onNodeWithText(event.description).assertDoesNotExist()
        composeRule.onNodeWithText(event.syncUid).assertDoesNotExist()
        composeRule.onNodeWithText(event.updatedAt.toString()).assertDoesNotExist()
    }

    @Test
    fun clickingItemEmitsPositiveDetailRoute() {
        val state = EventListPresenter.present(
            listOf(eventListFixture(7, LocalDateTime.of(2026, 8, 7, 9, 5))),
            Locale.US,
            englishLabels
        )
        val routes = mutableListOf<AppRoute.EventDetail>()
        setEventListContent(state = state, onNavigate = routes::add)

        composeRule.onNodeWithTag("event_list_item_7").assertHasClickAction().performClick()

        composeRule.runOnIdle { assertEquals(listOf(AppRoute.EventDetail(7)), routes) }
    }

    @Test
    fun loadingEmptyAndErrorNeverExposeContentItems() {
        listOf(
            EventListUiState.Loading,
            EventListUiState.Empty,
            EventListUiState.Error(EventListErrorCode.LOAD_FAILED)
        ).forEach { state ->
            setEventListContent(state)
            assertFalse(
                composeRule.onAllNodes(
                    androidx.compose.ui.test.hasTestTag("event_list_item_7")
                ).fetchSemanticsNodes().isNotEmpty()
            )
        }
    }

    private fun setEventListContent(
        state: EventListUiState,
        onRetry: () -> Unit = {},
        onNavigate: (AppRoute.EventDetail) -> Unit = {}
    ) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                EventListScreen(
                    state = state,
                    onRetry = onRetry,
                    onNavigate = onNavigate
                )
            }
        }
        composeRule.waitForIdle()
    }
}
