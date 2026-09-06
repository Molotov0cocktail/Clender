package com.molotov.clender.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.domain.calendar.CalendarBlock

private val MINIMUM_BLOCK_TOUCH_SIZE = 48.dp

@Composable
fun TimelineEventBlock(
    block: CalendarBlock,
    onEventClick: (Long) -> Unit,
    onOverflowClick: (List<Long>) -> Unit,
    modifier: Modifier = Modifier,
    eventLabels: Map<Long, String> = emptyMap()
) {
    val eventIds = validEventIds(block) ?: return
    val label = eventLabels[block.id]?.trim()?.takeIf { it.isNotEmpty() && !block.overflow }
    val state = blockStateDescription(block, eventIds.size)
    val description = listOfNotNull(label, state).joinToString(separator = ", ")
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
        if (label != null) {
            Text(
                text = label,
                maxLines = 2,
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
