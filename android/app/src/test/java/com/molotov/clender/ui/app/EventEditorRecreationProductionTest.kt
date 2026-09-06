package com.molotov.clender.ui.app

import android.os.Looper
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.app.MainActivity
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.domain.event.AddEventCommand
import com.molotov.clender.domain.event.EventPatch
import com.molotov.clender.domain.event.FieldUpdate
import com.molotov.clender.testsupport.ProductionActivityTestResources
import com.molotov.clender.ui.event.EventCrudLoadStatus
import com.molotov.clender.ui.event.EventCrudRoute
import com.molotov.clender.ui.event.EventCrudViewModel
import com.molotov.clender.ui.event.EventFormError
import com.molotov.clender.ui.event.EventFormState
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.navigation.AppRoute
import com.molotov.clender.ui.state.AppShellViewModel
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
class EventEditorRecreationProductionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: ClenderApplication
        get() = composeRule.activity.application as ClenderApplication

    @After
    fun releaseProductionResources() {
        ProductionActivityTestResources.close(application, composeRule.activityRule.scenario)
    }

    @Test
    fun dirtyEditFormSurvivesActivityRecreationWithoutWriting() {
        val stored = openStoredEditor()
        composeRule.onNodeWithTag("event_editor_title").performTextReplacement("未保存 🌏")
        composeRule.runOnIdle {
            val form = requireNotNull(crud().state.value.form)
            crud().updateForm(
                form.copy(
                    description = "synthetic\nsecond line",
                    startTime = form.startTime.plusHours(1)
                )
            )
        }
        val before = crud().state.value
        assertNotEquals(EventFormState.fromEvent(stored), before.form)
        val version = application.container.scheduleMutationVersion.value

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        assertEquals(EventCrudRoute.Edit(stored.id), crud().state.value.route)
        assertEquals(before, crud().state.value)
        composeRule.onNodeWithTag("event_editor_title").assertExists()
        val persisted = runBlocking { application.container.eventRepository.findById(stored.id) }
        assertEquals(stored, persisted)
        assertEquals(version, application.container.scheduleMutationVersion.value)
    }

    @Test
    fun invalidEditDraftSurvivesActivityRecreation() {
        val stored = openStoredEditor()
        composeRule.runOnIdle {
            crud().updateForm(
                requireNotNull(crud().state.value.form).copy(estimatedDurationInput = "invalid")
            )
        }
        val before = requireNotNull(crud().state.value.form)
        assertTrue(EventFormError.NON_NUMERIC_ESTIMATED_DURATION in before.validationErrors)
        val version = application.container.scheduleMutationVersion.value

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        assertEquals(EventCrudRoute.Edit(stored.id), crud().state.value.route)
        assertEquals(before, crud().state.value.form)
        val persisted = runBlocking { application.container.eventRepository.findById(stored.id) }
        assertEquals(stored, persisted)
        assertEquals(version, application.container.scheduleMutationVersion.value)
    }

    @Test
    fun explicitDiscardReloadsLatestStoredEventAndReopensFreshEditor() {
        val stored = openStoredEditor()
        composeRule.onNodeWithTag("event_editor_title").performTextReplacement("discard this")
        val dirty = requireNotNull(crud().state.value.form)
        val changed = runBlocking {
            application.container.eventService.update(
                stored.id,
                EventPatch(title = FieldUpdate.Set("latest stored title"))
            )
        }
        val version = application.container.scheduleMutationVersion.value
        composeRule.onNodeWithTag("clender_navigate_back").performClick()
        composeRule.onNodeWithTag("event_discard_keep").performClick()
        assertEquals(dirty, crud().state.value.form)
        composeRule.onNodeWithTag("clender_navigate_back").performClick()
        composeRule.onNodeWithTag("event_discard_confirm").performClick()
        awaitDetail(changed)

        assertNull(crud().state.value.form)
        assertEquals(changed, crud().state.value.event)
        clickEdit()
        assertEquals(EventFormState.fromEvent(changed), crud().state.value.form)
        assertEquals(version, application.container.scheduleMutationVersion.value)
    }

    private fun openStoredEditor(): Event {
        val stored = runBlocking {
            application.container.eventService.add(
                AddEventCommand(
                    EventType.REMINDER,
                    "stored synthetic event",
                    LocalDateTime.of(2028, 2, 29, 9, 0)
                )
            )
        }
        composeRule.runOnIdle {
            val shell = ViewModelProvider(composeRule.activity)[AppShellViewModel::class.java]
            shell.navigateTo(AppDestination.EVENTS)
            shell.pushRoute(AppRoute.EventDetail(stored.id.toInt()))
        }
        awaitDetail(stored)
        clickEdit()
        return stored
    }

    private fun awaitDetail(event: Event) {
        composeRule.waitUntil(5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            crud().state.value.route == EventCrudRoute.Detail(event.id) &&
                crud().state.value.loadStatus == EventCrudLoadStatus.CONTENT &&
                crud().state.value.event == event
        }
        composeRule.waitForIdle()
    }

    private fun clickEdit() {
        composeRule.onNodeWithTag("event_detail_content")
            .performScrollToNode(hasTestTag("event_detail_edit"))
        composeRule.onNodeWithTag("event_detail_edit").performClick()
        composeRule.onNodeWithTag("event_editor_title").assertExists()
    }

    private fun crud(): EventCrudViewModel =
        ViewModelProvider(composeRule.activity)[EventCrudViewModel::class.java]
}
