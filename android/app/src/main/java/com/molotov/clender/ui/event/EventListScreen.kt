package com.molotov.clender.ui.event

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.domain.event.EventValidationException
import com.molotov.clender.domain.event.EventValidator
import com.molotov.clender.ui.navigation.AppRoute
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

enum class EventListErrorCode {
    LOAD_FAILED
}

data class EventListItemUi(val id: Long, val title: String, val timeSummary: String)

data class EventListLabels(val reminder: String, val timespan: String) {
    init {
        require(reminder.isNotBlank() && timespan.isNotBlank()) {
            "Event list labels must not be blank"
        }
    }
}

sealed interface EventListUiState {
    data object Loading : EventListUiState

    data object Empty : EventListUiState

    data class Error(val code: EventListErrorCode) : EventListUiState

    data class Content(val items: List<EventListItemUi>) : EventListUiState {
        init {
            require(items.isNotEmpty()) { "Content requires at least one event" }
        }
    }
}

object EventListPresenter {
    fun present(
        events: List<Event>,
        locale: Locale,
        labels: EventListLabels
    ): EventListUiState.Content {
        val presented = events.mapNotNull { event ->
            validatedVisibleEvent(event)?.let { visible ->
                PresentedEvent(
                    event = visible,
                    item = EventListItemUi(
                        id = visible.id,
                        title = visible.title,
                        timeSummary = formatSummary(visible, locale, labels)
                    )
                )
            }
        }.sortedWith(
            compareBy<PresentedEvent> { it.event.startTime }
                .thenComparator { first, second ->
                    compareNullableEnd(first.event.endTime, second.event.endTime)
                }
                .thenBy { it.item.id }
        )
        return EventListUiState.Content(presented.map(PresentedEvent::item))
    }

    private fun validatedVisibleEvent(event: Event): Event? = try {
        EventValidator.validatePersisted(event).takeIf { it.deletedAt == null }
    } catch (_: EventValidationException) {
        null
    }

    private fun formatSummary(event: Event, locale: Locale, labels: EventListLabels): String {
        val type = when (event.eventType) {
            EventType.REMINDER -> labels.reminder
            EventType.TIMESPAN -> labels.timespan
        }
        val start = formatDateTime(event.startTime, locale)
        return when (event.eventType) {
            EventType.REMINDER -> "$type · $start"

            EventType.TIMESPAN -> {
                val end = formatDateTime(requireNotNull(event.endTime), locale)
                "$type · $start – $end"
            }
        }
    }

    private fun formatDateTime(value: LocalDateTime, locale: Locale): String = value.format(
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
    )

    private fun compareNullableEnd(first: LocalDateTime?, second: LocalDateTime?): Int = when {
        first == null && second == null -> 0
        first == null -> -1
        second == null -> 1
        else -> first.compareTo(second)
    }

    private data class PresentedEvent(val event: Event, val item: EventListItemUi)
}

@Composable
fun eventListLabels(): EventListLabels = EventListLabels(
    reminder = stringResource(R.string.event_type_reminder),
    timespan = stringResource(R.string.event_type_timespan)
)

object EventListNavigation {
    fun detailRoute(id: Long): AppRoute.EventDetail? = id
        .takeIf { it in 1..Int.MAX_VALUE.toLong() }
        ?.toInt()
        ?.let(AppRoute::EventDetail)
}

@Composable
fun EventListScreen(
    state: EventListUiState,
    onRetry: () -> Unit,
    onNavigate: (AppRoute.EventDetail) -> Unit,
    modifier: Modifier = Modifier
) {
    when (state) {
        EventListUiState.Loading -> CenteredStatus(
            modifier = modifier
                .testTag("event_list_loading")
                .semantics { liveRegion = LiveRegionMode.Polite }
        ) {
            CircularProgressIndicator()
            Text(stringResource(R.string.event_list_loading))
        }

        EventListUiState.Empty -> CenteredStatus(
            modifier = modifier
                .testTag("event_list_empty")
                .semantics { liveRegion = LiveRegionMode.Polite }
        ) {
            Text(stringResource(R.string.event_list_empty))
        }

        is EventListUiState.Error -> CenteredStatus(
            modifier = modifier
                .testTag("event_list_error")
                .semantics { liveRegion = LiveRegionMode.Polite }
        ) {
            Text(stringResource(R.string.event_list_error))
            Button(onClick = onRetry, modifier = Modifier.testTag("event_list_retry")) {
                Text(stringResource(R.string.action_retry))
            }
        }

        is EventListUiState.Content -> EventListContent(
            items = state.items,
            onNavigate = onNavigate,
            modifier = modifier
        )
    }
}

@Composable
private fun EventListContent(
    items: List<EventListItemUi>,
    onNavigate: (AppRoute.EventDetail) -> Unit,
    modifier: Modifier
) {
    val listState = rememberSaveable(
        saver = androidx.compose.foundation.lazy.LazyListState.Saver
    ) { androidx.compose.foundation.lazy.LazyListState() }
    LazyColumn(modifier = modifier.fillMaxSize(), state = listState) {
        items(items = items, key = EventListItemUi::id) { item ->
            val route = EventListNavigation.detailRoute(item.id)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (route == null) {
                            Modifier
                        } else {
                            Modifier.clickable(
                                role = Role.Button,
                                onClick = { onNavigate(route) }
                            )
                        }
                    )
                    .sizeIn(minHeight = 48.dp)
                    .padding(16.dp)
                    .testTag("event_list_item_${item.id}")
            ) {
                Text(item.title)
                Text(
                    item.timeSummary,
                    modifier = Modifier.testTag("event_list_summary_${item.id}")
                )
            }
        }
    }
}

@Composable
private fun CenteredStatus(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        content()
    }
}
