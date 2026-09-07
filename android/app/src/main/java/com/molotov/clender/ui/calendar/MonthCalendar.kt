package com.molotov.clender.ui.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil

private const val DAYS_PER_WEEK = 7
private const val MAXIMUM_DAY_OF_MONTH = 31
private const val OUTSIDE_MONTH_ALPHA = 0.62f
private const val MONTH_SURFACE_ALPHA = 0.78f
private val DAY_CELL_MINIMUM_SIZE = 48.dp
private val DAY_CELL_SHAPE = RoundedCornerShape(14.dp)
private val MONTH_SHAPE = RoundedCornerShape(20.dp)

@Composable
fun MonthCalendar(
    model: MonthCalendarModel,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val displayedMonth = YearMonth.from(model.selectedDate)
    val dates = CalendarRangePolicy.monthGridDates(displayedMonth, model.locale)
    val firstDayOfWeek = dates.first().dayOfWeek
    val minimumCellWidth = minimumDateCellWidth(model.locale)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val gridWidth = maxOf(maxWidth, minimumCellWidth * DAYS_PER_WEEK)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MONTH_SHAPE,
            color = MaterialTheme.colorScheme.surface.copy(alpha = MONTH_SURFACE_ALPHA)
        ) {
            Column(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState(), enabled = gridWidth > maxWidth)
                    .width(gridWidth)
                    .padding(vertical = 8.dp)
            ) {
                WeekdayHeader(firstDayOfWeek, model.locale)
                MonthWeeks(dates, displayedMonth, model, onDateSelected)
            }
        }
    }
}

@Composable
private fun minimumDateCellWidth(locale: Locale): Dp {
    val measurer = rememberTextMeasurer()
    val dateStyle = MaterialTheme.typography.bodyLarge
    val weekdayStyle = MaterialTheme.typography.labelMedium.copy(textAlign = TextAlign.Center)
    val maximumDateWidth = remember(measurer, dateStyle) {
        (1..MAXIMUM_DAY_OF_MONTH).maxOf { number ->
            measurer.measure(number.toString(), style = dateStyle, softWrap = false)
                .multiParagraph.maxIntrinsicWidth
        }
    }
    val maximumWeekdayWidth = remember(measurer, weekdayStyle, locale) {
        DayOfWeek.values().maxOf { day ->
            measurer.measure(
                day.getDisplayName(TextStyle.SHORT, locale),
                style = weekdayStyle,
                softWrap = false
            ).centeredGlyphWidth()
        }
    }
    return with(LocalDensity.current) {
        val textWidth = ceil(maxOf(maximumDateWidth, maximumWeekdayWidth)).toInt()
        val paddingWidth = 4.dp.roundToPx() * 2
        maxOf(DAY_CELL_MINIMUM_SIZE, (textWidth + paddingWidth).toDp())
    }
}

private fun TextLayoutResult.centeredGlyphWidth(): Float {
    val paragraphWidth = multiParagraph.width
    val glyphs = layoutInput.text.indices.map(::getBoundingBox)
    val leftOverhang = -(glyphs.minOfOrNull { it.left } ?: 0f)
    val rightOverhang = (glyphs.maxOfOrNull { it.right } ?: paragraphWidth) - paragraphWidth
    return paragraphWidth + 2 * maxOf(0f, leftOverhang, rightOverhang)
}

@Composable
private fun MonthWeeks(
    dates: List<LocalDate>,
    displayedMonth: YearMonth,
    model: MonthCalendarModel,
    onDateSelected: (LocalDate) -> Unit
) {
    dates.chunked(DAYS_PER_WEEK).forEach { week ->
        Row(modifier = Modifier.fillMaxWidth()) {
            week.forEach { date ->
                MonthDayCell(
                    presentation = MonthDayPresentation(
                        date = date,
                        eventCount = model.eventCounts[date]?.coerceAtLeast(0) ?: 0,
                        selected = date == model.selectedDate,
                        today = date == model.today,
                        outsideMonth = YearMonth.from(date) != displayedMonth
                    ),
                    locale = model.locale,
                    onDateSelected = onDateSelected,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun WeekdayHeader(firstDay: DayOfWeek, locale: Locale) {
    Row(modifier = Modifier.fillMaxWidth()) {
        repeat(DAYS_PER_WEEK) { index ->
            val day = firstDay.plus(index.toLong())
            Text(
                text = day.getDisplayName(TextStyle.SHORT, locale),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = DAY_CELL_MINIMUM_SIZE)
                    .testTag("calendar_month_weekday_$index")
                    .padding(4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun MonthDayCell(
    presentation: MonthDayPresentation,
    locale: Locale,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier
) {
    val description = monthDayDescription(presentation, locale)
    val border = if (presentation.today) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    } else {
        null
    }
    val containerColor = if (presentation.selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    Surface(
        modifier = modifier
            .sizeIn(minWidth = DAY_CELL_MINIMUM_SIZE, minHeight = DAY_CELL_MINIMUM_SIZE)
            .testTag("calendar_month_day_${presentation.date}")
            .semantics {
                selected = presentation.selected
                role = Role.Button
                contentDescription = description
            }
            .clickable { onDateSelected(presentation.date) }
            .then(
                if (presentation.outsideMonth) Modifier.alpha(OUTSIDE_MONTH_ALPHA) else Modifier
            ),
        shape = DAY_CELL_SHAPE,
        border = border,
        color = containerColor,
        contentColor = if (presentation.selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = presentation.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1
            )
            Text(
                text = if (presentation.eventCount > 0) presentation.eventCount.toString() else " ",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun monthDayDescription(presentation: MonthDayPresentation, locale: Locale): String =
    buildList {
        add(
            presentation.date.format(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
            )
        )
        add(
            pluralStringResource(
                R.plurals.calendar_semantics_event_count,
                presentation.eventCount,
                presentation.eventCount
            )
        )
        if (presentation.selected) add(stringResource(R.string.calendar_semantics_selected))
        if (presentation.today) add(stringResource(R.string.calendar_semantics_today))
        if (presentation.outsideMonth) {
            add(stringResource(R.string.calendar_semantics_outside_month))
        }
    }.joinToString(separator = ", ")

private data class MonthDayPresentation(
    val date: LocalDate,
    val eventCount: Int,
    val selected: Boolean,
    val today: Boolean,
    val outsideMonth: Boolean
)

data class MonthCalendarModel(
    val selectedDate: LocalDate,
    val today: LocalDate,
    val locale: Locale,
    val eventCounts: Map<LocalDate, Int>
)
