package com.molotov.clender.domain.calendar

import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.core.model.eventFixture
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class EventStateClassifierTest {
    private val now = LocalDateTime.of(2026, 8, 7, 10, 30, 45)

    @Test
    fun classifiesAllCurrentEarliestFutureTiesAndRemainingPastOrFuture() {
        val events = listOf(
            span(1, now.minusHours(1), now.plusHours(1)),
            reminder(2, now.minusMinutes(15), 60),
            reminder(3, now.plusMinutes(15), 0),
            reminder(4, now.plusMinutes(15), 0),
            reminder(5, now.plusHours(2), 0),
            reminder(6, now.minusHours(2), 0)
        )

        val result = EventStateClassifier.classify(events, now)

        assertEquals(listOf(1L, 2L), result.currentIds)
        assertEquals(listOf(3L, 4L), result.nextIds)
        assertEquals(listOf(6L), result.pastIds)
        assertEquals(EventTemporalState.FUTURE, result.states.getValue(5L))
    }

    @Test
    fun zeroDurationReminderIsCurrentOnlyThroughoutItsStartMinute() {
        val atMinute = reminder(1, now.withSecond(0).withNano(0), 0)

        assertEquals(listOf(1L), EventStateClassifier.classify(listOf(atMinute), now).currentIds)
        assertEquals(
            listOf(1L),
            EventStateClassifier.classify(
                listOf(atMinute),
                now.plusMinutes(1).withSecond(0)
            ).pastIds
        )
        assertEquals(
            listOf(1L),
            EventStateClassifier.classify(listOf(atMinute), now.minusMinutes(1)).nextIds
        )
    }

    @Test
    fun halfOpenTimespanChangesFromFutureToCurrentToPastAtExactBoundaries() {
        val boundary = now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
        val event = span(1, boundary, boundary.plusMinutes(1))

        assertEquals(
            listOf(1L),
            EventStateClassifier.classify(listOf(event), boundary.minusNanos(1)).nextIds
        )
        assertEquals(listOf(1L), EventStateClassifier.classify(listOf(event), boundary).currentIds)
        assertEquals(
            listOf(1L),
            EventStateClassifier.classify(listOf(event), boundary.plusMinutes(1)).pastIds
        )
    }

    @Test
    fun invalidAndTombstonedRecordsAreSanitized() {
        val deleted = reminder(1, now, 0).let { it.copy(deletedAt = it.updatedAt) }
        val invalid = span(2, now, now.minusMinutes(1))

        val result = EventStateClassifier.classify(listOf(deleted, invalid), now)

        assertTrueStatesEmpty(result)
        assertEquals(2, result.sanitizedCount)
    }

    @Test
    fun maximumEstimatedDurationRemainsValidAndDoesNotOverflowClassification() {
        val event = reminder(1, LocalDateTime.of(2026, 1, 1, 0, 0), Int.MAX_VALUE)

        val result = EventStateClassifier.classify(
            listOf(event),
            LocalDateTime.of(6000, 1, 1, 0, 0)
        )

        assertEquals(listOf(1L), result.currentIds)
        assertEquals(0, result.sanitizedCount)
    }

    private fun reminder(id: Long, start: LocalDateTime, duration: Int): Event = eventFixture(
        id = id,
        startTime = start.withSecond(0).withNano(0),
        estimatedDurationMinutes = duration,
        syncUid = id.toString(16).padStart(32, '0')
    )

    private fun span(id: Long, start: LocalDateTime, end: LocalDateTime): Event = eventFixture(
        id = id,
        eventType = EventType.TIMESPAN,
        startTime = start.withSecond(0).withNano(0),
        endTime = end.withSecond(0).withNano(0),
        syncUid = id.toString(16).padStart(32, '0')
    )

    private fun assertTrueStatesEmpty(result: EventStateResult) {
        assertEquals(emptyMap<Long, EventTemporalState>(), result.states)
        assertEquals(emptyList<Long>(), result.currentIds)
        assertEquals(emptyList<Long>(), result.nextIds)
        assertEquals(emptyList<Long>(), result.pastIds)
    }
}
