package com.molotov.clender.ui.event

import com.molotov.clender.core.model.EventType
import java.time.LocalDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventListPresentationTest {
    private val selectedDay = LocalDateTime.of(2026, 8, 7, 0, 0)
    private val chineseLabels = EventListLabels(reminder = "提醒", timespan = "时间段")
    private val englishLabels = EventListLabels(reminder = "Reminder", timespan = "Time span")

    @Test
    fun reminderSummaryIsLocalizedWithoutPrivateFields() {
        val event = eventListFixture(
            id = 7,
            startTime = selectedDay.plusHours(9).plusMinutes(5),
            title = "Morning reminder"
        )

        val chinese = EventListPresenter.present(
            listOf(event),
            Locale.SIMPLIFIED_CHINESE,
            chineseLabels
        )
            .items.single()
        val english = EventListPresenter.present(listOf(event), Locale.US, englishLabels)
            .items.single()

        assertTrue(chinese.timeSummary.contains("提醒"))
        assertTrue(chinese.timeSummary.contains("2026"))
        assertTrue(chinese.timeSummary.contains("09:05"))
        assertTrue(english.timeSummary.contains("Reminder"))
        assertTrue(english.timeSummary.contains("2026"))
        assertFalse(chinese.timeSummary.contains(event.description))
        assertFalse(english.toString().contains(event.syncUid))
        assertFalse(english.toString().contains(event.updatedAt.toString()))
    }

    @Test
    fun crossDayTimespanSummaryIncludesActualStartAndEndDatesInBothLocales() {
        val event = eventListFixture(
            id = 8,
            startTime = selectedDay.plusHours(23).plusMinutes(30),
            eventType = EventType.TIMESPAN,
            endTime = selectedDay.plusDays(1).plusHours(1).plusMinutes(15),
            title = "Overnight span"
        )

        val chinese = EventListPresenter.present(
            listOf(event),
            Locale.SIMPLIFIED_CHINESE,
            chineseLabels
        )
            .items.single().timeSummary
        val english = EventListPresenter.present(listOf(event), Locale.US, englishLabels)
            .items.single().timeSummary

        assertTrue(chinese.contains("时间段"))
        assertTrue(chinese.contains("8月7日"))
        assertTrue(chinese.contains("8月8日"))
        assertTrue(english.contains("Time span"))
        assertTrue(english.contains("Aug 7"))
        assertTrue(english.contains("Aug 8"))
    }

    @Test
    fun itemsAreStablySortedByStartValidEndThenId() {
        val sameStart = selectedDay.plusHours(10)
        val events = listOf(
            eventListFixture(
                id = 30,
                startTime = sameStart,
                eventType = EventType.TIMESPAN,
                endTime = sameStart.plusHours(2)
            ),
            eventListFixture(id = 40, startTime = sameStart.plusHours(1)),
            eventListFixture(id = 20, startTime = sameStart),
            eventListFixture(id = 10, startTime = sameStart)
        )

        val items = EventListPresenter.present(events, Locale.US, englishLabels).items

        assertEquals(listOf(10L, 20L, 30L, 40L), items.map { it.id })
    }

    @Test
    fun loadingEmptyAndBoundedErrorStatesCarryNoEventOrExceptionPayload() {
        val states = listOf(
            EventListUiState.Loading,
            EventListUiState.Empty,
            EventListUiState.Error(EventListErrorCode.LOAD_FAILED)
        )

        states.forEach { state ->
            assertFalse(state.toString().contains("SQLiteException"))
            assertFalse(state.toString().contains("PRIVATE_DESCRIPTION"))
        }
        assertEquals(EventListErrorCode.LOAD_FAILED, (states.last() as EventListUiState.Error).code)
    }
}
