package com.molotov.clender.domain.calendar

import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.domain.event.EventValidationException
import com.molotov.clender.domain.event.EventValidator
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

enum class EventTemporalState {
    PAST,
    CURRENT,
    NEXT,
    FUTURE
}

data class EventStateResult(
    val states: Map<Long, EventTemporalState>,
    val currentIds: List<Long>,
    val nextIds: List<Long>,
    val pastIds: List<Long>,
    val sanitizedCount: Int
)

object EventStateClassifier {
    fun classify(events: List<Event>, now: LocalDateTime): EventStateResult {
        val (valid, sanitizedCount) = sanitize(events)
        val ordered = valid.sortedWith(compareBy(Event::startTime, { eventEnd(it) }, Event::id))
        val states = linkedMapOf<Long, EventTemporalState>()
        val future = mutableListOf<Event>()
        for (event in ordered) {
            when {
                isCurrent(event, now) -> states[event.id] = EventTemporalState.CURRENT

                event.startTime.isAfter(now) -> {
                    states[event.id] = EventTemporalState.FUTURE
                    future += event
                }

                else -> states[event.id] = EventTemporalState.PAST
            }
        }
        val nextStart = future.minOfOrNull(Event::startTime)
        future.filter { it.startTime == nextStart }.forEach {
            states[it.id] =
                EventTemporalState.NEXT
        }
        return EventStateResult(
            states = states,
            currentIds = idsFor(states, EventTemporalState.CURRENT),
            nextIds = idsFor(states, EventTemporalState.NEXT),
            pastIds = idsFor(states, EventTemporalState.PAST),
            sanitizedCount = sanitizedCount
        )
    }
}

private fun sanitize(events: List<Event>): Pair<List<Event>, Int> {
    val valid = mutableListOf<Event>()
    val seenIds = mutableSetOf<Long>()
    var sanitizedCount = 0
    for (event in events) {
        val candidate = try {
            EventValidator.validatePersisted(event)
        } catch (_: EventValidationException) {
            sanitizedCount += 1
            continue
        }
        if (candidate.deletedAt != null || !seenIds.add(candidate.id)) {
            sanitizedCount += 1
        } else {
            valid += candidate
        }
    }
    return valid to sanitizedCount
}

private fun isCurrent(event: Event, now: LocalDateTime): Boolean {
    if (event.eventType == EventType.REMINDER && event.estimatedDurationMinutes == 0) {
        return event.startTime == now.truncatedTo(ChronoUnit.MINUTES)
    }
    return !now.isBefore(event.startTime) && now.isBefore(eventEnd(event))
}

private fun eventEnd(event: Event): LocalDateTime = when (event.eventType) {
    EventType.TIMESPAN -> requireNotNull(event.endTime)

    EventType.REMINDER -> try {
        event.startTime.plusMinutes(event.estimatedDurationMinutes.toLong())
    } catch (_: DateTimeException) {
        LocalDateTime.MAX
    }
}

private fun idsFor(states: Map<Long, EventTemporalState>, target: EventTemporalState): List<Long> =
    states.filterValues { it == target }.keys.toList()
