package com.molotov.clender.core.model

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private const val MIN_SUPPORTED_YEAR = 1
private const val MAX_SUPPORTED_YEAR = 9999
private const val MICROSECOND_FRACTION_DIGITS = 6

object WallClockCodec {
    private val shape = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}")
    private val formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(value: String): LocalDateTime {
        require(shape.matches(value)) { "Wall-clock timestamp has an invalid shape" }
        val parsed = try {
            LocalDateTime.parse(value, formatter)
        } catch (error: DateTimeException) {
            throw IllegalArgumentException("Wall-clock timestamp is invalid", error)
        }
        require(parsed.year in MIN_SUPPORTED_YEAR..MAX_SUPPORTED_YEAR) {
            "Wall-clock timestamp must use a positive four-digit year"
        }
        return parsed
    }

    fun format(value: LocalDateTime): String {
        require(value.second == 0 && value.nano == 0) {
            "Wall-clock timestamp must have minute precision"
        }
        require(value.year in MIN_SUPPORTED_YEAR..MAX_SUPPORTED_YEAR) {
            "Wall-clock timestamp must use a positive four-digit year"
        }
        val encoded = formatter.format(value)
        require(shape.matches(encoded)) {
            "Wall-clock timestamp is outside the strict four-digit-year contract"
        }
        return encoded
    }
}

object UtcInstantCodec {
    private val shape = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z")
    private val formatter = DateTimeFormatterBuilder()
        .appendInstant(MICROSECOND_FRACTION_DIGITS)
        .toFormatter(Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(value: String): Instant {
        require(shape.matches(value)) { "UTC timestamp has an invalid shape" }
        val parsed = try {
            Instant.from(formatter.parse(value))
        } catch (error: DateTimeException) {
            throw IllegalArgumentException("UTC timestamp is invalid", error)
        }
        require(
            parsed.atOffset(java.time.ZoneOffset.UTC).year in MIN_SUPPORTED_YEAR..MAX_SUPPORTED_YEAR
        ) {
            "UTC timestamp must use a positive four-digit year"
        }
        return parsed
    }

    fun format(value: Instant): String {
        val truncated = value.truncatedTo(ChronoUnit.MICROS)
        val year = try {
            truncated.atOffset(java.time.ZoneOffset.UTC).year
        } catch (error: DateTimeException) {
            throw IllegalArgumentException("UTC timestamp is outside the supported range", error)
        }
        require(year in MIN_SUPPORTED_YEAR..MAX_SUPPORTED_YEAR) {
            "UTC timestamp must use a positive four-digit year"
        }
        val encoded = formatter.format(truncated)
        require(shape.matches(encoded)) {
            "UTC timestamp is outside the strict four-digit-year contract"
        }
        return encoded
    }
}
