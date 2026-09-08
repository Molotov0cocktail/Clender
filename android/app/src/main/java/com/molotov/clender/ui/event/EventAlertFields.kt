package com.molotov.clender.ui.event

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.molotov.clender.R

@Composable
internal fun EventAlertFields(
    form: EventFormState,
    enabled: Boolean,
    onChange: (EventFormState) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.alert_section_title),
            style = MaterialTheme.typography.titleMedium
        )
        AlertSwitch(
            stringResource(R.string.alert_notification),
            "event_alert_notification",
            form.notificationEnabled,
            enabled
        ) { onChange(form.copy(notificationEnabled = it)) }
        AlertSwitch(
            stringResource(R.string.alert_alarm),
            "event_alert_alarm",
            form.alarmEnabled,
            enabled
        ) { onChange(form.copy(alarmEnabled = it)) }
        Text(
            stringResource(R.string.alert_alarm_explanation),
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = form.timerMinutesInput,
            onValueChange = { onChange(form.copy(timerMinutesInput = it)) },
            modifier = Modifier.fillMaxWidth().testTag("event_alert_timer"),
            label = { Text(stringResource(R.string.alert_timer)) },
            supportingText = { Text(stringResource(R.string.alert_timer_explanation)) },
            isError = EventFormError.INVALID_TIMER in form.validationErrors,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )
    }
}

@Composable
private fun AlertSwitch(
    label: String,
    tag: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            modifier = Modifier.testTag(tag).sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics { contentDescription = label }
        )
    }
}
