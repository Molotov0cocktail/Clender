package com.molotov.clender.ui.calendar

import com.molotov.clender.core.model.Event
import com.molotov.clender.ui.state.CalendarMode
import java.time.LocalDate

enum class CalendarLoadStatus {
    LOADING,
    CONTENT,
    EMPTY,
    ERROR
}

enum class CalendarErrorCode {
    LOAD_FAILED
}

data class CalendarScreenState(
    val mode: CalendarMode,
    val selectedDate: LocalDate,
    val queryRange: CalendarQueryRange,
    val events: List<Event> = emptyList(),
    val loadStatus: CalendarLoadStatus = CalendarLoadStatus.LOADING,
    val errorCode: CalendarErrorCode? = null
)
