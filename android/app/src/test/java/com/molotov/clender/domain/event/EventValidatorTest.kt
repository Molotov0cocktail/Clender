package com.molotov.clender.domain.event

import com.molotov.clender.core.model.EventType
import com.molotov.clender.core.model.eventFixture
import java.time.Instant
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EventValidatorTest {
    @Test
    fun acceptsTrimmedUnicodeLongTextAndUnboundedNonNegativeDomainDuration() {
        val longTitle = " 会議🌏" + "长".repeat(8_192) + " "

        listOf(0, 480, 481, Int.MAX_VALUE).forEach { duration ->
            val validated = EventValidator.validatePersisted(
                eventFixture(title = longTitle, estimatedDurationMinutes = duration)
            )
            assertEquals(longTitle.trim(), validated.title)
            assertEquals(duration, validated.estimatedDurationMinutes)
        }
    }

    @Test
    fun rejectsBlankTitleNegativeDurationAndInvalidIds() {
        listOf("", " ", "\t\n").forEach { title ->
            assertThrows(EventValidationException::class.java) {
                EventValidator.validatePersisted(eventFixture(title = title))
            }
        }
        assertThrows(EventValidationException::class.java) {
            EventValidator.validatePersisted(eventFixture(estimatedDurationMinutes = -1))
        }
        listOf(0L, -1L, Long.MIN_VALUE).forEach { id ->
            assertThrows(EventValidationException::class.java) {
                EventValidator.validatePersisted(eventFixture(id = id))
            }
        }
    }

    @Test
    fun enforcesReminderAndTimespanEndContractsIncludingCrossDay() {
        val start = LocalDateTime.of(2024, 2, 29, 23, 59)
        val crossDay = eventFixture(
            eventType = EventType.TIMESPAN,
            startTime = start,
            endTime = start.plusMinutes(2)
        )
        assertEquals(start.plusMinutes(2), EventValidator.validatePersisted(crossDay).endTime)

        assertThrows(EventValidationException::class.java) {
            EventValidator.validatePersisted(crossDay.copy(endTime = null))
        }
        assertThrows(EventValidationException::class.java) {
            EventValidator.validatePersisted(crossDay.copy(endTime = start))
        }
        assertThrows(EventValidationException::class.java) {
            EventValidator.validatePersisted(crossDay.copy(endTime = start.minusMinutes(1)))
        }
        assertThrows(EventValidationException::class.java) {
            EventValidator.validatePersisted(
                eventFixture(eventType = EventType.REMINDER, endTime = start.plusMinutes(1))
            )
        }
    }

    @Test
    fun enforcesLowercaseHexUuidAndMetadataOrdering() {
        listOf(
            "",
            "0123456789abcdef",
            "0123456789ABCDEF0123456789ABCDEF",
            "g123456789abcdef0123456789abcdef",
            "0123456789abcdef0123456789abcdef00"
        ).forEach { uid ->
            assertThrows(EventValidationException::class.java) {
                EventValidator.validatePersisted(eventFixture(syncUid = uid))
            }
        }

        val created = Instant.parse("2026-08-07T00:00:01Z")
        val earlier = created.minusSeconds(1)
        assertThrows(EventValidationException::class.java) {
            EventValidator.validatePersisted(eventFixture(createdAt = created, updatedAt = earlier))
        }
        assertThrows(EventValidationException::class.java) {
            EventValidator.validatePersisted(
                eventFixture(createdAt = earlier, updatedAt = earlier, deletedAt = created)
            )
        }

        val updated = created.plusSeconds(2)
        val desktopCompatibleTombstone = eventFixture(
            createdAt = earlier,
            updatedAt = updated,
            deletedAt = created
        )
        assertEquals(
            created,
            EventValidator.validatePersisted(desktopCompatibleTombstone).deletedAt
        )
    }

    @Test
    fun rejectsNonMinuteWallClockAndNonMicrosecondMetadataPrecision() {
        val base = eventFixture()
        listOf(
            base.copy(startTime = base.startTime.plusSeconds(1)),
            base.copy(startTime = base.startTime.plusNanos(1))
        ).forEach { event ->
            assertThrows(EventValidationException::class.java) {
                EventValidator.validatePersisted(event)
            }
        }
        val timespan = base.copy(
            eventType = EventType.TIMESPAN,
            endTime = base.startTime.plusHours(1).plusSeconds(1)
        )
        assertThrows(EventValidationException::class.java) {
            EventValidator.validatePersisted(timespan)
        }
        assertThrows(EventValidationException::class.java) {
            EventValidator.validatePersisted(base.copy(updatedAt = base.updatedAt.plusNanos(1)))
        }
        val maximumMicrosecond = Instant.MAX.truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        assertThrows(EventValidationException::class.java) {
            EventValidator.validatePersisted(
                base.copy(createdAt = maximumMicrosecond, updatedAt = maximumMicrosecond)
            )
        }
    }
}
