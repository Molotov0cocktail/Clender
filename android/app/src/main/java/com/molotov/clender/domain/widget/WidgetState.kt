package com.molotov.clender.domain.widget

import com.molotov.clender.core.model.EventType
import com.molotov.clender.domain.calendar.EventTemporalState
import java.time.LocalDate
import java.time.LocalDateTime

data class WidgetEventItem(
    val id: Long,
    val eventType: EventType,
    val title: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime?,
    val effectiveEndTime: LocalDateTime,
    val temporalState: EventTemporalState
)

data class WidgetState(
    val date: LocalDate,
    val items: List<WidgetEventItem>,
    val opacityPercent: Int
)
