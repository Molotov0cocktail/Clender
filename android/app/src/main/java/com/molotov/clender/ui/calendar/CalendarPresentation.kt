package com.molotov.clender.ui.calendar

import com.molotov.clender.core.model.Event
import com.molotov.clender.domain.calendar.CalendarLayoutConfig
import com.molotov.clender.domain.calendar.CalendarLayoutEngine
import com.molotov.clender.domain.calendar.CalendarLayoutResult
import com.molotov.clender.ui.event.EventListErrorCode
import com.molotov.clender.ui.event.EventListLabels
import com.molotov.clender.ui.event.EventListPresenter
import com.molotov.clender.ui.event.EventListUiState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val PER_HOUR_LOGICAL_HEIGHT = 60f
private const val MINIMUM_VISUAL_HEIGHT = 12f
private const val MINIMUM_ACCESSIBLE_LANE_WIDTH = 48f
private const val UNCONSTRAINED_COLUMN_WIDTH = 10_000f

internal fun monthEventCounts(state: CalendarScreenState): Map<LocalDate, Int> {
    val dates = state.queryRange.dates
    val layout = layoutEvents(state.events, dates, UNCONSTRAINED_COLUMN_WIDTH)
    return layout.blocks
        .groupBy { it.column }
        .mapNotNull { (column, blocks) ->
            dates.getOrNull(column)?.let { date ->
                date to blocks.flatMap { it.eventIds }.distinct().size
            }
        }
        .toMap()
}

internal fun selectedDateListState(
    state: CalendarScreenState,
    locale: Locale,
    labels: EventListLabels
): EventListUiState = when (state.loadStatus) {
    CalendarLoadStatus.LOADING -> EventListUiState.Loading

    CalendarLoadStatus.ERROR -> EventListUiState.Error(EventListErrorCode.LOAD_FAILED)

    else -> {
        val events = selectedDateEvents(state)
        if (events.isEmpty()) {
            EventListUiState.Empty
        } else {
            EventListPresenter.present(events, locale, labels)
        }
    }
}

internal fun eventLabels(
    events: List<Event>,
    locale: Locale,
    labels: EventListLabels
): Map<Long, String> = events.mapNotNull { event ->
    try {
        EventListPresenter.present(listOf(event), locale, labels).items.single().let { item ->
            item.id to "${item.title}\n${item.timeSummary}"
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}.toMap()

internal fun eventDisplayLabels(
    events: List<Event>,
    locale: Locale,
    labels: EventListLabels
): Map<Long, String> {
    val validIds = eventLabels(events, locale, labels).keys
    return events.filter { it.id in validIds }.associate { event ->
        val end = event.endTime
        val pattern = if (end == null || event.startTime.toLocalDate() == end.toLocalDate()) {
            "HH:mm"
        } else {
            "MM-dd HH:mm"
        }
        val format = DateTimeFormatter.ofPattern(pattern, locale)
        val time =
            event.startTime.format(format) + if (end != null) " – ${end.format(format)}" else ""
        event.id to "${event.title}\n$time"
    }
}

internal fun layoutEvents(
    events: List<Event>,
    dates: List<LocalDate>,
    columnWidth: Float
): CalendarLayoutResult {
    require(dates.isNotEmpty()) { "Calendar dates must not be empty" }
    return CalendarLayoutEngine.layout(
        events,
        CalendarLayoutConfig(
            rangeStart = dates.first(),
            rangeEndExclusive = dates.last().plusDays(1),
            perHourLogicalHeight = PER_HOUR_LOGICAL_HEIGHT,
            columnWidth = columnWidth.coerceAtLeast(MINIMUM_ACCESSIBLE_LANE_WIDTH),
            minimumVisualHeight = MINIMUM_VISUAL_HEIGHT,
            minimumAccessibleLaneWidth = MINIMUM_ACCESSIBLE_LANE_WIDTH
        )
    )
}

private fun selectedDateEvents(state: CalendarScreenState): List<Event> {
    val dates = listOf(state.selectedDate)
    val visibleIds = layoutEvents(state.events, dates, UNCONSTRAINED_COLUMN_WIDTH)
        .blocks
        .flatMap { it.eventIds }
        .toSet()
    return state.events.filter { it.id in visibleIds }
}
