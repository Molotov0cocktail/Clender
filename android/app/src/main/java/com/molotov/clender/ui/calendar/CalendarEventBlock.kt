package com.molotov.clender.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.domain.calendar.CalendarBlock

private val MINIMUM_BLOCK_TOUCH_SIZE = 48.dp
private const val BLOCK_HOURS_PER_DAY = 24

// Keep optional visual inputs separate from full accessibility labels and event callbacks.
@Suppress("LongParameterList")
@Composable
fun TimelineEventBlock(
    block: CalendarBlock,
    onEventClick: (Long) -> Unit,
    onOverflowClick: (List<Long>) -> Unit,
    modifier: Modifier = Modifier,
    eventLabels: Map<Long, String> = emptyMap(),
    displayLabels: Map<Long, String> = emptyMap(),
    markerLabelHeight: Dp = MINIMUM_BLOCK_TOUCH_SIZE
) {
    val eventIds = validEventIds(block) ?: return
    val label = eventLabels[block.id]?.trim()?.takeIf { it.isNotEmpty() && !block.overflow }
    val state = blockStateDescription(block, eventIds.size)
    val description = listOfNotNull(label, state).joinToString(separator = ", ")
    val colors = calendarEventColors(
        block.id,
        MaterialTheme.colorScheme.background.luminance() < CALENDAR_DARK_BACKGROUND_THRESHOLD
    )
    val visualHeight = HOUR_HEIGHT * BLOCK_HOURS_PER_DAY * block.heightFraction.coerceIn(0f, 1f)
    val labelHeight = if (block.marker) {
        markerLabelHeight.coerceIn(
            0.dp,
            MINIMUM_BLOCK_TOUCH_SIZE
        )
    } else {
        visualHeight
    }
    val minimumTextHeight = with(LocalDensity.current) {
        MaterialTheme.typography.labelLarge.fontSize.toDp() + 6.dp
    }
    val tag = if (block.overflow) {
        "calendar_overflow_${block.column}_${block.topMinute}"
    } else {
        "calendar_event_${block.column}_${block.id}"
    }
    Box(
        modifier = modifier
            .sizeIn(minWidth = MINIMUM_BLOCK_TOUCH_SIZE, minHeight = MINIMUM_BLOCK_TOUCH_SIZE)
            .testTag(tag)
            .semantics {
                role = Role.Button
                contentDescription = description
                stateDescription = state
            }
            .clickable {
                if (block.overflow) {
                    onOverflowClick(eventIds)
                } else {
                    onEventClick(block.id)
                }
            }
    ) {
        if (label != null && labelHeight >= minimumTextHeight) {
            Text(
                text = displayLabels[block.id] ?: label,
                modifier = Modifier
                    .heightIn(max = labelHeight)
                    .clipToBounds()
                    .padding(start = 8.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                color = colors.foreground,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = if (block.marker) 1 else 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun blockStateDescription(block: CalendarBlock, eventCount: Int): String = buildList {
    if (block.overflow) {
        add(
            pluralStringResource(
                R.plurals.calendar_semantics_overflow,
                eventCount,
                eventCount
            )
        )
    } else if (block.marker) {
        add(stringResource(R.string.calendar_semantics_marker))
    }
    add(
        stringResource(
            R.string.calendar_semantics_lane,
            block.lane + 1,
            block.laneCount
        )
    )
    if (block.overlapRanges.isNotEmpty()) {
        add(stringResource(R.string.calendar_semantics_overlap))
    }
}.joinToString(separator = ", ")

private fun validEventIds(block: CalendarBlock): List<Long>? {
    val ids = block.eventIds
    val blockGeometryIsValid = block.id > 0L &&
        block.column >= 0 &&
        block.lane >= 0 &&
        block.lane < block.laneCount
    val idsAreValid = ids.isNotEmpty() &&
        ids.all { it > 0L } &&
        ids.distinct().size == ids.size
    val singleBlockMappingIsValid = block.overflow ||
        (ids.size == 1 && ids.single() == block.id)
    return ids.takeIf {
        blockGeometryIsValid && idsAreValid && singleBlockMappingIsValid
    }
}
