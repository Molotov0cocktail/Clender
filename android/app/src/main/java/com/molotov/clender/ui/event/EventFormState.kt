package com.molotov.clender.ui.event

import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.domain.event.AddEventCommand
import com.molotov.clender.domain.event.EventPatch
import com.molotov.clender.domain.event.FieldUpdate
import java.time.LocalDate
import java.time.LocalDateTime

private const val DEFAULT_START_HOUR = 9

enum class EventFormError {
    BLANK_TITLE,
    EMPTY_ESTIMATED_DURATION,
    NON_NUMERIC_ESTIMATED_DURATION,
    NEGATIVE_ESTIMATED_DURATION,
    OUT_OF_RANGE_ESTIMATED_DURATION,
    MISSING_END_TIME,
    END_NOT_AFTER_START,
    REMINDER_END_TIME_PRESENT
}

data class EventFormState(
    val eventType: EventType,
    val title: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime?,
    val description: String,
    val estimatedDurationInput: String
) {
    constructor(
        eventType: EventType,
        title: String,
        startTime: LocalDateTime,
        endTime: LocalDateTime?,
        description: String,
        estimatedDurationMinutes: Int
    ) : this(
        eventType = eventType,
        title = title,
        startTime = startTime,
        endTime = endTime,
        description = description,
        estimatedDurationInput = estimatedDurationMinutes.toString()
    )

    val validationErrors: Set<EventFormError>
        get() = buildSet {
            if (title.isBlank()) add(EventFormError.BLANK_TITLE)
            durationError(estimatedDurationInput)?.let(::add)
            when (eventType) {
                EventType.REMINDER -> if (endTime != null) {
                    add(EventFormError.REMINDER_END_TIME_PRESENT)
                }

                EventType.TIMESPAN -> when {
                    endTime == null -> add(EventFormError.MISSING_END_TIME)
                    !endTime.isAfter(startTime) -> add(EventFormError.END_NOT_AFTER_START)
                }
            }
        }

    val canSubmit: Boolean
        get() = validationErrors.isEmpty()

    fun changeType(type: EventType): EventFormState = copy(
        eventType = type,
        endTime = if (type == EventType.REMINDER) null else endTime
    )

    fun withStartTime(value: LocalDateTime): EventFormState = copy(
        startTime = value.toMinutePrecision()
    )

    fun withEndTime(value: LocalDateTime?): EventFormState = copy(
        endTime = value?.toMinutePrecision()
    )

    fun toAddCommand(): AddEventCommand {
        check(canSubmit) { "Event form is invalid" }
        return AddEventCommand(
            eventType = eventType,
            title = title.trim(),
            startTime = startTime.toMinutePrecision(),
            endTime = endTime?.toMinutePrecision(),
            description = description,
            estimatedDurationMinutes = requireNotNull(parsedDuration())
        )
    }

    fun toPatch(original: Event): EventPatch {
        check(canSubmit) { "Event form is invalid" }
        val normalizedTitle = title.trim()
        val normalizedStart = startTime.toMinutePrecision()
        val normalizedEnd = endTime?.toMinutePrecision()
        val duration = requireNotNull(parsedDuration())
        val typeChangedToReminder = original.eventType != EventType.REMINDER &&
            eventType == EventType.REMINDER
        return EventPatch(
            eventType = changed(original.eventType, eventType),
            title = changed(original.title, normalizedTitle),
            startTime = changed(original.startTime, normalizedStart),
            endTime = if (typeChangedToReminder) {
                FieldUpdate.Unchanged
            } else {
                changed(original.endTime, normalizedEnd)
            },
            description = changed(original.description, description),
            estimatedDurationMinutes = changed(original.estimatedDurationMinutes, duration)
        )
    }

    private fun parsedDuration(): Int? = estimatedDurationInput
        .takeIf { input -> input.isNotEmpty() && input.all(Char::isAsciiDigit) }
        ?.toIntOrNull()

    companion object {
        fun new(date: LocalDate): EventFormState = EventFormState(
            eventType = EventType.REMINDER,
            title = "",
            startTime = date.atTime(DEFAULT_START_HOUR, 0),
            endTime = null,
            description = "",
            estimatedDurationInput = "0"
        )

        fun fromEvent(event: Event): EventFormState = EventFormState(
            eventType = event.eventType,
            title = event.title,
            startTime = event.startTime.toMinutePrecision(),
            endTime = event.endTime?.toMinutePrecision(),
            description = event.description,
            estimatedDurationInput = event.estimatedDurationMinutes.toString()
        )
    }
}

private fun durationError(input: String): EventFormError? = when {
    input.isEmpty() -> EventFormError.EMPTY_ESTIMATED_DURATION

    input.startsWith("-") && input.drop(1).isNotEmpty() && input.drop(1).all(Char::isAsciiDigit) ->
        EventFormError.NEGATIVE_ESTIMATED_DURATION

    !input.all(Char::isAsciiDigit) -> EventFormError.NON_NUMERIC_ESTIMATED_DURATION

    input.toIntOrNull() == null -> EventFormError.OUT_OF_RANGE_ESTIMATED_DURATION

    else -> null
}

private fun LocalDateTime.toMinutePrecision(): LocalDateTime = withSecond(0).withNano(0)

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

private fun <T> changed(original: T, requested: T): FieldUpdate<T> =
    if (original == requested) FieldUpdate.Unchanged else FieldUpdate.Set(requested)
