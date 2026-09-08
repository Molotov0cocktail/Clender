package com.molotov.clender.ui.event

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.core.model.EventType
import java.time.LocalDateTime
import java.util.Locale

@Composable
internal fun EditorFormContent(
    state: EventCrudUiState,
    form: EventFormState,
    locale: Locale,
    actions: EditorFormActions,
    modifier: Modifier
) {
    val saving = state.operation == EventCrudOperation.SAVING
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("event_editor_scroll"),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        editorFieldItems(form, locale, saving, actions.onFormChange, actions.onPickerRequested)
        editorStatusItems(state, form, saving, actions.onSave)
    }
}

private fun LazyListScope.editorFieldItems(
    form: EventFormState,
    locale: Locale,
    saving: Boolean,
    onFormChange: (EventFormState) -> Unit,
    onPickerRequested: (EditorPickerTarget) -> Unit
) {
    item { EventTypeSelector(form, !saving, onFormChange) }
    item { TitleField(form, !saving, onFormChange) }
    item {
        val model = DateTimeControlModel(
            "event_editor_start",
            stringResource(R.string.event_field_start),
            form.startTime,
            locale,
            !saving
        )
        DateTimeControls(
            model,
            { onPickerRequested(EditorPickerTarget.START_DATE) },
            { onPickerRequested(EditorPickerTarget.START_TIME) }
        )
    }
    if (form.eventType == EventType.TIMESPAN) {
        item { EndDateTimeControls(form, locale, !saving, onPickerRequested) }
    }
    item { DescriptionField(form, !saving, onFormChange) }
    item { DurationField(form, !saving, onFormChange) }
    item { EventAlertFields(form, !saving, onFormChange) }
    item { AlertPermissionSection() }
}

private fun LazyListScope.editorStatusItems(
    state: EventCrudUiState,
    form: EventFormState,
    saving: Boolean,
    onSave: () -> Unit
) {
    state.saveError?.let { error ->
        item {
            Text(
                text = stringResource(saveErrorResource(error)),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("event_editor_error")
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
    if (saving) {
        item {
            Text(
                text = stringResource(R.string.event_saving),
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("event_editor_busy")
            )
        }
    }
    item {
        Button(
            onClick = onSave,
            enabled = form.canSubmit && !saving,
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("event_editor_save")
        ) {
            Text(stringResource(R.string.action_save))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventTypeSelector(
    form: EventFormState,
    enabled: Boolean,
    onFormChange: (EventFormState) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.event_field_type))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EventTypeChip(form, EventType.REMINDER, enabled, onFormChange)
            EventTypeChip(form, EventType.TIMESPAN, enabled, onFormChange)
        }
    }
}

@Composable
private fun EventTypeChip(
    form: EventFormState,
    type: EventType,
    enabled: Boolean,
    onFormChange: (EventFormState) -> Unit
) {
    val reminder = type == EventType.REMINDER
    FilterChip(
        selected = form.eventType == type,
        onClick = { onFormChange(form.changeType(type)) },
        label = {
            Text(
                stringResource(
                    if (reminder) R.string.event_type_reminder else R.string.event_type_timespan
                )
            )
        },
        enabled = enabled,
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .testTag(if (reminder) "event_editor_type_reminder" else "event_editor_type_timespan")
    )
}

@Composable
private fun DateTimeControls(
    model: DateTimeControlModel,
    onDateClick: () -> Unit,
    onTimeClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(model.label)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DateTimeButton(
                formatDate(model.value.toLocalDate(), model.locale),
                "${model.prefix}_date",
                model.enabled,
                onDateClick,
                Modifier.weight(1f)
            )
            DateTimeButton(
                formatTime(model.value.toLocalTime(), model.locale),
                "${model.prefix}_time",
                model.enabled,
                onTimeClick,
                Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun DateTimeButton(
    text: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag(tag)
    ) {
        Text(text)
    }
}

@Composable
private fun EndDateTimeControls(
    form: EventFormState,
    locale: Locale,
    enabled: Boolean,
    onPickerRequested: (EditorPickerTarget) -> Unit
) {
    Column(
        modifier = Modifier.testTag("event_editor_end_section"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.event_field_end))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DateTimeButton(
                form.endTime?.toLocalDate()?.let { formatDate(it, locale) }
                    ?: stringResource(R.string.event_select_date),
                "event_editor_end_date",
                enabled,
                { onPickerRequested(EditorPickerTarget.END_DATE) },
                Modifier.weight(1f)
            )
            DateTimeButton(
                form.endTime?.toLocalTime()?.let { formatTime(it, locale) }
                    ?: stringResource(R.string.event_select_time),
                "event_editor_end_time",
                enabled,
                { onPickerRequested(EditorPickerTarget.END_TIME) },
                Modifier.weight(1f)
            )
        }
        endErrorResource(form.validationErrors)?.let { resource ->
            Text(
                stringResource(resource),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("event_editor_end_error")
            )
        }
    }
}

internal data class DateTimeControlModel(
    val prefix: String,
    val label: String,
    val value: LocalDateTime,
    val locale: Locale,
    val enabled: Boolean
)

internal data class EditorFormActions(
    val onFormChange: (EventFormState) -> Unit,
    val onSave: () -> Unit,
    val onPickerRequested: (EditorPickerTarget) -> Unit
)
