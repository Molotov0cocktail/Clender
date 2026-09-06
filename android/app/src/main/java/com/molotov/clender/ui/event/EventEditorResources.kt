package com.molotov.clender.ui.event

import com.molotov.clender.R
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun durationError(errors: Set<EventFormError>): EventFormError? = errors.firstOrNull {
    it == EventFormError.EMPTY_ESTIMATED_DURATION ||
        it == EventFormError.NON_NUMERIC_ESTIMATED_DURATION ||
        it == EventFormError.NEGATIVE_ESTIMATED_DURATION ||
        it == EventFormError.OUT_OF_RANGE_ESTIMATED_DURATION
}

internal fun durationErrorResource(error: EventFormError): Int = when (error) {
    EventFormError.EMPTY_ESTIMATED_DURATION -> R.string.event_duration_empty
    EventFormError.NON_NUMERIC_ESTIMATED_DURATION -> R.string.event_duration_non_numeric
    EventFormError.NEGATIVE_ESTIMATED_DURATION -> R.string.event_duration_negative
    EventFormError.OUT_OF_RANGE_ESTIMATED_DURATION -> R.string.event_duration_out_of_range
    else -> error("Not a duration error")
}

internal fun endErrorResource(errors: Set<EventFormError>): Int? = when {
    EventFormError.MISSING_END_TIME in errors -> R.string.event_end_missing
    EventFormError.END_NOT_AFTER_START in errors -> R.string.event_end_not_after_start
    EventFormError.REMINDER_END_TIME_PRESENT in errors -> R.string.event_end_invalid_for_reminder
    else -> null
}

internal fun saveErrorResource(error: EventSaveErrorCode): Int = when (error) {
    EventSaveErrorCode.VALIDATION_FAILED -> R.string.event_save_validation_failed
    EventSaveErrorCode.NOT_FOUND -> R.string.event_save_not_found
    EventSaveErrorCode.SAVE_FAILED -> R.string.event_save_failed
}

internal fun formatDate(value: LocalDate, locale: Locale): String = value.format(
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
)

internal fun formatTime(value: LocalTime, locale: Locale): String = value.format(
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
)
