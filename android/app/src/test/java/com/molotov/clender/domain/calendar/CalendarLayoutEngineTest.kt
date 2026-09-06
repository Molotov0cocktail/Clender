package com.molotov.clender.domain.calendar

import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.core.model.eventFixture
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarLayoutEngineTest {
    private val day = LocalDate.of(2026, 8, 7)
    private val start = day.atStartOfDay()

    @Test
    fun emptyInputAndEventsOutsideRangeProduceNoBlocksWithoutSanitizing() {
        assertEquals(CalendarLayoutResult(emptyList(), 0), layout(emptyList()))
        val result = layout(listOf(event(1, start.minusDays(1))))
        assertTrue(result.blocks.isEmpty())
        assertEquals(0, result.sanitizedCount)
    }

    @Test
    fun shortVisualMinimumDoesNotCreateFalseCollisionWithAdjacentEvent() {
        val first = event(
            id = 1,
            startTime = start.plusHours(9),
            type = EventType.TIMESPAN,
            endTime = start.plusHours(9).plusMinutes(1)
        )
        val adjacent = event(
            id = 2,
            startTime = start.plusHours(9).plusMinutes(1),
            type = EventType.TIMESPAN,
            endTime = start.plusHours(9).plusMinutes(2)
        )

        val blocks = layout(listOf(first, adjacent)).blocks

        assertTrue(blocks.all { it.heightFraction > 1f / MINUTES_PER_DAY })
        assertTrue(blocks.all { it.lane == 0 && it.laneCount == 1 })
        assertTrue(blocks.all { it.overlapRanges.isEmpty() })
        assertEquals(listOf(9 * 60, 9 * 60 + 1), blocks.map { it.topMinute })
    }

    @Test
    fun greedyLanesAndClusterLaneCountHandleTwoThreeAndReuseDeterministically() {
        val events = listOf(
            span(30, 8, 0, 12, 0),
            span(20, 9, 0, 10, 0),
            span(10, 9, 30, 11, 0),
            span(40, 10, 0, 10, 30)
        )

        val blocks = layout(events).blocks.associateBy { EventBlockKey.from(it) }

        assertEquals(0, blocks.getValue(EventBlockKey(30, 0)).lane)
        assertEquals(1, blocks.getValue(EventBlockKey(20, 0)).lane)
        assertEquals(2, blocks.getValue(EventBlockKey(10, 0)).lane)
        assertEquals(1, blocks.getValue(EventBlockKey(40, 0)).lane)
        assertTrue(blocks.values.all { it.laneCount == 3 })
        assertTrue(blocks.values.all { it.clusterId == blocks.values.first().clusterId })

        val reversed = layout(events.reversed()).blocks
        assertEquals(layout(events).blocks, reversed)
    }

    @Test
    fun crossDayTimespanClipsIntoColumnsAndHonoursMidnightAndEndExclusion() {
        val crossDay = event(
            id = 1,
            startTime = start.minusMinutes(1),
            type = EventType.TIMESPAN,
            endTime = start.plusDays(1).plusMinutes(1)
        )
        val atEnd = event(2, start.plusDays(2))

        val result = layout(
            events = listOf(crossDay, atEnd),
            rangeEndExclusive = day.plusDays(2)
        )

        assertEquals(2, result.blocks.size)
        assertEquals(listOf(0, 1), result.blocks.map { it.column })
        assertEquals(listOf(0, 0), result.blocks.map { it.topMinute })
        assertEquals(listOf(MINUTES_PER_DAY, 1), result.blocks.map { it.durationMinutes })
    }

    @Test
    fun markerAt2359IsClippedAndSameMinuteMarkersCollideUsingMinuteBucket() {
        val point = start.plusHours(23).plusMinutes(59)
        val blocks = layout(listOf(event(1, point), event(2, point))).blocks

        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it.marker })
        assertTrue(blocks.all { it.topMinute == 1439 && it.durationMinutes == 1 })
        assertTrue(blocks.all { it.heightFraction == 1f / MINUTES_PER_DAY })
        assertEquals(setOf(0, 1), blocks.map { it.lane }.toSet())
        assertTrue(blocks.all { it.laneCount == 2 })
    }

    @Test
    fun narrowColumnAggregatesOverflowWithStableIdsAndAccessibleLaneCount() {
        val simultaneous = (1L..5L).map { id -> span(id, 9, 0, 10, 0) }

        val blocks = layout(
            simultaneous,
            columnWidth = 100f,
            minimumAccessibleLaneWidth = 48f
        ).blocks

        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it.laneCount == 2 })
        assertEquals(listOf(1L), blocks.first().eventIds)
        assertEquals(listOf(2L, 3L, 4L, 5L), blocks.last().eventIds)
        assertTrue(blocks.last().overflow)
    }

    @Test
    fun overflowAggregationDoesNotCreateFalseHitAreaAcrossTemporalGap() {
        val bridge = span(1, 8, 59, 12, 0)
        val earlyOverflow = span(2, 9, 0, 10, 0)
        val lateOverflow = span(3, 10, 0, 12, 0)
        val peakOverflow = span(4, 9, 30, 9, 45)

        val overflow = layout(
            listOf(bridge, earlyOverflow, lateOverflow, peakOverflow),
            columnWidth = 96f
        ).blocks.filter(CalendarBlock::overflow)

        assertEquals(2, overflow.size)
        assertEquals(listOf(9 * 60, 10 * 60), overflow.map(CalendarBlock::topMinute))
        assertEquals(listOf(60, 120), overflow.map(CalendarBlock::durationMinutes))
        assertEquals(listOf(listOf(2L, 4L), listOf(3L)), overflow.map(CalendarBlock::eventIds))
    }

    @Test
    fun overlapRangesUseTrueIntervalsAndSeparateAdjacentCluster() {
        val events = listOf(
            span(1, 9, 0, 10, 0),
            span(2, 9, 30, 10, 30),
            span(3, 10, 30, 11, 0)
        )

        val blocks = layout(events).blocks.associateBy { it.id }

        assertEquals(listOf(MinuteRange(9 * 60 + 30, 10 * 60)), blocks.getValue(1).overlapRanges)
        assertEquals(listOf(MinuteRange(9 * 60 + 30, 10 * 60)), blocks.getValue(2).overlapRanges)
        assertTrue(blocks.getValue(3).overlapRanges.isEmpty())
        assertEquals(blocks.getValue(1).clusterId, blocks.getValue(2).clusterId)
        assertTrue(blocks.getValue(3).clusterId != blocks.getValue(2).clusterId)
        assertEquals(1, blocks.getValue(3).laneCount)
    }

    @Test
    fun estimatedReminderUsesRealDurationForCollisionButKeepsReminderShape() {
        val reminder = event(1, start.plusHours(9)).copy(estimatedDurationMinutes = 30)
        val touching = event(
            2,
            start.plusHours(9).plusMinutes(30)
        ).copy(estimatedDurationMinutes = 15)
        val overlapping = event(
            3,
            start.plusHours(9).plusMinutes(29)
        ).copy(estimatedDurationMinutes = 1)

        val blocks = layout(listOf(touching, overlapping, reminder)).blocks.associateBy { it.id }

        assertTrue(blocks.values.none { it.marker })
        assertEquals(30, blocks.getValue(1).durationMinutes)
        assertEquals(2, blocks.getValue(1).laneCount)
        assertEquals(1, blocks.getValue(2).laneCount)
        assertEquals(blocks.getValue(1).clusterId, blocks.getValue(3).clusterId)
        assertTrue(blocks.getValue(2).clusterId != blocks.getValue(1).clusterId)
    }

    @Test
    fun invalidAndTombstonedRecordsAreSkippedAndCountedWithoutLeakingTitles() {
        val valid = event(1, start)
        val corrupted = listOf(
            event(2, start).copy(id = 0),
            event(3, start).copy(estimatedDurationMinutes = -1),
            event(4, start, EventType.TIMESPAN, start),
            event(5, start).copy(deletedAt = event(5, start).updatedAt)
        )

        val result = layout(listOf(valid) + corrupted)

        assertEquals(listOf(1L), result.blocks.flatMap { it.eventIds })
        assertEquals(corrupted.size, result.sanitizedCount)
    }

    private fun layout(
        events: List<Event>,
        rangeEndExclusive: LocalDate = day.plusDays(1),
        columnWidth: Float = 480f,
        minimumAccessibleLaneWidth: Float = 48f
    ): CalendarLayoutResult = CalendarLayoutEngine.layout(
        events,
        CalendarLayoutConfig(
            rangeStart = day,
            rangeEndExclusive = rangeEndExclusive,
            perHourLogicalHeight = 60f,
            columnWidth = columnWidth,
            minimumVisualHeight = 12f,
            minimumAccessibleLaneWidth = minimumAccessibleLaneWidth
        )
    )

    private fun event(
        id: Long,
        startTime: LocalDateTime,
        type: EventType = EventType.REMINDER,
        endTime: LocalDateTime? = null
    ): Event = eventFixture(
        id = id,
        eventType = type,
        startTime = startTime,
        endTime = endTime,
        syncUid = id.toString(16).padStart(32, '0')
    )

    private fun span(
        id: Long,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int
    ): Event = event(
        id = id,
        startTime = start.plusHours(startHour.toLong()).plusMinutes(startMinute.toLong()),
        type = EventType.TIMESPAN,
        endTime = start.plusHours(endHour.toLong()).plusMinutes(endMinute.toLong())
    )
}

private data class EventBlockKey(val id: Long, val column: Int) {
    companion object {
        fun from(block: CalendarBlock): EventBlockKey = EventBlockKey(block.id, block.column)
    }
}
