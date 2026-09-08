package com.molotov.clender.domain.event

import com.molotov.clender.core.model.eventFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EventAlertValidationTest {
    @Test
    fun domainAcceptsBothBooleanChoicesAndTimerBoundaries() {
        listOf(false, true).forEach { notification ->
            listOf(false, true).forEach { alarm ->
                listOf(0, 1, MAX_TIMER_MINUTES).forEach { timer ->
                    val candidate = eventFixture().copy(
                        notificationEnabled = notification,
                        alarmEnabled = alarm,
                        timerMinutes = timer
                    )
                    assertEquals(candidate, EventValidator.validatePersisted(candidate))
                }
            }
        }
    }

    @Test
    fun domainRejectsInvalidTimerEvenWhenOtherAlertsAreOff() {
        listOf(-1, MAX_TIMER_MINUTES + 1, Int.MAX_VALUE, Int.MIN_VALUE).forEach { timer ->
            assertThrows(EventValidationException::class.java) {
                EventValidator.validatePersisted(eventFixture().copy(timerMinutes = timer))
            }
        }
    }
}
