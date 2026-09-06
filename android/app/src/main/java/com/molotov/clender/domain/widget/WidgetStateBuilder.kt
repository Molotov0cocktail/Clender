package com.molotov.clender.domain.widget

import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.domain.calendar.EventStateClassifier
import com.molotov.clender.domain.calendar.EventTemporalState
import com.molotov.clender.domain.event.EventValidationException
import com.molotov.clender.domain.event.EventValidator
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime

object WidgetStateBuilder {
    fun build(
        events: List<Event>,
        configuration: WidgetConfiguration,
        date: LocalDate,
        now: LocalDateTime
    ): WidgetState {
        val rangeStart = date.atTime(configuration.startTime)
        val rangeEndExclusive = date.atTime(configuration.endTime)
        val candidates = mutableListOf<Event>()
        val seenIds = mutableSetOf<Long>()

        events.forEach { event ->
            val valid = validatedEventOrNull(event) ?: return@forEach
            if (valid.deletedAt != null || !seenIds.add(valid.id)) return@forEach
            if (overlapsWindow(valid, rangeStart, rangeEndExclusive)) candidates += valid
        }

        val classified = EventStateClassifier.classify(candidates, now).states
        val items = candidates.asSequence()
            .mapNotNull { event ->
                val temporalState = classified[event.id]
                if (temporalState == null || temporalState == EventTemporalState.PAST) {
                    null
                } else {
                    WidgetEventItem(
                        id = event.id,
                        eventType = event.eventType,
                        title = event.title,
                        startTime = event.startTime,
                        endTime = event.endTime,
                        effectiveEndTime = effectiveEnd(event),
                        temporalState = temporalState
                    )
                }
            }
            .sortedWith(
                compareBy(
                    WidgetEventItem::startTime,
                    WidgetEventItem::effectiveEndTime,
                    WidgetEventItem::id
                )
            )
            .toList()

        return WidgetState(
            date = date,
            items = items,
            opacityPercent = configuration.opacityPercent
        )
    }
}

private fun validatedEventOrNull(event: Event): Event? = try {
    EventValidator.validatePersisted(event)
} catch (_: EventValidationException) {
    null
}

private fun overlapsWindow(
    event: Event,
    rangeStart: LocalDateTime,
    rangeEndExclusive: LocalDateTime
): Boolean = when (event.eventType) {
    EventType.REMINDER ->
        !event.startTime.isBefore(rangeStart) && event.startTime.isBefore(rangeEndExclusive)

    EventType.TIMESPAN ->
        event.startTime.isBefore(rangeEndExclusive) &&
            requireNotNull(event.endTime).isAfter(rangeStart)
}

private fun effectiveEnd(event: Event): LocalDateTime = when (event.eventType) {
    EventType.TIMESPAN -> requireNotNull(event.endTime)

    EventType.REMINDER -> try {
        event.startTime.plusMinutes(event.estimatedDurationMinutes.toLong())
    } catch (_: DateTimeException) {
        LocalDateTime.MAX
    }
}
