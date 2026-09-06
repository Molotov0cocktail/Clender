package com.molotov.clender.ui.event

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

internal const val MIN_SUPPORTED_YEAR = 1
internal const val MAX_SUPPORTED_YEAR = 9999

object EventDateTimePickerCodec {
    private const val MILLIS_PER_DAY = 86_400_000L

    fun toUtcEpochMillis(date: LocalDate): Long {
        require(date.year in MIN_SUPPORTED_YEAR..MAX_SUPPORTED_YEAR) {
            "Date year is outside the supported range"
        }
        return date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    fun fromUtcEpochMillis(value: Long): LocalDate? {
        if (Math.floorMod(value, MILLIS_PER_DAY) != 0L) return null
        return try {
            Instant.ofEpochMilli(value)
                .atOffset(ZoneOffset.UTC)
                .toLocalDate()
                .takeIf { it.year in MIN_SUPPORTED_YEAR..MAX_SUPPORTED_YEAR }
        } catch (_: DateTimeException) {
            null
        }
    }

    fun replaceDate(value: LocalDateTime, date: LocalDate): LocalDateTime = LocalDateTime.of(
        date,
        LocalTime.of(value.hour, value.minute)
    )

    fun replaceTime(value: LocalDateTime, time: LocalTime): LocalDateTime = LocalDateTime.of(
        value.toLocalDate(),
        LocalTime.of(time.hour, time.minute)
    )
}
