package com.molotov.clender.ui.calendar

import com.molotov.clender.ui.event.EventListLabels
import com.molotov.clender.ui.event.eventListFixture
import com.molotov.clender.ui.navigation.AppRoute
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarOverflowSelectionTest {
    private val start = LocalDateTime.of(2026, 8, 7, 9, 0)
    private val events = listOf(
        eventListFixture(1, start, title = "One"),
        eventListFixture(2, start.plusMinutes(5), title = "Two"),
        eventListFixture(3, start.plusMinutes(10), title = "Three")
    )
    private val labels = EventListLabels(reminder = "Reminder", timespan = "Time span")

    @Test
    fun openContainsOnlyAggregatedIdsInTheirStableOrder() {
        val controller = CalendarOverflowController(onNavigate = {}, labels = labels)

        assertTrue(controller.open(eventIds = listOf(3, 1), availableEvents = events))

        val open = controller.state as CalendarOverflowState.Open
        assertEquals(listOf(3L, 1L), open.events.map { it.id })
        assertFalse(open.events.any { it.id == 2L })
    }

    @Test
    fun selectingListedEventClosesSheetThenPushesDetail() {
        val routes = mutableListOf<AppRoute.EventDetail>()
        val statesAtNavigation = mutableListOf<CalendarOverflowState>()
        lateinit var controller: CalendarOverflowController
        controller = CalendarOverflowController(
            onNavigate = { route ->
                statesAtNavigation += controller.state
                routes += route
            },
            labels = labels
        )
        controller.open(eventIds = listOf(1, 2), availableEvents = events)

        assertTrue(controller.select(2))

        assertEquals(CalendarOverflowState.Closed, controller.state)
        assertEquals(listOf(CalendarOverflowState.Closed), statesAtNavigation)
        assertEquals(listOf(AppRoute.EventDetail(2)), routes)
    }

    @Test
    fun emptyIdsFailClosed() {
        val controller = CalendarOverflowController(onNavigate = {}, labels = labels)

        assertFalse(controller.open(emptyList(), events))
        assertEquals(CalendarOverflowState.Closed, controller.state)
    }

    @Test
    fun missingOrDuplicateIdsFailClosedWithoutPartialSheet() {
        val controller = CalendarOverflowController(onNavigate = {}, labels = labels)

        assertFalse(controller.open(listOf(1, 99), events))
        assertEquals(CalendarOverflowState.Closed, controller.state)
        assertFalse(controller.open(listOf(1, 1), events))
        assertEquals(CalendarOverflowState.Closed, controller.state)
    }

    @Test
    fun invalidOrUnlistedSelectionFailsClosedWithoutNavigation() {
        val routes = mutableListOf<AppRoute.EventDetail>()
        val controller = CalendarOverflowController(onNavigate = routes::add, labels = labels)
        controller.open(listOf(1, 2), events)

        assertFalse(controller.select(3))
        assertTrue(controller.state is CalendarOverflowState.Open)
        assertTrue(routes.isEmpty())
        assertFalse(controller.select(0))
        assertTrue(routes.isEmpty())
    }
}
