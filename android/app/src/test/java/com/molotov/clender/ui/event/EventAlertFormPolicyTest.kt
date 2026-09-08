package com.molotov.clender.ui.event

import com.molotov.clender.core.model.EventType
import com.molotov.clender.core.model.eventFixture
import com.molotov.clender.domain.event.FieldUpdate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventAlertFormPolicyTest {
    private val initial = EventFormState.new(LocalDate.of(2026, 9, 9)).copy(title = "Reminder")

    @Test
    fun timerAcceptsZeroOneAndMaximumAndRejectsInvalidText() {
        listOf("0", "1", "1440").forEach { input ->
            val form = initial.copy(timerMinutesInput = input)
            assertTrue(form.canSubmit)
            assertEquals(input.toInt(), form.toAddCommand().timerMinutes)
        }
        listOf("", "-1", "1441", "1.5", "abc", "9999999999999999999999").forEach { input ->
            val form = initial.copy(timerMinutesInput = input)
            assertFalse(form.canSubmit)
            assertTrue(EventFormError.INVALID_TIMER in form.validationErrors)
        }
    }

    @Test
    fun editPreservesPolicyAndCanExplicitlyDisableEverything() {
        val event = eventFixture().copy(
            notificationEnabled = true,
            alarmEnabled = true,
            timerMinutes = 25
        )
        val form = EventFormState.fromEvent(event)
        assertTrue(form.toPatch(event).isEmpty())
        val patch = form.copy(
            notificationEnabled = false,
            alarmEnabled = false,
            timerMinutesInput = "0"
        )
            .toPatch(event)
        assertEquals(FieldUpdate.Set(false), patch.notificationEnabled)
        assertEquals(FieldUpdate.Set(false), patch.alarmEnabled)
        assertEquals(FieldUpdate.Set(0), patch.timerMinutes)
        assertFalse(patch.isEmpty())
    }

    @Test
    fun switchingTypeNeverReenablesAnExplicitlyDisabledNotification() {
        val form = initial.copy(
            notificationEnabled = false,
            alarmEnabled = true,
            timerMinutesInput = "1"
        )
        listOf(EventType.REMINDER, EventType.TIMESPAN).forEach { type ->
            val changed = form.changeType(type)
            assertFalse(changed.notificationEnabled)
            assertTrue(changed.alarmEnabled)
            assertEquals("1", changed.timerMinutesInput)
        }
    }
}
