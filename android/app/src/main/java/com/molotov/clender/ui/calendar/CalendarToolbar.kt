package com.molotov.clender.ui.calendar

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.ui.state.CalendarMode
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val ACTION_SIZE = 48.dp
private val TOOLBAR_SHAPE = RoundedCornerShape(16.dp)
private const val SELECTOR_SURFACE_ALPHA = 0.7f

@Composable
internal fun CalendarToolbar(model: CalendarScreenModel, actions: CalendarScreenActions) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CalendarRangeHeader(model, actions)
        CalendarModeSelector(model.state.mode, actions.onChangeMode)
    }
}

@Composable
private fun CalendarRangeHeader(model: CalendarScreenModel, actions: CalendarScreenActions) {
    val title = calendarRangeTitle(model)
    val measurer = rememberTextMeasurer()
    val titleWidth = measurer.measure(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        softWrap = false
    ).size.width
    val todayWidth = measurer.measure(
        text = stringResource(R.string.calendar_today),
        style = MaterialTheme.typography.labelLarge,
        softWrap = false
    ).size.width
    val density = LocalDensity.current
    val requiredWidth = with(density) {
        titleWidth.toDp() + ACTION_SIZE * 2 + maxOf(ACTION_SIZE, todayWidth.toDp() + 24.dp)
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (requiredWidth <= maxWidth) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RangeTitle(title, Modifier.weight(1f))
                RangeNavigation(model, actions)
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                RangeTitle(title, Modifier.fillMaxWidth())
                RangeNavigation(model, actions, Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
private fun RangeTitle(title: String, modifier: Modifier) {
    Text(
        text = title,
        modifier = modifier.testTag("calendar_range_title").semantics { heading() },
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleLarge
    )
}

@Composable
private fun RangeNavigation(
    model: CalendarScreenModel,
    actions: CalendarScreenActions,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        RangeArrow(model, actions, previous = true)
        TextButton(
            onClick = { actions.onSelectDate(model.today) },
            modifier = Modifier
                .sizeIn(minWidth = ACTION_SIZE, minHeight = ACTION_SIZE)
                .testTag("calendar_today")
        ) {
            Text(stringResource(R.string.calendar_today), maxLines = 1)
        }
        RangeArrow(model, actions, previous = false)
    }
}

@Composable
private fun RangeArrow(
    model: CalendarScreenModel,
    actions: CalendarScreenActions,
    previous: Boolean
) {
    IconButton(
        onClick = {
            actions.onSelectDate(
                CalendarRangePolicy.move(
                    model.state.selectedDate,
                    model.state.mode,
                    if (previous) -1 else 1
                )
            )
        },
        modifier = Modifier
            .sizeIn(minWidth = ACTION_SIZE, minHeight = ACTION_SIZE)
            .testTag(if (previous) "calendar_previous_range" else "calendar_next_range")
    ) {
        Icon(
            imageVector = if (previous) {
                Icons.AutoMirrored.Filled.ArrowBack
            } else {
                Icons.AutoMirrored.Filled.ArrowForward
            },
            contentDescription = stringResource(
                if (previous) R.string.calendar_previous_range else R.string.calendar_next_range
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CalendarModeSelector(selected: CalendarMode, onChangeMode: (CalendarMode) -> Unit) {
    Surface(
        shape = TOOLBAR_SHAPE,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = SELECTOR_SURFACE_ALPHA)
    ) {
        Row(modifier = Modifier.fillMaxWidth().selectableGroup().padding(4.dp)) {
            CalendarMode.entries.forEach { mode ->
                val active = selected == mode
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(TOOLBAR_SHAPE)
                        .selectable(
                            selected = active,
                            role = Role.Tab,
                            onClick = { onChangeMode(mode) }
                        )
                        .testTag("calendar_mode_${mode.wireValue}"),
                    shape = TOOLBAR_SHAPE,
                    color = if (active) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                    contentColor = if (active) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .sizeIn(minWidth = ACTION_SIZE, minHeight = ACTION_SIZE)
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(mode.labelResource()),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private fun calendarRangeTitle(model: CalendarScreenModel): String {
    val state = model.state
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(model.locale)
    return when (state.mode) {
        CalendarMode.MONTH -> state.selectedDate.format(
            DateTimeFormatter.ofPattern(
                DateFormat.getBestDateTimePattern(model.locale, "yMMMM"),
                model.locale
            )
        )

        CalendarMode.DAY -> state.selectedDate.format(formatter)

        CalendarMode.WEEK -> "${state.queryRange.dates.first().format(formatter)} – " +
            state.queryRange.dates.last().format(formatter)
    }
}

private fun CalendarMode.labelResource(): Int = when (this) {
    CalendarMode.MONTH -> R.string.calendar_mode_month
    CalendarMode.WEEK -> R.string.calendar_mode_week
    CalendarMode.DAY -> R.string.calendar_mode_day
}
