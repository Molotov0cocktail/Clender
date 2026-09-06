package com.molotov.clender.ui.event

import androidx.compose.runtime.Composable

internal enum class EditorPickerTarget {
    START_DATE,
    START_TIME,
    END_DATE,
    END_TIME
}

@Composable
internal fun EditorPicker(
    target: EditorPickerTarget?,
    form: EventFormState,
    onFormChange: (EventFormState) -> Unit,
    onDismiss: () -> Unit
) {
    when (target) {
        EditorPickerTarget.START_DATE -> EventDatePickerDialog(
            selectedDate = form.startTime.toLocalDate(),
            onDateSelected = { date ->
                onFormChange(
                    form.withStartTime(EventDateTimePickerCodec.replaceDate(form.startTime, date))
                )
                onDismiss()
            },
            onDismiss = onDismiss
        )

        EditorPickerTarget.START_TIME -> EventTimePickerDialog(
            selectedTime = form.startTime.toLocalTime(),
            onTimeSelected = { time ->
                onFormChange(
                    form.withStartTime(EventDateTimePickerCodec.replaceTime(form.startTime, time))
                )
                onDismiss()
            },
            onDismiss = onDismiss
        )

        EditorPickerTarget.END_DATE -> EventDatePickerDialog(
            selectedDate = form.endTime?.toLocalDate() ?: form.startTime.toLocalDate(),
            onDateSelected = { date ->
                val base = form.endTime ?: form.startTime
                onFormChange(form.withEndTime(EventDateTimePickerCodec.replaceDate(base, date)))
                onDismiss()
            },
            onDismiss = onDismiss
        )

        EditorPickerTarget.END_TIME -> EventTimePickerDialog(
            selectedTime = form.endTime?.toLocalTime() ?: form.startTime.toLocalTime(),
            onTimeSelected = { time ->
                val base = form.endTime ?: form.startTime
                onFormChange(form.withEndTime(EventDateTimePickerCodec.replaceTime(base, time)))
                onDismiss()
            },
            onDismiss = onDismiss
        )

        null -> Unit
    }
}
