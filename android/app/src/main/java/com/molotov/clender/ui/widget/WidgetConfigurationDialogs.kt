package com.molotov.clender.ui.widget

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import java.time.LocalTime
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetTimePickerDialog(
    selectedTime: LocalTime,
    onTimeSelected: (LocalTime) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val pickerState = rememberTimePickerState(
        initialHour = selectedTime.hour,
        initialMinute = selectedTime.minute,
        is24Hour = DateFormat.is24HourFormat(context)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onTimeSelected(LocalTime.of(pickerState.hour, pickerState.minute))
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("widget_config_time_picker_confirm")
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("widget_config_time_picker_cancel")
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        title = { Text(stringResource(R.string.widget_config_time_picker_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TimePicker(state = pickerState)
            }
        },
        modifier = Modifier.testTag("widget_config_time_picker_dialog")
    )
}

@Composable
fun WidgetDiscardConfirmationDialog(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        confirmButton = {
            TextButton(
                onClick = onDiscard,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("widget_config_discard_confirm")
            ) {
                Text(stringResource(R.string.widget_config_discard_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onKeepEditing,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("widget_config_discard_keep_editing")
            ) {
                Text(stringResource(R.string.widget_config_keep_editing))
            }
        },
        title = { Text(stringResource(R.string.widget_config_discard_title)) },
        text = { Text(stringResource(R.string.widget_config_discard_message)) },
        modifier = Modifier.testTag("widget_config_discard_dialog")
    )
}

@Composable
internal fun formatWidgetTime(time: LocalTime): String {
    val context = LocalContext.current
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, time.hour)
        set(Calendar.MINUTE, time.minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return DateFormat.getTimeFormat(context).format(calendar.time)
}
