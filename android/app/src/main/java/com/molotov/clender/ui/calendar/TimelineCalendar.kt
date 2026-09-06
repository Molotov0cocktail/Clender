package com.molotov.clender.ui.calendar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.domain.calendar.CalendarBlock
import com.molotov.clender.domain.calendar.CalendarLayoutResult
import java.time.LocalDate
import kotlin.math.max

private const val HOURS_PER_DAY = 24
private const val DAY_COLUMN_COUNT = 1
private const val WEEK_COLUMN_COUNT = 7
private const val OVERLAP_STRIPE_STEP_PX = 12f
private val HOUR_HEIGHT = 60.dp
private val TIME_GUTTER_WIDTH = 56.dp
private val MINIMUM_COLUMN_WIDTH = 48.dp
private val MINIMUM_TIMELINE_WIDTH = 304.dp

@Composable
fun CalendarTimeline(
    dates: List<LocalDate>,
    layoutResult: CalendarLayoutResult,
    onEventClick: (Long) -> Unit,
    onOverflowClick: (List<Long>) -> Unit,
    eventLabels: Map<Long, String> = emptyMap()
) {
    CalendarTimeline(
        model = CalendarTimelineModel(dates, layoutResult, eventLabels),
        onEventClick = onEventClick,
        onOverflowClick = onOverflowClick
    )
}

@Composable
fun CalendarTimeline(
    model: CalendarTimelineModel,
    onEventClick: (Long) -> Unit,
    onOverflowClick: (List<Long>) -> Unit,
    modifier: Modifier = Modifier
) {
    val dates = model.dates
    val layoutResult = model.layoutResult
    require(dates.size == DAY_COLUMN_COUNT || dates.size == WEEK_COLUMN_COUNT) {
        "Timeline requires one or seven dates"
    }
    val horizontalScroll = rememberSaveable(
        saver = androidx.compose.foundation.ScrollState.Saver
    ) { androidx.compose.foundation.ScrollState(0) }
    val verticalScroll = rememberSaveable(
        saver = androidx.compose.foundation.ScrollState.Saver
    ) { androidx.compose.foundation.ScrollState(0) }
    val maximumLaneCount = layoutResult.blocks.maxOfOrNull { it.laneCount.coerceAtLeast(1) } ?: 1
    val minimumColumnWidth = MINIMUM_COLUMN_WIDTH * maximumLaneCount
    val bodyWidth = maxOf(
        MINIMUM_TIMELINE_WIDTH,
        MINIMUM_COLUMN_WIDTH * dates.size,
        minimumColumnWidth * dates.size
    )
    Column(modifier = modifier.fillMaxSize()) {
        TimelineDateHeaders(dates, bodyWidth, model.locale, horizontalScroll)
        Row(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(verticalScroll)
                .horizontalScroll(horizontalScroll)
        ) {
            TimelineHourLabels()
            TimelineBody(
                model = TimelineBodyModel(
                    dates = dates,
                    layoutResult = layoutResult,
                    eventLabels = model.eventLabels,
                    bodyWidth = bodyWidth
                ),
                onEventClick = onEventClick,
                onOverflowClick = onOverflowClick
            )
        }
        if (layoutResult.sanitizedCount > 0) {
            val hiddenDescription = stringResource(R.string.calendar_semantics_sanitized_hidden)
            Spacer(
                modifier = Modifier
                    .testTag("calendar_sanitized_notice")
                    .semantics { contentDescription = hiddenDescription }
            )
        }
    }
}

data class CalendarTimelineModel(
    val dates: List<LocalDate>,
    val layoutResult: CalendarLayoutResult,
    val eventLabels: Map<Long, String> = emptyMap(),
    val locale: java.util.Locale = java.util.Locale.getDefault()
)

private data class TimelineBodyModel(
    val dates: List<LocalDate>,
    val layoutResult: CalendarLayoutResult,
    val eventLabels: Map<Long, String>,
    val bodyWidth: Dp
)

@Composable
private fun TimelineDateHeaders(
    dates: List<LocalDate>,
    bodyWidth: Dp,
    locale: java.util.Locale,
    horizontalScroll: androidx.compose.foundation.ScrollState
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(horizontalScroll)
            .requiredWidth(TIME_GUTTER_WIDTH + bodyWidth)
    ) {
        Spacer(Modifier.width(TIME_GUTTER_WIDTH))
        dates.forEachIndexed { index, date ->
            val localizedDate = date.format(
                java.time.format.DateTimeFormatter.ofLocalizedDate(
                    java.time.format.FormatStyle.MEDIUM
                ).withLocale(locale)
            )
            Text(
                text = localizedDate,
                modifier = Modifier
                    .requiredWidth(bodyWidth / dates.size)
                    .testTag("calendar_timeline_column_$index")
                    .semantics { contentDescription = localizedDate }
                    .padding(4.dp),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun TimelineHourLabels() {
    Column(modifier = Modifier.width(TIME_GUTTER_WIDTH)) {
        repeat(HOURS_PER_DAY) { hour ->
            Text(
                text = "%02d:00".format(hour),
                modifier = Modifier
                    .height(HOUR_HEIGHT)
                    .testTag("calendar_timeline_hour_$hour")
                    .padding(horizontal = 4.dp),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun TimelineBody(
    model: TimelineBodyModel,
    onEventClick: (Long) -> Unit,
    onOverflowClick: (List<Long>) -> Unit
) {
    val dates = model.dates
    val layoutResult = model.layoutResult
    val timelineHeight = HOUR_HEIGHT * HOURS_PER_DAY
    val columnWidth = model.bodyWidth / dates.size
    val overlayLayout = TimelineOverlayLayout(
        columnWidth = columnWidth,
        timelineHeight = timelineHeight,
        eventLabels = model.eventLabels,
        columnCount = dates.size
    )
    Box(
        modifier = Modifier
            .requiredHeight(timelineHeight)
            .requiredWidth(model.bodyWidth)
    ) {
        TimelineVisualLayer(
            blocks = layoutResult.blocks,
            columnCount = dates.size,
            layoutDirection = LocalLayoutDirection.current
        )
        TimelineSemanticOverlay(
            blocks = layoutResult.blocks,
            overlayLayout = overlayLayout,
            onEventClick = onEventClick,
            onOverflowClick = onOverflowClick
        )
    }
}

@Composable
private fun TimelineVisualLayer(
    blocks: List<CalendarBlock>,
    columnCount: Int,
    layoutDirection: LayoutDirection
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val blockColor = MaterialTheme.colorScheme.primaryContainer
    val overlapColor = MaterialTheme.colorScheme.onPrimaryContainer
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .testTag("calendar_timeline_visual_layer")
    ) {
        repeat(HOURS_PER_DAY + 1) { hour ->
            val y = size.height * hour / HOURS_PER_DAY
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y))
        }
        repeat(columnCount + 1) { column ->
            val x = size.width * column / columnCount
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height))
        }
        blocks.forEach { block ->
            drawTimelineBlock(
                block,
                columnCount,
                blockColor,
                overlapColor,
                layoutDirection
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTimelineBlock(
    block: CalendarBlock,
    columnCount: Int,
    blockColor: Color,
    overlapColor: Color,
    layoutDirection: LayoutDirection
) {
    if (block.column !in 0 until columnCount || block.lane !in 0 until block.laneCount) return
    val columnWidth = size.width / columnCount
    val laneWidth = columnWidth / block.laneCount
    val visualColumn = if (layoutDirection == LayoutDirection.Ltr) {
        block.column
    } else {
        columnCount - block.column - 1
    }
    val visualLane = if (layoutDirection == LayoutDirection.Ltr) {
        block.lane
    } else {
        block.laneCount - block.lane - 1
    }
    val left = columnWidth * visualColumn + laneWidth * visualLane
    val top = size.height * block.topFraction.coerceIn(0f, 1f)
    val height = max(1f, size.height * block.heightFraction.coerceIn(0f, 1f))
    if (block.marker) {
        drawLine(
            color = blockColor,
            start = Offset(left, top),
            end = Offset(left + laneWidth, top),
            strokeWidth = 4f,
            cap = StrokeCap.Round
        )
    } else {
        drawRect(blockColor, Offset(left, top), Size(laneWidth, height))
    }
    if (block.overlapRanges.isNotEmpty() || block.overflow) {
        var stripeX = left
        while (stripeX < left + laneWidth) {
            drawLine(
                overlapColor,
                Offset(stripeX, top),
                Offset((stripeX + height).coerceAtMost(left + laneWidth), top + height)
            )
            stripeX += OVERLAP_STRIPE_STEP_PX
        }
    }
}
