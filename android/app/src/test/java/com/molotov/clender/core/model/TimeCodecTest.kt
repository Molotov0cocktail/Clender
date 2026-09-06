package com.molotov.clender.core.model

import java.time.Instant
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TimeCodecTest {
    @Test
    fun wallClockCodecAcceptsStrictBoundariesLeapDayAndRoundTrips() {
        val values = listOf(
            "2024-02-29 00:00",
            "2026-08-07 23:59"
        )

        values.forEach { text ->
            assertEquals(text, WallClockCodec.format(WallClockCodec.parse(text)))
        }
        assertEquals(
            LocalDateTime.of(2024, 2, 29, 0, 0),
            WallClockCodec.parse("2024-02-29 00:00")
        )
    }

    @Test
    fun wallClockCodecRejectsLenientOrNonMinuteInput() {
        listOf(
            "2023-02-29 09:00",
            "2024-2-29 09:00",
            "2024-02-29T09:00",
            "2024-02-29 9:00",
            "2024-02-29 09:00:00",
            " 2024-02-29 09:00",
            "0000-01-01 00:00"
        ).forEach { text ->
            assertThrows(IllegalArgumentException::class.java) {
                WallClockCodec.parse(text)
            }
        }
    }

    @Test
    fun utcCodecUsesExactlySixFractionDigitsAndUtcZ() {
        val instant = Instant.parse("2026-08-07T01:02:03.123456Z")
        assertEquals("2026-08-07T01:02:03.123456Z", UtcInstantCodec.format(instant))
        assertEquals(instant, UtcInstantCodec.parse("2026-08-07T01:02:03.123456Z"))

        listOf(
            "2026-08-07T01:02:03Z",
            "2026-08-07T01:02:03.123Z",
            "2026-08-07T01:02:03.123456789Z",
            "2026-08-07T01:02:03.123456+00:00",
            "2026-08-07T01:02:03.123456z",
            "0000-01-01T00:00:00.000000Z"
        ).forEach { text ->
            assertThrows(IllegalArgumentException::class.java) {
                UtcInstantCodec.parse(text)
            }
        }
    }

    @Test
    fun utcFormatterTruncatesNanosecondsToDesktopMicrosecondContract() {
        assertEquals(
            "2026-08-07T01:02:03.123456Z",
            UtcInstantCodec.format(Instant.parse("2026-08-07T01:02:03.123456999Z"))
        )
    }

    @Test
    fun formattersRejectYearsTheirStrictParsersCannotRoundTrip() {
        listOf(0, 10_000).forEach { year ->
            assertThrows(IllegalArgumentException::class.java) {
                WallClockCodec.format(LocalDateTime.of(year, 1, 1, 0, 0))
            }
        }
        listOf(
            Instant.parse("0000-01-01T00:00:00Z"),
            Instant.parse("+10000-01-01T00:00:00Z")
        ).forEach { instant ->
            assertThrows(IllegalArgumentException::class.java) {
                UtcInstantCodec.format(instant)
            }
        }
    }
}
