package com.molotov.clender.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.molotov.clender.core.model.Event
import com.molotov.clender.domain.event.EventRepository
import com.molotov.clender.ui.state.CalendarMode
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CalendarViewModel(
    private val repository: EventRepository,
    initialSelectedDate: LocalDate,
    initialMode: CalendarMode,
    locale: Locale
) : ViewModel() {
    private var currentLocale: Locale = locale
    private val mutableState = MutableStateFlow(
        initialState(initialSelectedDate, initialMode, locale)
    )
    val state: StateFlow<CalendarScreenState> = mutableState.asStateFlow()

    private var queryJob: Job? = null
    private var queryVersion: Long = 0

    init {
        subscribe()
    }

    fun selectDate(date: LocalDate) {
        if (date == mutableState.value.selectedDate) return
        mutableState.value = mutableState.value.copy(selectedDate = date)
        subscribe()
    }

    fun changeMode(mode: CalendarMode) {
        if (mode == mutableState.value.mode) return
        mutableState.value = mutableState.value.copy(mode = mode)
        subscribe()
    }

    fun retry() {
        subscribe()
    }

    fun changeLocale(locale: Locale) {
        if (locale == currentLocale) return
        currentLocale = locale
        subscribe()
    }

    private fun subscribe() {
        queryJob?.cancel()
        val version = ++queryVersion
        val current = mutableState.value
        val range = CalendarRangePolicy.queryRange(
            selectedDate = current.selectedDate,
            mode = current.mode,
            locale = currentLocale
        )
        mutableState.value = current.copy(
            queryRange = range,
            events = emptyList(),
            loadStatus = CalendarLoadStatus.LOADING,
            errorCode = null
        )
        queryJob = viewModelScope.launch {
            collectRange(version, range)
        }
    }

    private suspend fun collectRange(version: Long, range: CalendarQueryRange) {
        try {
            repository.observeRange(
                range.start.atStartOfDay(),
                range.endExclusive.atStartOfDay()
            ).collect { events ->
                if (version == queryVersion) publishEvents(events)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (version == queryVersion) publishError()
        }
    }

    private fun publishEvents(events: List<Event>) {
        val ordered = events.sortedWith(EVENT_ORDER)
        mutableState.value = mutableState.value.copy(
            events = ordered,
            loadStatus = if (ordered.isEmpty()) {
                CalendarLoadStatus.EMPTY
            } else {
                CalendarLoadStatus.CONTENT
            },
            errorCode = null
        )
    }

    private fun publishError() {
        mutableState.value = mutableState.value.copy(
            events = emptyList(),
            loadStatus = CalendarLoadStatus.ERROR,
            errorCode = CalendarErrorCode.LOAD_FAILED
        )
    }

    private companion object {
        val EVENT_ORDER: Comparator<Event> = compareBy<Event>(
            Event::startTime,
            { it.endTime ?: it.startTime },
            Event::id
        )

        fun initialState(
            selectedDate: LocalDate,
            mode: CalendarMode,
            locale: Locale
        ): CalendarScreenState = CalendarScreenState(
            mode = mode,
            selectedDate = selectedDate,
            queryRange = CalendarRangePolicy.queryRange(selectedDate, mode, locale)
        )
    }
}
