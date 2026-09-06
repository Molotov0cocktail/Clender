package com.molotov.clender.ui.state

import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.navigation.AppRouteFactory
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AppUiStateContractTest {
    private val date = LocalDate.of(2026, 8, 9)

    @Test
    fun savedStateRoundTripsOnlyDocumentedNonSensitiveFields() {
        val state = SavedAppState(
            destination = AppDestination.EVENTS,
            selectedDate = date,
            calendarMode = CalendarMode.WEEK,
            draftId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        )

        val encoded = AppSavedStateCodec.encode(state)

        assertEquals(
            mapOf(
                "destination" to "events",
                "date" to "2026-08-09",
                "calendar_mode" to "week",
                "draft_id" to "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            ),
            encoded
        )
        assertEquals(state, AppSavedStateCodec.decode(encoded, defaultDate = date.minusDays(1)))
        listOf("api_key", "webdav_password", "ai_prompt", "event_description").forEach {
            assertFalse(encoded.containsKey(it))
        }
    }

    @Test
    fun malformedSavedFieldsFailClosedToSafeUiDefaults() {
        val restored = AppSavedStateCodec.decode(
            mapOf(
                "destination" to "unknown",
                "date" to "2026-02-30",
                "calendar_mode" to "agenda",
                "draft_id" to "   ",
                "ai_prompt" to "must not be restored"
            ),
            defaultDate = date
        )

        assertEquals(AppDestination.CALENDAR, restored.destination)
        assertEquals(date, restored.selectedDate)
        assertEquals(CalendarMode.MONTH, restored.calendarMode)
        assertNull(restored.draftId)
    }

    @Test
    fun calendarSelectionAndModeChangesKeepOneSharedDateAnchor() {
        val initial = CalendarUiState(
            mode = CalendarMode.MONTH,
            selectedDate = LocalDate.of(2026, 8, 31),
            visibleDate = LocalDate.of(2026, 8, 1)
        )
        val selected = CalendarUiReducer.reduce(
            initial,
            CalendarUiAction.SelectDate(LocalDate.of(2026, 9, 1))
        )
        val switched = CalendarUiReducer.reduce(
            selected,
            CalendarUiAction.ChangeMode(CalendarMode.DAY)
        )

        assertEquals(LocalDate.of(2026, 9, 1), selected.selectedDate)
        assertEquals(selected.selectedDate, selected.visibleDate)
        assertEquals(selected.selectedDate, switched.selectedDate)
        assertEquals(selected.selectedDate, switched.visibleDate)
        assertEquals(CalendarMode.DAY, switched.mode)
        assertEquals("events?date=2026-09-01", AppRouteFactory.events(switched.selectedDate))
    }

    @Test
    fun everyDestinationAndCalendarModeRoundTripsThroughCodec() {
        val defaultDate = LocalDate.of(2026, 1, 1)
        AppDestination.entries.forEach { destination ->
            CalendarMode.entries.forEach { mode ->
                val state = SavedAppState(
                    destination = destination,
                    selectedDate = date,
                    calendarMode = mode,
                    draftId = null
                )
                assertEquals(
                    state,
                    AppSavedStateCodec.decode(AppSavedStateCodec.encode(state), defaultDate)
                )
            }
        }
    }

    @Test
    fun calendarModeContractContainsMonthWeekAndDayOnly() {
        assertEquals(
            listOf(CalendarMode.MONTH, CalendarMode.WEEK, CalendarMode.DAY),
            CalendarMode.entries
        )
    }
}
