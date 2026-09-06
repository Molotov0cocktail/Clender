package com.molotov.clender.ui.event

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDatePickerDialog(
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = EventDateTimePickerCodec.toUtcEpochMillis(selectedDate),
        yearRange = MIN_SUPPORTED_YEAR..MAX_SUPPORTED_YEAR
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val date = pickerState.selectedDateMillis
                        ?.let(EventDateTimePickerCodec::fromUtcEpochMillis)
                        ?: selectedDate
                    onDateSelected(date)
                },
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("event_date_picker_confirm")
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("event_date_picker_cancel")
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        title = {
            Text(
                text = stringResource(R.string.event_date_picker_title),
                modifier = Modifier.testTag("event_date_picker_title")
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                DatePicker(state = pickerState)
            }
        },
        modifier = Modifier.testTag("event_date_picker_dialog")
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventTimePickerDialog(
    selectedTime: LocalTime,
    onTimeSelected: (LocalTime) -> Unit,
    onDismiss: () -> Unit
) {
    val pickerState = rememberTimePickerState(
        initialHour = selectedTime.hour,
        initialMinute = selectedTime.minute,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onTimeSelected(LocalTime.of(pickerState.hour, pickerState.minute))
                },
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("event_time_picker_confirm")
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("event_time_picker_cancel")
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        title = {
            Text(
                text = stringResource(R.string.event_time_picker_title),
                modifier = Modifier.testTag("event_time_picker_title")
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                TimePicker(state = pickerState)
            }
        },
        modifier = Modifier.testTag("event_time_picker_dialog")
    )
}
