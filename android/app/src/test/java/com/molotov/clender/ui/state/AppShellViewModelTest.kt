package com.molotov.clender.ui.state

import androidx.lifecycle.SavedStateHandle
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.navigation.AppRoute
import com.molotov.clender.ui.navigation.BackNavigationAction
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShellViewModelTest {
    private val fixedDate = LocalDate.of(2026, 8, 9)

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) = AppShellViewModel(handle)

    @Test
    fun defaultsToCalendarTodayMonthDrawerClosedEmptyStackNoDraft() {
        val state = viewModel().state.value

        assertEquals(AppDestination.CALENDAR, state.destination)
        assertEquals(LocalDate.now(), state.selectedDate)
        assertEquals(CalendarMode.MONTH, state.calendarMode)
        assertFalse(state.drawerOpen)
        assertTrue(state.childRoutes.isEmpty())
        assertNull(state.draftId)
    }

    @Test
    fun restoresDocumentedFieldsFromSavedStateHandle() {
        val handle = SavedStateHandle(
            AppSavedStateCodec.encode(
                SavedAppState(
                    destination = AppDestination.EVENTS,
                    selectedDate = fixedDate,
                    calendarMode = CalendarMode.WEEK,
                    draftId = "0123456789abcdef0123456789abcdef"
                )
            )
        )

        val state = viewModel(handle).state.value

        assertEquals(AppDestination.EVENTS, state.destination)
        assertEquals(fixedDate, state.selectedDate)
        assertEquals(CalendarMode.WEEK, state.calendarMode)
        assertEquals("0123456789abcdef0123456789abcdef", state.draftId)
    }

    @Test
    fun unknownDestinationBadDateBadModeAndBlankDraftFailClosed() {
        val handle = SavedStateHandle(
            mapOf(
                "destination" to "unknown",
                "date" to "2026-02-30",
                "calendar_mode" to "agenda",
                "draft_id" to "   "
            )
        )

        val state = viewModel(handle).state.value

        assertEquals(AppDestination.CALENDAR, state.destination)
        assertEquals(LocalDate.now(), state.selectedDate)
        assertEquals(CalendarMode.MONTH, state.calendarMode)
        assertNull(state.draftId)
    }

    @Test
    fun mutationsPersistOnlyAllowedNonSensitiveKeys() {
        val handle = SavedStateHandle(mapOf("draft_id" to "fedcba9876543210fedcba9876543210"))
        val vm = viewModel(handle)

        vm.navigateTo(AppDestination.SETTINGS)
        vm.selectDate(fixedDate)
        vm.changeMode(CalendarMode.DAY)

        assertEquals("settings", handle.get<String>("destination"))
        assertEquals("2026-08-09", handle.get<String>("date"))
        assertEquals("day", handle.get<String>("calendar_mode"))
        assertEquals("fedcba9876543210fedcba9876543210", handle.get<String>("draft_id"))

        val allowed = setOf("destination", "date", "calendar_mode", "draft_id")
        assertTrue(handle.keys().all { it in allowed })
        listOf("api_key", "webdav_password", "ai_prompt", "event_description").forEach {
            assertFalse(handle.keys().contains(it))
        }
    }

    @Test
    fun pushAndPopMaintainLifoChildRouteStack() {
        val vm = viewModel()

        vm.pushRoute(AppRoute.EventDetail(1))
        vm.pushRoute(AppRoute.QuickAi)

        assertEquals(
            listOf(AppRoute.EventDetail(1), AppRoute.QuickAi),
            vm.state.value.childRoutes
        )

        assertTrue(vm.popRoute())
        assertEquals(listOf(AppRoute.EventDetail(1)), vm.state.value.childRoutes)
        assertTrue(vm.popRoute())
        assertTrue(vm.state.value.childRoutes.isEmpty())
        assertFalse(vm.popRoute())
    }

    @Test
    fun backPolicyClosesDrawerBeforePoppingThenDefersAtRoot() {
        val vm = viewModel()
        vm.pushRoute(AppRoute.EventDetail(1))

        assertEquals(BackNavigationAction.CLOSE_DRAWER, vm.onBack(drawerOpen = true))
        assertEquals(BackNavigationAction.POP_ROUTE, vm.onBack(drawerOpen = false))

        vm.popRoute()
        assertEquals(BackNavigationAction.DEFER_TO_SYSTEM, vm.onBack(drawerOpen = false))
    }

    @Test
    fun navigateToTopLevelClearsChildRoutesAndClosesDrawer() {
        val vm = viewModel()
        vm.openDrawer()
        vm.pushRoute(AppRoute.EventDetail(1))

        assertTrue(vm.state.value.drawerOpen)
        assertEquals(1, vm.state.value.childRoutes.size)

        vm.navigateTo(AppDestination.EVENTS)

        assertEquals(AppDestination.EVENTS, vm.state.value.destination)
        assertFalse(vm.state.value.drawerOpen)
        assertTrue(vm.state.value.childRoutes.isEmpty())
    }

    @Test
    fun childRouteStackIsRuntimeOnlyAndNeverPersisted() {
        val handle = SavedStateHandle()
        val vm = viewModel(handle)

        vm.pushRoute(AppRoute.EventDetail(7))
        vm.pushRoute(AppRoute.QuickAi)

        val allowed = setOf("destination", "date", "calendar_mode", "draft_id")
        assertTrue(handle.keys().all { it in allowed })
        assertFalse(handle.keys().contains("child_routes"))
        assertFalse(handle.keys().any { "route" in it })
    }
}
