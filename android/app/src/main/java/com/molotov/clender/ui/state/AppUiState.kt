package com.molotov.clender.ui.state

import com.molotov.clender.ui.navigation.AppDestination
import java.time.DateTimeException
import java.time.LocalDate

enum class CalendarMode(val wireValue: String) {
    MONTH("month"),
    WEEK("week"),
    DAY("day");

    companion object {
        fun fromWire(value: String?): CalendarMode? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

data class SavedAppState(
    val destination: AppDestination,
    val selectedDate: LocalDate,
    val calendarMode: CalendarMode,
    val draftId: String?
)

object AppSavedStateCodec {
    private const val DESTINATION_KEY = "destination"
    private const val DATE_KEY = "date"
    private const val CALENDAR_MODE_KEY = "calendar_mode"
    private const val DRAFT_ID_KEY = "draft_id"

    fun encode(state: SavedAppState): Map<String, String> = buildMap {
        put(DESTINATION_KEY, state.destination.route)
        put(DATE_KEY, state.selectedDate.toString())
        put(CALENDAR_MODE_KEY, state.calendarMode.wireValue)
        state.draftId?.takeIf(DRAFT_ID::matches)?.let { put(DRAFT_ID_KEY, it) }
    }

    fun decode(values: Map<String, String>, defaultDate: LocalDate): SavedAppState = SavedAppState(
        destination = AppDestination.fromRoute(values[DESTINATION_KEY])
            ?: AppDestination.CALENDAR,
        selectedDate = parseDate(values[DATE_KEY]) ?: defaultDate,
        calendarMode = CalendarMode.fromWire(values[CALENDAR_MODE_KEY]) ?: CalendarMode.MONTH,
        draftId = values[DRAFT_ID_KEY]?.takeIf(DRAFT_ID::matches)
    )

    private fun parseDate(value: String?): LocalDate? = try {
        value?.let(LocalDate::parse)
    } catch (_: DateTimeException) {
        null
    }

    private val DRAFT_ID = Regex("^[0-9a-f]{32}$")
}

data class CalendarUiState(
    val mode: CalendarMode,
    val selectedDate: LocalDate,
    val visibleDate: LocalDate
)

sealed interface CalendarUiAction {
    data class SelectDate(val date: LocalDate) : CalendarUiAction

    data class ChangeMode(val mode: CalendarMode) : CalendarUiAction
}

object CalendarUiReducer {
    fun reduce(state: CalendarUiState, action: CalendarUiAction): CalendarUiState = when (action) {
        is CalendarUiAction.SelectDate -> state.copy(
            selectedDate = action.date,
            visibleDate = action.date
        )

        is CalendarUiAction.ChangeMode -> state.copy(
            mode = action.mode,
            visibleDate = state.selectedDate
        )
    }
}
