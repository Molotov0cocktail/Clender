package com.molotov.clender.domain.event

import com.molotov.clender.core.model.EventType
import com.molotov.clender.core.model.eventFixture
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventAlertContractTest {
    @Test
    fun persistedEventHasThreeLocalAlertFieldsDisabledByDefault() {
        val event = eventFixture()
        assertEquals(false, property(event, "getNotificationEnabled"))
        assertEquals(false, property(event, "getAlarmEnabled"))
        assertEquals(0, property(event, "getTimerMinutes"))
    }

    @Test
    fun newReminderDefaultsToNotificationAndNeverToImportantAlarmOrTimer() {
        val reminder =
            AddEventCommand(EventType.REMINDER, "Reminder", LocalDateTime.of(2026, 9, 9, 9, 0))
        assertEquals(true, property(reminder, "getNotificationEnabled"))
        assertEquals(false, property(reminder, "getAlarmEnabled"))
        assertEquals(0, property(reminder, "getTimerMinutes"))
    }

    @Test
    fun newTimespanDefaultsToNoSystemAlert() {
        val start = LocalDateTime.of(2026, 9, 9, 9, 0)
        val span = AddEventCommand(EventType.TIMESPAN, "Focus", start, start.plusHours(1))
        assertEquals(false, property(span, "getNotificationEnabled"))
        assertEquals(false, property(span, "getAlarmEnabled"))
        assertEquals(0, property(span, "getTimerMinutes"))
    }

    @Test
    fun patchesCanExplicitlySetOrClearEveryAlertField() {
        val names = EventPatch::class.java.methods.map { it.name }
        assertTrue(
            names.containsAll(
                listOf("getNotificationEnabled", "getAlarmEnabled", "getTimerMinutes")
            )
        )
        assertTrue(EventPatch().isEmpty())
    }

    private fun property(target: Any, name: String): Any? {
        val method = target.javaClass.methods.firstOrNull {
            it.name == name &&
                it.parameterCount == 0
        }
        assertTrue("${target.javaClass.simpleName} must expose $name", method != null)
        return requireNotNull(method).invoke(target)
    }
}
