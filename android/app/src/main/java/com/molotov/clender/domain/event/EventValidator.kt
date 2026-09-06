package com.molotov.clender.domain.event

import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

private const val MIN_SUPPORTED_YEAR = 1
private const val MAX_SUPPORTED_YEAR = 9999
private const val NANOS_PER_MICROSECOND = 1_000

class EventValidationException(message: String) : IllegalArgumentException(message)

object EventValidator {
    private val syncUidShape = Regex("[0-9a-f]{32}")

    fun validatePersisted(event: Event): Event {
        validate(event, requirePersistedId = true)
        return event.copy(title = event.title.trim())
    }

    fun validateNew(event: Event): Event {
        validate(event, requirePersistedId = false)
        return event.copy(title = event.title.trim())
    }

    private fun validate(event: Event, requirePersistedId: Boolean) {
        if (requirePersistedId) {
            valid(event.id > 0L, "Event id must be positive")
        } else {
            valid(event.id == 0L, "New event id must be zero")
        }
        valid(event.title.trim().isNotEmpty(), "Event title must not be blank")
        valid(event.estimatedDurationMinutes >= 0, "Estimated duration must not be negative")
        validMinutePrecision(event.startTime, "Start time")
        event.endTime?.let { validMinutePrecision(it, "End time") }
        valid(syncUidShape.matches(event.syncUid), "Sync uid must be 32 lowercase hex characters")
        validMicrosecondPrecision(event.createdAt, "Creation timestamp")
        validMicrosecondPrecision(event.updatedAt, "Updated timestamp")
        event.deletedAt?.let { validMicrosecondPrecision(it, "Deletion timestamp") }
        valid(
            !event.updatedAt.isBefore(event.createdAt),
            "Updated timestamp must not precede creation"
        )
        valid(
            event.deletedAt == null || !event.deletedAt.isAfter(event.updatedAt),
            "Deletion timestamp must not follow the updated timestamp"
        )

        when (event.eventType) {
            EventType.REMINDER -> valid(event.endTime == null, "Reminder must not have an end time")

            EventType.TIMESPAN -> valid(
                event.endTime != null && event.endTime.isAfter(event.startTime),
                "Timespan end time must follow start time"
            )
        }
    }

    private fun valid(condition: Boolean, message: String) {
        if (!condition) throw EventValidationException(message)
    }

    private fun validMinutePrecision(value: LocalDateTime, label: String) {
        valid(value.second == 0 && value.nano == 0, "$label must have minute precision")
        valid(
            value.year in MIN_SUPPORTED_YEAR..MAX_SUPPORTED_YEAR,
            "$label must use a four-digit positive year"
        )
    }

    private fun validMicrosecondPrecision(value: Instant, label: String) {
        valid(value.nano % NANOS_PER_MICROSECOND == 0, "$label must have microsecond precision")
        val year = try {
            value.atOffset(ZoneOffset.UTC).year
        } catch (_: java.time.DateTimeException) {
            null
        }
        valid(
            year != null && year in MIN_SUPPORTED_YEAR..MAX_SUPPORTED_YEAR,
            "$label must use a four-digit positive UTC year"
        )
    }
}
