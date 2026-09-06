package com.molotov.clender.domain.calendar

import kotlin.math.max
import kotlin.math.min

private const val HOURS_PER_DAY = 24f

internal fun mergeMinuteRanges(ranges: List<MinuteRange>): List<MinuteRange> {
    val merged = mutableListOf<MinuteRange>()
    for (range in ranges.sortedWith(compareBy(MinuteRange::startMinute, MinuteRange::endMinute))) {
        if (range.endMinute > range.startMinute) addMergedRange(merged, range)
    }
    return merged
}

private fun addMergedRange(merged: MutableList<MinuteRange>, range: MinuteRange) {
    val previous = merged.lastOrNull()
    if (previous != null && range.startMinute <= previous.endMinute) {
        merged[merged.lastIndex] =
            MinuteRange(previous.startMinute, max(previous.endMinute, range.endMinute))
    } else {
        merged += range
    }
}

internal fun buildBlocks(
    segments: List<Segment>,
    perHourLogicalHeight: Float,
    minimumVisualHeight: Float,
    maximumVisibleLanes: Int
): List<CalendarBlock> {
    val result = mutableListOf<CalendarBlock>()
    for (cluster in segments.groupBy { it.column to it.clusterId }.values) {
        result += buildClusterBlocks(
            cluster,
            perHourLogicalHeight,
            minimumVisualHeight,
            maximumVisibleLanes
        )
    }
    return result.sortedWith(
        compareBy(
            CalendarBlock::column,
            CalendarBlock::topMinute,
            CalendarBlock::lane,
            CalendarBlock::id
        )
    )
}

private fun buildClusterBlocks(
    cluster: List<Segment>,
    perHourLogicalHeight: Float,
    minimumVisualHeight: Float,
    maximumVisibleLanes: Int
): List<CalendarBlock> {
    val sourceLaneCount = cluster.first().laneCount
    if (sourceLaneCount <= maximumVisibleLanes) {
        return cluster.map {
            it.toBlock(perHourLogicalHeight, minimumVisualHeight, sourceLaneCount)
        }
    }
    val overflowLane = maximumVisibleLanes - 1
    val visible = cluster.filter { it.lane < overflowLane }.map {
        it.toBlock(perHourLogicalHeight, minimumVisualHeight, maximumVisibleLanes)
    }
    val overflow = aggregateOverflowGroups(
        cluster.filter {
            it.lane >= overflowLane
        },
        maximumVisibleLanes
    ).map {
        it.toBlock(perHourLogicalHeight, minimumVisualHeight, maximumVisibleLanes, overflow = true)
    }
    return visible + overflow
}

private fun aggregateOverflowGroups(segments: List<Segment>, visibleLaneCount: Int): List<Segment> {
    val ordered = segments.sortedWith(
        compareBy(Segment::startMinute, Segment::endMinute, {
            it.event.id
        })
    )
    val groups = mutableListOf<MutableList<Segment>>()
    var groupEnd = -1
    for (segment in ordered) {
        if (groups.isEmpty() || segment.startMinute >= groupEnd) {
            groups += mutableListOf(segment)
            groupEnd = segment.endMinute
        } else {
            groups.last() += segment
            groupEnd = max(groupEnd, segment.endMinute)
        }
    }
    return groups.map { aggregateOverflowGroup(it, visibleLaneCount) }
}

private fun aggregateOverflowGroup(segments: List<Segment>, visibleLaneCount: Int): Segment {
    val first = segments.first()
    return first.copy(
        startMinute = segments.minOf(Segment::startMinute),
        endMinute = segments.maxOf(Segment::endMinute),
        marker = segments.all(Segment::marker),
        lane = visibleLaneCount - 1,
        laneCount = visibleLaneCount,
        overlaps = segments.flatMap(Segment::overlaps).toMutableList(),
        eventIds = segments.flatMap(Segment::eventIds).distinct()
    )
}

private fun Segment.toBlock(
    perHourLogicalHeight: Float,
    minimumVisualHeight: Float,
    visibleLaneCount: Int,
    overflow: Boolean = false
): CalendarBlock {
    val topFraction = startMinute.toFloat() / MINUTES_PER_DAY
    val realHeight = (endMinute - startMinute).toFloat() / MINUTES_PER_DAY
    val minimumHeight = minimumVisualHeight / (perHourLogicalHeight * HOURS_PER_DAY)
    val visualHeight = if (marker) {
        realHeight
    } else {
        min(
            1f - topFraction,
            max(realHeight, minimumHeight)
        )
    }
    return CalendarBlock(
        id = event.id,
        column = column,
        topFraction = topFraction,
        heightFraction = visualHeight,
        topMinute = startMinute,
        durationMinutes = endMinute - startMinute,
        lane = lane,
        laneCount = visibleLaneCount,
        clusterId = clusterId,
        marker = marker,
        overlapRanges = mergeMinuteRanges(overlaps),
        eventIds = eventIds,
        overflow = overflow
    )
}
