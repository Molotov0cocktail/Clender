package com.molotov.clender.ui.event

import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import java.time.Instant
import java.time.LocalDateTime

internal fun eventListFixture(
    id: Long,
    startTime: LocalDateTime,
    eventType: EventType = EventType.REMINDER,
    endTime: LocalDateTime? = null,
    title: String = "Fixture event"
): Event = Event(
    id = id,
    eventType = eventType,
    title = title,
    startTime = startTime,
    endTime = endTime,
    description = "PRIVATE_DESCRIPTION_MUST_NOT_RENDER",
    estimatedDurationMinutes = 0,
    createdAt = Instant.parse("2026-08-01T00:00:00Z"),
    syncUid = id.toString(16).padStart(32, '0'),
    updatedAt = Instant.parse("2026-08-01T00:00:00Z"),
    deletedAt = null
)
