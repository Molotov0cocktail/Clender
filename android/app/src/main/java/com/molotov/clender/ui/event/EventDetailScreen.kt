package com.molotov.clender.ui.event

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun EventDetailScreen(
    state: EventCrudUiState,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (state.loadStatus) {
        EventCrudLoadStatus.LOADING -> DetailStatus(
            text = stringResource(R.string.event_detail_loading),
            heading = stringResource(R.string.screen_event_detail),
            tag = "event_detail_loading",
            modifier = modifier,
            progress = true
        )

        EventCrudLoadStatus.NOT_FOUND -> DetailStatus(
            text = stringResource(R.string.event_detail_not_found),
            heading = stringResource(R.string.screen_event_detail),
            tag = "event_detail_not_found",
            modifier = modifier
        )

        EventCrudLoadStatus.ERROR -> DetailError(onRetry = onRetry, modifier = modifier)

        EventCrudLoadStatus.CONTENT -> state.event?.let { event ->
            EventDetailContent(
                event = event,
                deleting = state.operation == EventCrudOperation.DELETING,
                onEdit = onEdit,
                onRequestDelete = onRequestDelete,
                modifier = modifier
            )
        } ?: DetailStatus(
            text = stringResource(R.string.event_detail_not_found),
            heading = stringResource(R.string.screen_event_detail),
            tag = "event_detail_not_found",
            modifier = modifier
        )

        EventCrudLoadStatus.IDLE -> Unit
    }
}

@Composable
private fun DetailError(onRetry: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("event_detail_error")
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.event_detail_load_failed))
        Button(
            onClick = onRetry,
            modifier = Modifier
                .padding(top = 16.dp)
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("event_detail_retry")
        ) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

@Composable
private fun DetailStatus(
    text: String,
    tag: String,
    modifier: Modifier,
    progress: Boolean = false,
    heading: String? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(tag)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (progress) CircularProgressIndicator()
        heading?.let { Text(text = it, style = MaterialTheme.typography.titleLarge) }
        Text(text = text, modifier = Modifier.padding(top = if (progress) 12.dp else 0.dp))
    }
}

@Composable
private fun EventDetailContent(
    event: Event,
    deleting: Boolean,
    onEdit: () -> Unit,
    onRequestDelete: () -> Unit,
    modifier: Modifier
) {
    val locale = LocalConfiguration.current.locales[0]
    val rows = detailRows(event, locale)
    val listState = rememberSaveable(
        saver = androidx.compose.foundation.lazy.LazyListState.Saver
    ) { androidx.compose.foundation.lazy.LazyListState() }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("event_detail_content"),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        item {
            Text(
                text = event.title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .testTag("event_detail_title")
                    .semantics { heading() }
            )
        }
        items(rows) { row ->
            DetailValue(label = row.label, value = row.value, tag = row.tag)
        }
        if (deleting) {
            item {
                Text(
                    text = stringResource(R.string.event_deleting),
                    modifier = Modifier.testTag("event_detail_deleting")
                )
            }
        }
        item {
            DetailActions(deleting, onEdit, onRequestDelete)
        }
        item { EventAlertDetails(event) }
        item { AlertPermissionSection() }
    }
}

@Composable
private fun detailRows(event: Event, locale: Locale): List<DetailRow> = listOfNotNull(
    DetailRow(
        stringResource(R.string.event_field_type),
        stringResource(
            if (event.eventType == EventType.REMINDER) {
                R.string.event_type_reminder
            } else {
                R.string.event_type_timespan
            }
        ),
        "event_detail_type"
    ),
    DetailRow(
        stringResource(R.string.event_field_start),
        formatDateTime(event.startTime, locale),
        "event_detail_start"
    ),
    event.endTime?.let {
        DetailRow(
            stringResource(R.string.event_field_end),
            formatDateTime(it, locale),
            "event_detail_end"
        )
    },
    DetailRow(
        stringResource(R.string.event_field_description),
        event.description,
        "event_detail_description"
    ),
    DetailRow(
        stringResource(R.string.event_field_duration),
        stringResource(R.string.event_detail_duration_value, event.estimatedDurationMinutes),
        "event_detail_duration"
    )
)

@Composable
private fun DetailActions(deleting: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onEdit,
            enabled = !deleting,
            modifier = Modifier
                .weight(1f)
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("event_detail_edit")
        ) {
            Text(stringResource(R.string.action_edit))
        }
        OutlinedButton(
            onClick = onDelete,
            enabled = !deleting,
            modifier = Modifier
                .weight(1f)
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("event_detail_delete")
        ) {
            Text(stringResource(R.string.action_delete))
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String, tag: String) {
    Column(modifier = Modifier.testTag(tag)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun formatDateTime(value: LocalDateTime, locale: Locale): String = value.format(
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
)

private data class DetailRow(val label: String, val value: String, val tag: String)
