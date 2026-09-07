package com.molotov.clender.ui.calendar

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private val MINIMUM_GUTTER_WIDTH = 56.dp
private val MINIMUM_COLUMN_WIDTH = 48.dp
private val MINIMUM_TIMELINE_WIDTH = 304.dp
private val LABEL_SIDE_PADDING = 4.dp
private const val HOURS_PER_DAY = 24

internal data class TimelineDimensions(val gutterWidth: Dp, val bodyWidth: Dp, val headerHeight: Dp)

@Composable
internal fun timelineDimensions(
    model: CalendarTimelineModel,
    viewportWidth: Dp
): TimelineDimensions {
    val measurer = rememberTextMeasurer()
    val hourStyle = MaterialTheme.typography.labelSmall
    val dateStyle = MaterialTheme.typography.labelMedium
    val hourWidthPx = (0 until HOURS_PER_DAY).maxOf { hour ->
        measurer.measure("%02d:00".format(hour), style = hourStyle, softWrap = false)
            .multiParagraph.maxIntrinsicWidth
    }
    val dateWordWidthPx = model.dates.maxOf { date ->
        measurer.measure(
            localizedTimelineDate(date, model.locale),
            style = dateStyle,
            softWrap = false
        ).multiParagraph.intrinsics.minIntrinsicWidth
    }
    val maximumLaneCount = model.layoutResult.blocks.maxOfOrNull {
        it.laneCount.coerceAtLeast(1)
    } ?: 1
    return with(LocalDensity.current) {
        val horizontalPaddingPx = LABEL_SIDE_PADDING.roundToPx() * 2
        val gutter = maxOf(
            MINIMUM_GUTTER_WIDTH,
            (ceil(hourWidthPx).toInt() + horizontalPaddingPx).toDp()
        )
        val column = maxOf(
            MINIMUM_COLUMN_WIDTH * maximumLaneCount,
            (ceil(dateWordWidthPx).toInt() + horizontalPaddingPx).toDp()
        )
        val bodyWidth = maxOf(
            MINIMUM_TIMELINE_WIDTH,
            column * model.dates.size,
            viewportWidth - gutter
        )
        TimelineDimensions(gutter, bodyWidth, timelineHeaderHeight(model, bodyWidth))
    }
}

@Composable
private fun timelineHeaderHeight(model: CalendarTimelineModel, bodyWidth: Dp): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium
    return with(LocalDensity.current) {
        val padding = LABEL_SIDE_PADDING.roundToPx() * 2
        val columnPixels = bodyWidth.roundToPx().toFloat() / model.dates.size
        model.dates.mapIndexed { index, date ->
            val width = ((index + 1) * columnPixels).roundToInt() -
                (index * columnPixels).roundToInt()
            measurer.measure(
                localizedTimelineDate(date, model.locale),
                style = style,
                constraints = Constraints(maxWidth = (width - padding).coerceAtLeast(0))
            ).size.height + padding
        }.max().toDp()
    }
}

internal fun localizedTimelineDate(date: LocalDate, locale: Locale): String = date.format(
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
)
