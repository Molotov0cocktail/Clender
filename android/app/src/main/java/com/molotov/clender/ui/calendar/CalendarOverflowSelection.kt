package com.molotov.clender.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.core.model.Event
import com.molotov.clender.ui.event.EventListItemUi
import com.molotov.clender.ui.event.EventListLabels
import com.molotov.clender.ui.event.EventListNavigation
import com.molotov.clender.ui.event.EventListPresenter
import com.molotov.clender.ui.navigation.AppRoute
import java.util.Locale

sealed interface CalendarOverflowState {
    data object Closed : CalendarOverflowState

    data class Open(val events: List<EventListItemUi>) : CalendarOverflowState {
        init {
            require(events.isNotEmpty()) { "An open overflow sheet requires events" }
        }
    }
}

class CalendarOverflowController(
    private val onNavigate: (AppRoute.EventDetail) -> Unit,
    private val labels: EventListLabels,
    private val locale: Locale = Locale.getDefault()
) {
    var state: CalendarOverflowState by mutableStateOf(CalendarOverflowState.Closed)
        private set

    fun open(eventIds: List<Long>, availableEvents: List<Event>): Boolean {
        val next = buildOpenState(eventIds, availableEvents)
        state = next ?: CalendarOverflowState.Closed
        return next != null
    }

    fun close() {
        state = CalendarOverflowState.Closed
    }

    fun select(eventId: Long): Boolean {
        val open = state as? CalendarOverflowState.Open
        val route = EventListNavigation.detailRoute(eventId)
        val selected = route != null && open?.events?.any { it.id == eventId } == true
        if (selected) {
            state = CalendarOverflowState.Closed
            onNavigate(requireNotNull(route))
        }
        return selected
    }

    private fun buildOpenState(
        eventIds: List<Long>,
        availableEvents: List<Event>
    ): CalendarOverflowState.Open? = runCatching {
        require(eventIds.isNotEmpty() && eventIds.toSet().size == eventIds.size)
        require(eventIds.all { EventListNavigation.detailRoute(it) != null })
        val grouped = availableEvents.groupBy(Event::id)
        val selected = eventIds.map { id -> requireNotNull(grouped[id]?.singleOrNull()) }
        val items = EventListPresenter.present(selected, locale, labels).items
        require(items.size == eventIds.size)
        val byId = items.associateBy(EventListItemUi::id)
        CalendarOverflowState.Open(eventIds.map { requireNotNull(byId[it]) })
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarOverflowSheet(
    state: CalendarOverflowState.Open,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag("calendar_overflow_sheet")
    ) {
        Text(
            text = stringResource(R.string.event_overflow_title),
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        LazyColumn {
            items(items = state.events, key = EventListItemUi::id) { item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(item.id) }
                        .padding(16.dp)
                        .testTag("calendar_overflow_item_${item.id}")
                ) {
                    Text(item.title)
                    Text(item.timeSummary)
                }
            }
        }
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.testTag("calendar_overflow_close")
        ) {
            Text(stringResource(R.string.event_overflow_close))
        }
    }
}
