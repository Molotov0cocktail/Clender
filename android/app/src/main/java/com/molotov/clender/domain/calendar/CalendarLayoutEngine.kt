package com.molotov.clender.domain.calendar

import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.domain.event.EventValidationException
import com.molotov.clender.domain.event.EventValidator
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

const val MINUTES_PER_DAY: Int = 24 * 60

data class MinuteRange(val startMinute: Int, val endMinute: Int)

data class CalendarBlock(
    val id: Long,
    val column: Int,
    val topFraction: Float,
    val heightFraction: Float,
    val topMinute: Int,
    val durationMinutes: Int,
    val lane: Int,
    val laneCount: Int,
    val clusterId: Int,
    val marker: Boolean,
    val overlapRanges: List<MinuteRange>,
    val eventIds: List<Long>,
    val overflow: Boolean
)

data class CalendarLayoutResult(val blocks: List<CalendarBlock>, val sanitizedCount: Int)

data class CalendarLayoutConfig(
    val rangeStart: LocalDate,
    val rangeEndExclusive: LocalDate,
    val perHourLogicalHeight: Float,
    val columnWidth: Float,
    val minimumVisualHeight: Float,
    val minimumAccessibleLaneWidth: Float
)

object CalendarLayoutEngine {
    fun layout(events: List<Event>, config: CalendarLayoutConfig): CalendarLayoutResult {
        validateLayoutArguments(config)
        val (segments, sanitizedCount) = buildSegments(
            events,
            config.rangeStart,
            config.rangeEndExclusive
        )
        assignLanesAndOverlaps(segments)
        val maximumVisibleLanes =
            floor(config.columnWidth / config.minimumAccessibleLaneWidth).toInt().coerceAtLeast(1)
        val blocks = buildBlocks(
            segments,
            config.perHourLogicalHeight,
            config.minimumVisualHeight,
            maximumVisibleLanes
        )
        return CalendarLayoutResult(blocks, sanitizedCount)
    }
}

internal data class Segment(
    val event: Event,
    val column: Int,
    val startMinute: Int,
    val endMinute: Int,
    val marker: Boolean,
    var lane: Int = 0,
    var laneCount: Int = 1,
    var clusterId: Int = 0,
    val overlaps: MutableList<MinuteRange> = mutableListOf(),
    val eventIds: List<Long> = listOf(event.id)
)

private fun validateLayoutArguments(config: CalendarLayoutConfig) {
    require(config.rangeStart.isBefore(config.rangeEndExclusive)) {
        "Date range must be increasing"
    }
    listOf(
        config.perHourLogicalHeight,
        config.columnWidth,
        config.minimumVisualHeight,
        config.minimumAccessibleLaneWidth
    ).forEach {
        require(it.isFinite() && it > 0f) { "Layout dimensions must be finite and positive" }
    }
}

private fun buildSegments(
    events: List<Event>,
    rangeStart: LocalDate,
    rangeEndExclusive: LocalDate
): Pair<MutableList<Segment>, Int> {
    val segments = mutableListOf<Segment>()
    var sanitizedCount = 0
    val seenIds = mutableSetOf<Long>()
    for (event in events) {
        val valid = validatedVisibleEvent(event, seenIds)
        if (valid == null) {
            sanitizedCount += 1
        } else {
            appendEventSegments(valid, rangeStart, rangeEndExclusive, segments)
        }
    }
    segments.sortWith(
        compareBy(Segment::column, Segment::startMinute, Segment::endMinute, {
            it.event.id
        })
    )
    return segments to sanitizedCount
}

private fun validatedVisibleEvent(event: Event, seenIds: MutableSet<Long>): Event? {
    val valid = try {
        EventValidator.validatePersisted(event)
    } catch (_: EventValidationException) {
        return null
    }
    return valid.takeIf { it.deletedAt == null && seenIds.add(it.id) }
}

private fun appendEventSegments(
    event: Event,
    rangeStart: LocalDate,
    rangeEndExclusive: LocalDate,
    destination: MutableList<Segment>
) {
    val marker = event.eventType == EventType.REMINDER && event.estimatedDurationMinutes == 0
    val eventEnd = when (event.eventType) {
        EventType.TIMESPAN -> requireNotNull(event.endTime)
        EventType.REMINDER -> reminderEnd(event.startTime, event.estimatedDurationMinutes)
    }
    var date = maxOf(event.startTime.toLocalDate(), rangeStart)
    while (date.isBefore(rangeEndExclusive) && date.atStartOfDay().isBefore(eventEnd)) {
        val dayStart = date.atStartOfDay()
        val dayEnd = date.plusDays(1).atStartOfDay()
        val clippedStart = maxOf(event.startTime, dayStart)
        val clippedEnd = minOf(eventEnd, dayEnd)
        if (clippedStart.isBefore(clippedEnd)) {
            destination += Segment(
                event = event,
                column = ChronoUnit.DAYS.between(rangeStart, date).toInt(),
                startMinute = ChronoUnit.MINUTES.between(dayStart, clippedStart).toInt(),
                endMinute = ChronoUnit.MINUTES.between(dayStart, clippedEnd).toInt(),
                marker = marker
            )
        }
        date = date.plusDays(1)
    }
}

private fun assignLanesAndOverlaps(segments: MutableList<Segment>) {
    var nextClusterId = 0
    for (columnSegments in segments.groupBy(Segment::column).toSortedMap().values) {
        val active = mutableListOf<Segment>()
        var cluster = mutableListOf<Segment>()
        for (segment in columnSegments) {
            active.removeAll { it.endMinute <= segment.startMinute }
            if (active.isEmpty()) {
                finishCluster(cluster)
                cluster = mutableListOf()
                segment.clusterId = nextClusterId++
            } else {
                segment.clusterId = cluster.first().clusterId
            }
            segment.lane = firstAvailableLane(active)
            addPairwiseOverlaps(segment, active)
            active += segment
            cluster += segment
        }
        finishCluster(cluster)
    }
}

private fun firstAvailableLane(active: List<Segment>): Int {
    val used = active.mapTo(mutableSetOf(), Segment::lane)
    var lane = 0
    while (lane in used) lane += 1
    return lane
}

private fun addPairwiseOverlaps(segment: Segment, active: List<Segment>) {
    for (other in active) {
        val overlap = MinuteRange(
            max(segment.startMinute, other.startMinute),
            min(segment.endMinute, other.endMinute)
        )
        if (overlap.startMinute < overlap.endMinute) {
            segment.overlaps += overlap
            other.overlaps += overlap
        }
    }
}

private fun finishCluster(cluster: List<Segment>) {
    if (cluster.isEmpty()) return
    val laneCount = cluster.maxOf(Segment::lane) + 1
    cluster.forEach { segment ->
        segment.laneCount = laneCount
        val merged = mergeMinuteRanges(segment.overlaps)
        segment.overlaps.clear()
        segment.overlaps += merged
    }
}

private fun reminderEnd(start: LocalDateTime, durationMinutes: Int): LocalDateTime = try {
    start.plusMinutes(max(1, durationMinutes).toLong())
} catch (_: DateTimeException) {
    LocalDateTime.MAX
}
