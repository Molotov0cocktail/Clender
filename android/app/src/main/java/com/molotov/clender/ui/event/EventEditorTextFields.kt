package com.molotov.clender.ui.event

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.molotov.clender.R

@Composable
internal fun TitleField(
    form: EventFormState,
    enabled: Boolean,
    onFormChange: (EventFormState) -> Unit
) {
    val invalid = EventFormError.BLANK_TITLE in form.validationErrors
    OutlinedTextField(
        value = form.title,
        onValueChange = { onFormChange(form.copy(title = it)) },
        modifier = Modifier.fillMaxWidth().testTag("event_editor_title"),
        enabled = enabled,
        label = { Text(stringResource(R.string.event_field_title)) },
        isError = invalid,
        supportingText = {
            if (invalid) {
                Text(
                    stringResource(R.string.event_title_blank),
                    Modifier.testTag("event_editor_title_error")
                )
            }
        },
        singleLine = true
    )
}

@Composable
internal fun DescriptionField(
    form: EventFormState,
    enabled: Boolean,
    onFormChange: (EventFormState) -> Unit
) {
    OutlinedTextField(
        value = form.description,
        onValueChange = { onFormChange(form.copy(description = it)) },
        modifier = Modifier.fillMaxWidth().testTag("event_editor_description"),
        enabled = enabled,
        label = { Text(stringResource(R.string.event_field_description)) },
        minLines = 3
    )
}

@Composable
internal fun DurationField(
    form: EventFormState,
    enabled: Boolean,
    onFormChange: (EventFormState) -> Unit
) {
    val error = durationError(form.validationErrors)
    OutlinedTextField(
        value = form.estimatedDurationInput,
        onValueChange = { onFormChange(form.copy(estimatedDurationInput = it)) },
        modifier = Modifier.fillMaxWidth().testTag("event_editor_duration"),
        enabled = enabled,
        label = { Text(stringResource(R.string.event_field_duration)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = error != null,
        supportingText = {
            error?.let {
                Text(
                    stringResource(durationErrorResource(it)),
                    Modifier.testTag("event_editor_duration_error")
                )
            }
        },
        singleLine = true
    )
}
