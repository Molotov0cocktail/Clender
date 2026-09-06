package com.molotov.clender.ui.app

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.app.MainActivity
import com.molotov.clender.testsupport.ProductionActivityTestResources
import com.molotov.clender.ui.calendar.CalendarLoadStatus
import com.molotov.clender.ui.calendar.CalendarViewModel
import com.molotov.clender.ui.event.EventCrudViewModel
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.navigation.AppRoute
import com.molotov.clender.ui.state.AppShellViewModel
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CreateEventEntryProductionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: ClenderApplication
        get() = composeRule.activity.application as ClenderApplication

    @After
    fun closeActivityThenContainerAndSandbox() {
        ProductionActivityTestResources.close(application, composeRule.activityRule.scenario)
    }

    @Test
    fun calendarEntryUsesSelectedLeapDayAndCleanCancelWritesNothing() {
        val date = LocalDate.of(2028, 2, 29)
        selectDate(date)
        composeRule.onNodeWithTag(CREATE_EVENT_TAG).assertIsDisplayed().performClick()
        assertNewForm(date)

        composeRule.onNodeWithTag("clender_navigate_back").performClick()
        composeRule.waitForIdle()

        assertTrue(shell().state.value.childRoutes.isEmpty())
        assertNull(shell().state.value.draftId)
        assertNoWrites(date)
        composeRule.onNodeWithTag(CREATE_EVENT_TAG).assertIsDisplayed()
    }

    @Test
    fun eventsEntryUsesSelectedYearBoundaryAndDirtyCancelRequiresConfirmation() {
        val date = LocalDate.of(2026, 12, 31)
        selectDate(date)
        openEventsThroughDrawer()
        composeRule.onNodeWithTag(CREATE_EVENT_TAG).performClick()
        assertNewForm(date)
        composeRule.onNodeWithTag("event_editor_title").performTextReplacement("未保存的事项")

        composeRule.onNodeWithTag("clender_navigate_back").performClick()
        composeRule.onNodeWithTag("event_discard_keep").performClick()
        assertEquals(listOf(AppRoute.NewEvent(date)), shell().state.value.childRoutes)
        assertEquals("未保存的事项", crud().state.value.form?.title)
        composeRule.onNodeWithTag(CREATE_EVENT_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("clender_navigate_back").performClick()
        composeRule.onNodeWithTag("event_discard_confirm").performClick()
        composeRule.waitForIdle()

        assertTrue(shell().state.value.childRoutes.isEmpty())
        assertEquals(AppDestination.EVENTS, shell().state.value.destination)
        assertNoWrites(date)
    }

    @Test
    fun actualEventsCreateClickSavesThroughProductionServiceAndRefreshesList() {
        val date = LocalDate.of(2028, 2, 29)
        val title = "通过真实入口创建 🌏"
        selectDate(date)
        openEventsThroughDrawer()
        composeRule.onNodeWithTag(CREATE_EVENT_TAG).performClick()
        assertNewForm(date)
        composeRule.onNodeWithTag("event_editor_title").performTextReplacement(title)
        composeRule.onNodeWithTag("event_editor_scroll")
            .performScrollToNode(hasTestTag("event_editor_save"))
        composeRule.onNodeWithTag("event_editor_save")
            .assertIsDisplayed().assertIsEnabled().performClick()

        composeRule.waitUntil(5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            shell().state.value.childRoutes.lastOrNull() is AppRoute.EventDetail &&
                calendar().state.value.events.any { it.title == title }
        }
        val event = calendar().state.value.events.single { it.title == title }
        val persisted = runBlocking {
            withTimeout(5_000) { application.container.eventRepository.findById(event.id) }
        }
        assertEquals(event, persisted)
        assertEquals(date.atTime(9, 0), persisted?.startTime)
        assertEquals(1L, application.container.scheduleMutationVersion.value)
        assertNull(shell().state.value.draftId)
        composeRule.onNodeWithTag("clender_navigate_back").performClick()
        composeRule.onNodeWithTag("event_list_item_${event.id}")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun recreateAfterRealEntryRetainsDateAndDirtyDraftWithoutDuplicatingForm() {
        val date = LocalDate.of(2028, 2, 29)
        selectDate(date)
        composeRule.onNodeWithTag(CREATE_EVENT_TAG).performClick()
        assertNewForm(date)
        composeRule.onNodeWithTag("event_editor_title").performTextReplacement("旋转保留")
        val draftId = shell().state.value.draftId

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        assertEquals(listOf(AppRoute.NewEvent(date)), shell().state.value.childRoutes)
        assertEquals(date, shell().state.value.selectedDate)
        assertEquals(draftId, shell().state.value.draftId)
        assertEquals("旋转保留", crud().state.value.form?.title)
        assertEquals(date.atTime(9, 0), crud().state.value.form?.startTime)
        composeRule.onNodeWithTag(CREATE_EVENT_TAG).assertDoesNotExist()
        assertNoWrites(date)
    }

    @Test
    fun discardAndReopenSameDateCreatesFreshEmptyForm() {
        val date = LocalDate.of(2028, 2, 29)
        selectDate(date)
        composeRule.onNodeWithTag(CREATE_EVENT_TAG).performClick()
        assertNewForm(date)
        composeRule.onNodeWithTag("event_editor_title").performTextReplacement("必须丢弃")
        val oldDraftId = shell().state.value.draftId
        composeRule.onNodeWithTag("clender_navigate_back").performClick()
        composeRule.onNodeWithTag("event_discard_confirm").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CREATE_EVENT_TAG).performClick()
        assertNewForm(date)

        assertEquals("", crud().state.value.form?.title)
        assertTrue(oldDraftId != shell().state.value.draftId)
        assertNoWrites(date)
    }

    private fun selectDate(date: LocalDate) {
        composeRule.runOnIdle {
            shell().selectDate(date)
            calendar().selectDate(date)
        }
        composeRule.waitUntil(5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            calendar().state.value.loadStatus != CalendarLoadStatus.LOADING
        }
        assertEquals(CalendarLoadStatus.EMPTY, calendar().state.value.loadStatus)
    }

    private fun openEventsThroughDrawer() {
        composeRule.onNodeWithTag("clender_open_navigation_drawer").performClick()
        composeRule.onNodeWithTag("clender_drawer_events").performClick()
        assertEquals(AppDestination.EVENTS, shell().state.value.destination)
    }

    private fun assertNewForm(date: LocalDate) {
        composeRule.onNodeWithTag("event_editor_title").assertExists()
        assertEquals(listOf(AppRoute.NewEvent(date)), shell().state.value.childRoutes)
        assertEquals(date.atTime(9, 0), crud().state.value.form?.startTime)
        assertTrue(requireNotNull(shell().state.value.draftId).matches(Regex("[0-9a-f]{32}")))
        composeRule.onNodeWithTag(CREATE_EVENT_TAG).assertDoesNotExist()
    }

    private fun assertNoWrites(date: LocalDate) {
        val events = runBlocking {
            withTimeout(5_000) {
                application.container.eventRepository.observeRange(
                    date.atStartOfDay(),
                    date.plusDays(1).atStartOfDay()
                ).first()
            }
        }
        assertTrue(events.isEmpty())
        assertEquals(0L, application.container.scheduleMutationVersion.value)
    }

    private fun shell(): AppShellViewModel =
        ViewModelProvider(composeRule.activity)[AppShellViewModel::class.java]

    private fun calendar(): CalendarViewModel =
        ViewModelProvider(composeRule.activity)[CalendarViewModel::class.java]

    private fun crud(): EventCrudViewModel =
        ViewModelProvider(composeRule.activity)[EventCrudViewModel::class.java]
}
