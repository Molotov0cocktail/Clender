package com.molotov.clender.ui.event

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    val visibleHeight = rememberVisibleDialogHeight()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // Native Dialog positioning already fits system bars. Bound Compose's measurement
        // to the host's actual visible frame, including keyboard and configuration changes.
        Box(Modifier.heightIn(max = visibleHeight)) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .testTag("event_date_picker_dialog"),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp
            ) {
                Column {
                    // Material's seven 48dp day targets plus padding need 360dp.
                    // Narrow windows scroll the full calendar without clipping the last column.
                    Box(
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                    ) {
                        DatePicker(
                            state = pickerState,
                            modifier = Modifier.width(360.dp),
                            title = {
                                Text(
                                    text = stringResource(R.string.event_date_picker_title),
                                    modifier = Modifier
                                        .padding(start = 24.dp, top = 16.dp, end = 24.dp)
                                        .testTag("event_date_picker_title")
                                )
                            }
                        )
                    }
                    DatePickerActions(
                        onDismiss = onDismiss,
                        onConfirm = {
                            val date = pickerState.selectedDateMillis
                                ?.let(EventDateTimePickerCodec::fromUtcEpochMillis)
                                ?: selectedDate
                            onDateSelected(date)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberVisibleDialogHeight(): Dp {
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    var visibleHeight by remember(view) { mutableIntStateOf(readVisibleHeight(view)) }
    DisposableEffect(view, configuration) {
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val height = readVisibleHeight(view)
            if (height > 0) visibleHeight = height
        }
        observer.addOnGlobalLayoutListener(listener)
        listener.onGlobalLayout()
        onDispose {
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
        }
    }
    return if (visibleHeight > 0) {
        with(LocalDensity.current) { visibleHeight.toDp() }
    } else {
        Dp.Infinity
    }
}

private fun readVisibleHeight(view: View): Int {
    val frame = Rect()
    view.getWindowVisibleDisplayFrame(frame)
    val root = view.rootView
    if (frame.isEmpty || root.width <= 0 || root.height <= 0) return 0
    val location = IntArray(2)
    root.getLocationOnScreen(location)
    val rootBounds = Rect(
        location[0],
        location[1],
        location[0] + root.width,
        location[1] + root.height
    )
    return if (frame.intersect(rootBounds)) frame.height() else 0
}

@Composable
private fun DatePickerActions(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(
            onClick = onDismiss,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("event_date_picker_cancel")
        ) {
            Text(stringResource(R.string.action_cancel))
        }
        TextButton(
            onClick = onConfirm,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("event_date_picker_confirm")
        ) {
            Text(stringResource(R.string.action_confirm))
        }
    }
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
