package com.molotov.clender.ui.calendar

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.molotov.clender.domain.calendar.CalendarBlock
import kotlin.math.roundToInt

private val MINIMUM_OVERLAY_WIDTH = 48.dp

@Composable
internal fun TimelineSemanticOverlay(
    blocks: List<CalendarBlock>,
    overlayLayout: TimelineOverlayLayout,
    onEventClick: (Long) -> Unit,
    onOverflowClick: (List<Long>) -> Unit
) {
    val layoutDirection = LocalLayoutDirection.current
    val renderableBlocks = blocks.filter { block ->
        block.id > 0L &&
            block.column in 0 until overlayLayout.columnCount &&
            block.lane >= 0 &&
            block.lane < block.laneCount &&
            block.eventIds.isNotEmpty() &&
            block.eventIds.all { it > 0L } &&
            block.eventIds.distinct().size == block.eventIds.size &&
            (block.overflow || (block.eventIds.size == 1 && block.eventIds.single() == block.id))
    }
    Layout(
        modifier = Modifier
            .fillMaxSize()
            .testTag("calendar_timeline_semantics_layer")
            .semantics { },
        content = {
            renderableBlocks.forEach { block ->
                TimelineBlockOverlay(
                    block = block,
                    markerLabelHeight = overlayLayout.timelineHeight *
                        markerLabelSpaceFraction(block, renderableBlocks),
                    layout = overlayLayout,
                    onEventClick = onEventClick,
                    onOverflowClick = onOverflowClick
                )
            }
        }
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val columnWidthPx = with(this) { overlayLayout.columnWidth.toPx() }
        val timelineHeightPx = with(this) { overlayLayout.timelineHeight.toPx() }
        layout(constraints.maxWidth, constraints.maxHeight) {
            renderableBlocks.zip(placeables).forEach { (block, placeable) ->
                placeable.place(
                    timelineBlockLeft(block, overlayLayout, layoutDirection, columnWidthPx),
                    timelineBlockTop(block, timelineHeightPx)
                )
            }
        }
    }
}

@Composable
private fun TimelineBlockOverlay(
    block: CalendarBlock,
    markerLabelHeight: Dp,
    layout: TimelineOverlayLayout,
    onEventClick: (Long) -> Unit,
    onOverflowClick: (List<Long>) -> Unit
) {
    if (block.column < 0 || block.lane < 0 || block.laneCount <= 0) return
    val laneWidth = (layout.columnWidth / block.laneCount)
        .coerceAtLeast(MINIMUM_OVERLAY_WIDTH)
    val visualHeight = layout.timelineHeight * block.heightFraction.coerceIn(0f, 1f)
    TimelineEventBlock(
        block = block,
        onEventClick = onEventClick,
        onOverflowClick = onOverflowClick,
        eventLabels = layout.eventLabels,
        displayLabels = layout.displayLabels,
        markerLabelHeight = markerLabelHeight,
        modifier = Modifier
            .requiredWidth(laneWidth)
            .requiredHeight(visualHeight.coerceAtLeast(MINIMUM_OVERLAY_WIDTH))
    )
}

internal fun markerLabelSpaceFraction(block: CalendarBlock, blocks: List<CalendarBlock>): Float {
    val left = block.lane.toFloat() / block.laneCount
    val right = (block.lane + 1f) / block.laneCount
    val nextTop = blocks.asSequence().filter { other ->
        other !== block && other.column == block.column && other.topFraction >= block.topFraction &&
            other.lane.toFloat() / other.laneCount < right &&
            (other.lane + 1f) / other.laneCount > left
    }.minOfOrNull { it.topFraction } ?: 1f
    return (nextTop - block.topFraction).coerceAtLeast(0f)
}

private fun timelineBlockLeft(
    block: CalendarBlock,
    layout: TimelineOverlayLayout,
    layoutDirection: LayoutDirection,
    columnWidthPx: Float
): Int {
    val visualColumn = if (layoutDirection == LayoutDirection.Ltr) {
        block.column
    } else {
        layout.columnCount - block.column - 1
    }
    val visualLane = if (layoutDirection == LayoutDirection.Ltr) {
        block.lane
    } else {
        block.laneCount - block.lane - 1
    }
    val laneWidth = columnWidthPx / block.laneCount
    return (columnWidthPx * visualColumn + laneWidth * visualLane).roundToInt()
}

private fun timelineBlockTop(block: CalendarBlock, timelineHeightPx: Float): Int =
    (timelineHeightPx * block.topFraction.coerceIn(0f, 1f)).roundToInt()

internal data class TimelineOverlayLayout(
    val columnWidth: Dp,
    val timelineHeight: Dp,
    val eventLabels: Map<Long, String>,
    val columnCount: Int,
    val displayLabels: Map<Long, String> = emptyMap()
)
