package com.molotov.clender.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.ui.event.EventListLabels
import com.molotov.clender.ui.event.EventListNavigation
import com.molotov.clender.ui.event.EventListScreen
import com.molotov.clender.ui.event.eventListLabels
import com.molotov.clender.ui.navigation.AppRoute
import com.molotov.clender.ui.state.CalendarMode
import java.time.LocalDate
import java.util.Locale

private val MINIMUM_ACTION_SIZE = 48.dp
private val TIMELINE_GUTTER = 56.dp

data class CalendarScreenModel(
    val state: CalendarScreenState,
    val locale: Locale,
    val today: LocalDate
)

data class CalendarScreenActions(
    val onSelectDate: (LocalDate) -> Unit,
    val onChangeMode: (CalendarMode) -> Unit,
    val onRetry: () -> Unit,
    val onNavigate: (AppRoute.EventDetail) -> Unit
)

@Composable
fun CalendarScreen(
    model: CalendarScreenModel,
    actions: CalendarScreenActions,
    modifier: Modifier = Modifier
) {
    val labels = eventListLabels()
    val currentNavigate by rememberUpdatedState(actions.onNavigate)
    val overflowController = remember(labels, model.locale) {
        CalendarOverflowController(
            onNavigate = { route -> currentNavigate(route) },
            labels = labels,
            locale = model.locale
        )
    }
    Column(modifier = modifier.fillMaxSize()) {
        CalendarToolbar(model, actions)
        CalendarViewport(
            model = model,
            labels = labels,
            actions = actions,
            overflowController = overflowController,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
    }
    (overflowController.state as? CalendarOverflowState.Open)?.let { open ->
        CalendarOverflowSheet(
            state = open,
            onSelect = overflowController::select,
            onDismiss = overflowController::close
        )
    }
}

@Composable
fun SelectedDateEventList(
    state: CalendarScreenState,
    locale: Locale,
    onRetry: () -> Unit,
    onNavigate: (AppRoute.EventDetail) -> Unit,
    modifier: Modifier = Modifier
) {
    val labels = eventListLabels()
    EventListScreen(
        state = selectedDateListState(state, locale, labels),
        onRetry = onRetry,
        onNavigate = onNavigate,
        modifier = modifier
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarToolbar(model: CalendarScreenModel, actions: CalendarScreenActions) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CalendarMode.entries.forEach { mode ->
                FilterChip(
                    selected = model.state.mode == mode,
                    onClick = { actions.onChangeMode(mode) },
                    label = { Text(stringResource(mode.labelResource())) },
                    modifier = Modifier
                        .sizeIn(minWidth = MINIMUM_ACTION_SIZE, minHeight = MINIMUM_ACTION_SIZE)
                        .testTag("calendar_mode_${mode.wireValue}")
                )
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RangeButton(
                label = stringResource(R.string.calendar_previous_range),
                tag = "calendar_previous_range"
            ) {
                actions.onSelectDate(
                    CalendarRangePolicy.move(model.state.selectedDate, model.state.mode, -1)
                )
            }
            RangeButton(
                label = stringResource(R.string.calendar_today),
                tag = "calendar_today"
            ) { actions.onSelectDate(model.today) }
            RangeButton(
                label = stringResource(R.string.calendar_next_range),
                tag = "calendar_next_range"
            ) {
                actions.onSelectDate(
                    CalendarRangePolicy.move(model.state.selectedDate, model.state.mode, 1)
                )
            }
        }
    }
}

@Composable
private fun RangeButton(label: String, tag: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .sizeIn(minWidth = MINIMUM_ACTION_SIZE, minHeight = MINIMUM_ACTION_SIZE)
            .testTag(tag)
    ) {
        Text(label)
    }
}

@Composable
private fun CalendarViewport(
    model: CalendarScreenModel,
    labels: EventListLabels,
    actions: CalendarScreenActions,
    overflowController: CalendarOverflowController,
    modifier: Modifier
) {
    when (model.state.loadStatus) {
        CalendarLoadStatus.ERROR -> CalendarError(actions.onRetry, modifier)

        else -> Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (model.state.loadStatus == CalendarLoadStatus.LOADING) {
                Column(
                    modifier = Modifier
                        .testTag("calendar_loading"),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.calendar_loading))
                }
            } else if (model.state.loadStatus == CalendarLoadStatus.EMPTY) {
                Text(
                    text = stringResource(R.string.calendar_empty),
                    modifier = Modifier
                        .testTag("calendar_empty")
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                CalendarContent(
                    model = model,
                    labels = labels,
                    actions = actions,
                    overflowController = overflowController
                )
            }
        }
    }
}

@Composable
private fun CalendarContent(
    model: CalendarScreenModel,
    labels: EventListLabels,
    actions: CalendarScreenActions,
    overflowController: CalendarOverflowController
) {
    when (model.state.mode) {
        CalendarMode.MONTH -> MonthCalendar(
            model = MonthCalendarModel(
                selectedDate = model.state.selectedDate,
                today = model.today,
                locale = model.locale,
                eventCounts = monthEventCounts(model.state)
            ),
            onDateSelected = actions.onSelectDate,
            modifier = Modifier.verticalScroll(
                rememberSaveable(saver = androidx.compose.foundation.ScrollState.Saver) {
                    androidx.compose.foundation.ScrollState(0)
                }
            )
        )

        CalendarMode.WEEK,
        CalendarMode.DAY -> TimelineViewport(
            model = model,
            labels = labels,
            actions = actions,
            overflowController = overflowController
        )
    }
}

@Composable
private fun TimelineViewport(
    model: CalendarScreenModel,
    labels: EventListLabels,
    actions: CalendarScreenActions,
    overflowController: CalendarOverflowController
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val dates = model.state.queryRange.dates
        val availableWidth = (maxWidth - TIMELINE_GUTTER).coerceAtLeast(48.dp)
        val columnWidth = with(density) { availableWidth.toPx() } / dates.size
        val layout = remember(model.state.events, dates, columnWidth) {
            layoutEvents(model.state.events, dates, columnWidth)
        }
        CalendarTimeline(
            model = CalendarTimelineModel(
                dates = dates,
                layoutResult = layout,
                eventLabels = eventLabels(model.state.events, model.locale, labels),
                locale = model.locale
            ),
            onEventClick = { eventId ->
                EventListNavigation.detailRoute(eventId)?.let(actions.onNavigate)
            },
            onOverflowClick = { eventIds ->
                overflowController.open(eventIds, model.state.events)
            }
        )
    }
}

@Composable
private fun CalendarError(onRetry: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().testTag("calendar_error"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.calendar_error))
        Button(
            onClick = onRetry,
            modifier = Modifier
                .sizeIn(minWidth = MINIMUM_ACTION_SIZE, minHeight = MINIMUM_ACTION_SIZE)
                .testTag("calendar_retry")
        ) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

private fun CalendarMode.labelResource(): Int = when (this) {
    CalendarMode.MONTH -> R.string.calendar_mode_month
    CalendarMode.WEEK -> R.string.calendar_mode_week
    CalendarMode.DAY -> R.string.calendar_mode_day
}
