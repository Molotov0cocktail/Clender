package com.molotov.clender.ui.event

import com.molotov.clender.core.model.EventType
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventFormStateTest {
    private val start = LocalDateTime.of(2026, 8, 9, 23, 30)

    @Test
    fun validReminderBuildsDomainCommandAndAllowsFullNonNegativeIntRange() {
        val state = EventFormState(
            eventType = EventType.REMINDER,
            title = "  计划 🌏  ",
            startTime = start,
            endTime = null,
            description = "说明",
            estimatedDurationMinutes = Int.MAX_VALUE
        )

        assertTrue(state.canSubmit)
        assertTrue(state.validationErrors.isEmpty())
        val command = state.toAddCommand()
        assertEquals(EventType.REMINDER, command.eventType)
        assertEquals("计划 🌏", command.title)
        assertNull(command.endTime)
        assertEquals(Int.MAX_VALUE, command.estimatedDurationMinutes)
    }

    @Test
    fun typeConversionClearsReminderEndAndRequiresNewTimespanEnd() {
        val timespan = EventFormState(
            eventType = EventType.TIMESPAN,
            title = "跨日事项",
            startTime = start,
            endTime = start.plusDays(1),
            description = "",
            estimatedDurationMinutes = 0
        )

        val reminder = timespan.changeType(EventType.REMINDER)
        assertNull(reminder.endTime)
        assertTrue(reminder.canSubmit)

        val incompleteTimespan = reminder.changeType(EventType.TIMESPAN)
        assertNull(incompleteTimespan.endTime)
        assertFalse(incompleteTimespan.canSubmit)
        assertEquals(setOf(EventFormError.MISSING_END_TIME), incompleteTimespan.validationErrors)

        val completed = incompleteTimespan.withEndTime(start.plusDays(1))
        assertTrue(completed.canSubmit)
        assertEquals(start.plusDays(1), completed.toAddCommand().endTime)
    }

    @Test
    fun blankTitleNegativeDurationAndNonIncreasingEndAreAllReported() {
        val invalid = EventFormState(
            eventType = EventType.TIMESPAN,
            title = " \t",
            startTime = start,
            endTime = start,
            description = "",
            estimatedDurationMinutes = -1
        )

        assertFalse(invalid.canSubmit)
        assertEquals(
            setOf(
                EventFormError.BLANK_TITLE,
                EventFormError.NEGATIVE_ESTIMATED_DURATION,
                EventFormError.END_NOT_AFTER_START
            ),
            invalid.validationErrors
        )
    }

    @Test
    fun reminderUnexpectedlyCarryingEndTimeIsRejected() {
        val invalid = EventFormState(
            eventType = EventType.REMINDER,
            title = "提醒",
            startTime = start,
            endTime = start.plusMinutes(30),
            description = "",
            estimatedDurationMinutes = 0
        )

        assertFalse(invalid.canSubmit)
        assertEquals(setOf(EventFormError.REMINDER_END_TIME_PRESENT), invalid.validationErrors)
    }
}
