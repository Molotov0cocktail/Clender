package com.molotov.clender.ui.event

import com.molotov.clender.core.model.EventType
import com.molotov.clender.core.model.eventFixture
import com.molotov.clender.domain.event.FieldUpdate
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventFormContractTest {
    private val start = LocalDateTime.of(2026, 8, 31, 9, 0)

    @Test
    fun newFormUsesRouteDateAtNineWithReminderDefaults() {
        val state = EventFormState.new(LocalDate.of(2026, 8, 31))

        assertEquals(EventType.REMINDER, state.eventType)
        assertEquals(start, state.startTime)
        assertNull(state.endTime)
        assertEquals("", state.title)
        assertEquals("", state.description)
        assertEquals("0", state.estimatedDurationInput)
    }

    @Test
    fun rawDurationRejectsEmptyNonNumericNegativeAndOverflowWithoutClamping() {
        val expected = listOf(
            "" to EventFormError.EMPTY_ESTIMATED_DURATION,
            "12.5" to EventFormError.NON_NUMERIC_ESTIMATED_DURATION,
            "-1" to EventFormError.NEGATIVE_ESTIMATED_DURATION,
            "2147483648" to EventFormError.OUT_OF_RANGE_ESTIMATED_DURATION
        )

        expected.forEach { (input, error) ->
            val state = validReminder().copy(estimatedDurationInput = input)
            assertFalse(input, state.canSubmit)
            assertTrue(input, error in state.validationErrors)
        }
    }

    @Test
    fun rawDurationAcceptsZeroAndIntMaximumExactly() {
        listOf("0" to 0, Int.MAX_VALUE.toString() to Int.MAX_VALUE).forEach { (raw, parsed) ->
            val command = validReminder().copy(estimatedDurationInput = raw).toAddCommand()
            assertEquals(parsed, command.estimatedDurationMinutes)
        }
    }

    @Test
    fun titleIsTrimmedOnlyAtSubmissionWhileDescriptionPreservesUnicodeAndWhitespace() {
        val description = " \n 说明 🌏\t "
        val state = validReminder().copy(
            title = "  标题 e\u0301 🌏  ",
            description = description
        )

        assertEquals("  标题 e\u0301 🌏  ", state.title)
        val command = state.toAddCommand()
        assertEquals("标题 e\u0301 🌏", command.title)
        assertEquals(description, command.description)
    }

    @Test
    fun timespanRequiresExplicitStrictlyLaterEndAndAllowsCrossDay() {
        val timespan = validReminder().changeType(EventType.TIMESPAN)
        assertNull(timespan.endTime)
        assertEquals(setOf(EventFormError.MISSING_END_TIME), timespan.validationErrors)

        val equal = timespan.withEndTime(start)
        val earlier = timespan.withEndTime(start.minusMinutes(1))
        assertEquals(setOf(EventFormError.END_NOT_AFTER_START), equal.validationErrors)
        assertEquals(setOf(EventFormError.END_NOT_AFTER_START), earlier.validationErrors)

        val crossDay = timespan.withEndTime(start.plusDays(1))
        assertTrue(crossDay.canSubmit)
        assertEquals(start.plusDays(1), crossDay.toAddCommand().endTime)
    }

    @Test
    fun typeConversionClearsEndAndChangingStartNeverMutatesExistingEnd() {
        val end = start.plusHours(1)
        val timespan = validReminder()
            .changeType(EventType.TIMESPAN)
            .withEndTime(end)

        val reminder = timespan.changeType(EventType.REMINDER)
        assertNull(reminder.endTime)
        val incompleteTimespan = reminder.changeType(EventType.TIMESPAN)
        assertNull(incompleteTimespan.endTime)

        val invalidated = timespan.withStartTime(end.plusMinutes(1))
        assertEquals(end, invalidated.endTime)
        assertEquals(setOf(EventFormError.END_NOT_AFTER_START), invalidated.validationErrors)
    }

    @Test
    fun pickedDateTimesAreNormalizedToMinutePrecision() {
        val state = validReminder()
            .withStartTime(start.withSecond(58).withNano(999_999_999))
            .changeType(EventType.TIMESPAN)
            .withEndTime(start.plusHours(1).withSecond(4).withNano(7))

        assertEquals(0, state.startTime.second)
        assertEquals(0, state.startTime.nano)
        assertEquals(0, requireNotNull(state.endTime).second)
        assertEquals(0, requireNotNull(state.endTime).nano)
    }

    @Test
    fun addCommandCarriesEveryValidatedField() {
        val end = start.plusDays(1)
        val command = validReminder().copy(
            eventType = EventType.TIMESPAN,
            title = "  跨日  ",
            endTime = end,
            description = " 保留 ",
            estimatedDurationInput = "42"
        ).toAddCommand()

        assertEquals(EventType.TIMESPAN, command.eventType)
        assertEquals("跨日", command.title)
        assertEquals(start, command.startTime)
        assertEquals(end, command.endTime)
        assertEquals(" 保留 ", command.description)
        assertEquals(42, command.estimatedDurationMinutes)
    }

    @Test
    fun editFormRoundTripsAllVisibleFieldsAndUnchangedPatchIsEmpty() {
        val original = eventFixture(
            id = 42,
            eventType = EventType.TIMESPAN,
            title = "标题",
            startTime = start,
            endTime = start.plusHours(2),
            description = "说明 🌏",
            estimatedDurationMinutes = Int.MAX_VALUE
        )

        val state = EventFormState.fromEvent(original)
        assertEquals(original.eventType, state.eventType)
        assertEquals(original.title, state.title)
        assertEquals(original.startTime, state.startTime)
        assertEquals(original.endTime, state.endTime)
        assertEquals(original.description, state.description)
        assertEquals(Int.MAX_VALUE.toString(), state.estimatedDurationInput)
        assertTrue(state.toPatch(original).isEmpty())
    }

    @Test
    fun updatePatchContainsOnlyActuallyChangedFields() {
        val original = eventFixture(id = 7, startTime = start, title = "旧")
        val patch = EventFormState.fromEvent(original).copy(
            eventType = EventType.TIMESPAN,
            title = "  新  ",
            startTime = start.plusMinutes(1),
            endTime = start.plusHours(1),
            description = "new description",
            estimatedDurationInput = "9"
        ).toPatch(original)

        assertEquals(FieldUpdate.Set(EventType.TIMESPAN), patch.eventType)
        assertEquals(FieldUpdate.Set("新"), patch.title)
        assertEquals(FieldUpdate.Set(start.plusMinutes(1)), patch.startTime)
        assertEquals(FieldUpdate.Set(start.plusHours(1)), patch.endTime)
        assertEquals(FieldUpdate.Set("new description"), patch.description)
        assertEquals(FieldUpdate.Set(9), patch.estimatedDurationMinutes)
    }

    @Test
    fun convertingTimespanToReminderExpressesTypeChangeWithoutSyntheticEndPatch() {
        val original = eventFixture(
            id = 8,
            eventType = EventType.TIMESPAN,
            startTime = start,
            endTime = start.plusHours(1)
        )
        val patch = EventFormState.fromEvent(original)
            .changeType(EventType.REMINDER)
            .toPatch(original)

        assertEquals(FieldUpdate.Set(EventType.REMINDER), patch.eventType)
        assertEquals(FieldUpdate.Unchanged, patch.endTime)
    }

    private fun validReminder() = EventFormState(
        eventType = EventType.REMINDER,
        title = "事项",
        startTime = start,
        endTime = null,
        description = "",
        estimatedDurationInput = "0"
    )
}
