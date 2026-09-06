package com.molotov.clender.app

import androidx.lifecycle.SavedStateHandle
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.navigation.AppRoute
import com.molotov.clender.ui.state.AppShellViewModel
import com.molotov.clender.ui.state.CalendarMode
import com.molotov.clender.widget.WidgetQuickAiDestination
import com.molotov.clender.widget.WidgetQuickAiEntryRouter
import com.molotov.clender.widget.WidgetQuickAiNavigation
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class WidgetQuickAiEntryRouterTest {
    @Test
    fun conversationEntryReplacesChildStackAndClosesDrawerWithoutOpeningSettings() {
        val shell = AppShellViewModel(SavedStateHandle())
        shell.navigateTo(AppDestination.EVENTS)
        shell.pushRoute(AppRoute.EventDetail(7))
        shell.pushRoute(AppRoute.QuickAi)
        shell.openDrawer()
        var settingsCalls = 0

        WidgetQuickAiEntryRouter.route(
            shell,
            WidgetQuickAiNavigation(1, WidgetQuickAiDestination.CONVERSATION)
        ) { settingsCalls += 1 }

        assertEquals(AppDestination.AI, shell.state.value.destination)
        assertTrue(shell.state.value.childRoutes.isEmpty())
        assertFalse(shell.state.value.drawerOpen)
        assertEquals(0, settingsCalls)
    }

    @Test
    fun settingsEntryFocusesAiSectionAndRepeatedEntriesNeverStackRoutes() {
        val shell = AppShellViewModel(SavedStateHandle())
        shell.pushRoute(AppRoute.QuickAi)
        var settingsCalls = 0
        val entry = WidgetQuickAiNavigation(Int.MAX_VALUE, WidgetQuickAiDestination.SETTINGS)

        repeat(2) {
            WidgetQuickAiEntryRouter.route(shell, entry) { settingsCalls += 1 }
            assertEquals(AppDestination.SETTINGS, shell.state.value.destination)
            assertTrue(shell.state.value.childRoutes.isEmpty())
        }

        assertEquals(2, settingsCalls)
    }

    @Test
    fun bothDestinationsKeepCalendarAndDraftStateWithoutAddingSavedStateFields() {
        val saved = SavedStateHandle()
        val shell = AppShellViewModel(saved)
        val date = LocalDate.of(2026, 9, 6)
        val draftId = "0123456789abcdef0123456789abcdef"
        shell.selectDate(date)
        shell.changeMode(CalendarMode.WEEK)
        shell.drafts.set(draftId)
        val originalKeys = saved.keys()

        WidgetQuickAiDestination.entries.forEach { destination ->
            val entry = WidgetQuickAiNavigation(51, destination)
            repeat(2) { WidgetQuickAiEntryRouter.route(shell, entry) {} }

            assertEquals(date, shell.state.value.selectedDate)
            assertEquals(CalendarMode.WEEK, shell.state.value.calendarMode)
            assertEquals(draftId, shell.state.value.draftId)
            assertEquals(originalKeys, saved.keys())
            assertTrue(shell.state.value.childRoutes.isEmpty())
        }
        assertEquals(setOf("destination", "date", "calendar_mode", "draft_id"), saved.keys())
    }
}
