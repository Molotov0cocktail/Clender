package com.molotov.clender.domain.widget

import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.core.model.eventFixture
import com.molotov.clender.domain.calendar.EventTemporalState
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateBuilderTest {
    private val day = LocalDate.of(2026, 8, 7)
    private val defaultNow = day.atTime(12, 0)

    @Test
    fun appliesReminderAndTimespanHalfOpenWindowAtBothBoundaries() {
        val result = build(
            events = listOf(
                reminder(1, day.atTime(8, 0)),
                reminder(2, day.atTime(22, 0)),
                span(3, day.atTime(7, 0), day.atTime(8, 0)),
                span(4, day.atTime(7, 59), day.atTime(8, 1)),
                span(5, day.atTime(22, 0), day.plusDays(1).atTime(8, 0)),
                span(6, day.atTime(21, 59), day.plusDays(1).atStartOfDay())
            ),
            now = day.atTime(7, 0)
        )

        assertEquals(listOf(4L, 1L, 6L), result.items.map { it.id })
    }

    @Test
    fun includesCrossDayTimespansButUsesReminderStartForWindowMembership() {
        val result = build(
            events = listOf(
                span(1, day.minusDays(1).atTime(23, 30), day.atTime(8, 30)),
                reminder(2, day.atTime(8, 0)),
                span(3, day.atTime(21, 30), day.plusDays(1).atTime(8, 30)),
                reminder(4, day.minusDays(1).atTime(23, 59)),
                reminder(5, day.atTime(22, 0))
            ),
            now = day.atTime(7, 30)
        )

        assertEquals(listOf(1L, 2L, 3L), result.items.map { it.id })
        assertEquals(EventTemporalState.CURRENT, result.items.first().temporalState)
        assertEquals(EventTemporalState.NEXT, result.items[1].temporalState)
        assertEquals(EventTemporalState.FUTURE, result.items.last().temporalState)
    }

    @Test
    fun reusesClassifierForAllCurrentEarliestTiedNextAndPastFiltering() {
        val result = build(
            events = listOf(
                span(1, day.atTime(10, 0), day.atTime(13, 0)),
                reminder(2, day.atTime(11, 30), duration = 120),
                reminder(3, day.atTime(12, 0)),
                reminder(4, day.atTime(13, 0)),
                reminder(5, day.atTime(13, 0)),
                reminder(6, day.atTime(14, 0)),
                reminder(7, day.atTime(9, 0))
            )
        )
        val states = result.items.associate { it.id to it.temporalState }

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), result.items.map { it.id })
        assertEquals(EventTemporalState.CURRENT, states.getValue(1L))
        assertEquals(EventTemporalState.CURRENT, states.getValue(2L))
        assertEquals(EventTemporalState.CURRENT, states.getValue(3L))
        assertEquals(EventTemporalState.NEXT, states.getValue(4L))
        assertEquals(EventTemporalState.NEXT, states.getValue(5L))
        assertEquals(EventTemporalState.FUTURE, states.getValue(6L))
        assertFalse(states.containsKey(7L))
    }

    @Test
    fun zeroDurationReminderIsCurrentOnlyDuringItsStartMinute() {
        val event = reminder(1, day.atTime(12, 0))

        assertEquals(
            listOf(EventTemporalState.CURRENT),
            build(listOf(event), now = day.atTime(12, 0)).items.map { it.temporalState }
        )
        assertEquals(
            listOf(EventTemporalState.NEXT),
            build(listOf(event), now = day.atTime(11, 59)).items.map { it.temporalState }
        )
        assertTrue(build(listOf(event), now = day.atTime(12, 1)).items.isEmpty())
    }

    @Test
    fun sortsByStartEffectiveEndAndIdAndPreservesUnicodeAndNewlineTitle() {
        val unicodeTitle = "会议 🌏\n第二行"
        val result = build(
            events = listOf(
                reminder(9, day.atTime(9, 0), duration = 30),
                span(2, day.atTime(9, 0), day.atTime(9, 10)),
                reminder(3, day.atTime(9, 0), duration = 10, title = unicodeTitle),
                reminder(1, day.atTime(10, 0), duration = 1)
            ),
            configuration = openDayConfiguration(),
            now = day.atTime(8, 0)
        )

        assertEquals(listOf(2L, 3L, 9L, 1L), result.items.map { it.id })
        assertEquals(unicodeTitle, result.items.single { it.id == 3L }.title)

        val forbiddenFields = setOf(
            "description",
            "estimatedDurationMinutes",
            "syncUid",
            "createdAt",
            "updatedAt",
            "deletedAt"
        )
        result.items.forEach { item ->
            val fieldNames = item::class.java.declaredFields.map { it.name }.toSet()
            assertTrue(
                "Widget item leaked a persisted field: ${fieldNames.intersect(forbiddenFields)}",
                fieldNames.intersect(forbiddenFields).isEmpty()
            )
        }
    }

    @Test
    fun filtersTombstonesInvalidPersistedEventsAndDuplicateIdsWithoutLeakingTitles() {
        val valid = reminder(1, day.atTime(9, 0), title = "keep")
        val duplicate = reminder(1, day.atTime(9, 1), title = "duplicate")
        val tombstone = reminder(2, day.atTime(10, 0), title = "墓碑\nsecret").let {
            it.copy(deletedAt = it.updatedAt)
        }
        val invalid = listOf(
            reminder(3, day.atTime(11, 0), title = " \n "),
            reminder(4, day.atTime(12, 0), duration = -1),
            span(5, day.atTime(13, 0), day.atTime(12, 59)),
            reminder(0, day.atTime(14, 0))
        )

        val result = build(
            events = listOf(valid, duplicate, tombstone) + invalid,
            now = day.atTime(8, 0)
        )

        assertEquals(listOf(1L), result.items.map { it.id })
        assertEquals("keep", result.items.single().title)
        assertTrue(result.items.none { it.title.contains("secret") })
    }

    @Test
    fun acceptsMaximumReminderDurationWithoutClassificationOverflow() {
        val result = build(
            events = listOf(reminder(1, day.atTime(8, 0), duration = Int.MAX_VALUE)),
            now = LocalDateTime.of(6000, 1, 1, 0, 0)
        )

        assertEquals(listOf(1L), result.items.map { it.id })
        assertEquals(EventTemporalState.CURRENT, result.items.single().temporalState)
    }

    private fun build(
        events: List<Event>,
        configuration: WidgetConfiguration = defaultConfiguration(),
        now: LocalDateTime = defaultNow
    ): WidgetState = WidgetStateBuilder.build(
        events = events,
        configuration = configuration,
        date = day,
        now = now
    )

    private fun defaultConfiguration(opacityPercent: Int = 100): WidgetConfiguration =
        WidgetConfiguration(
            appWidgetId = 1,
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(22, 0),
            opacityPercent = opacityPercent,
            fontSizeSp = 13,
            theme = WidgetThemeMode.SYSTEM
        )

    private fun openDayConfiguration(): WidgetConfiguration = WidgetConfiguration(
        appWidgetId = 1,
        startTime = LocalTime.MIDNIGHT,
        endTime = LocalTime.of(23, 59),
        opacityPercent = 100,
        fontSizeSp = 13,
        theme = WidgetThemeMode.SYSTEM
    )

    private fun reminder(
        id: Long,
        startTime: LocalDateTime,
        duration: Int = 0,
        title: String = "事项"
    ): Event = eventFixture(
        id = id,
        title = title,
        startTime = startTime,
        estimatedDurationMinutes = duration,
        syncUid = id.toString(16).padStart(32, '0')
    )

    private fun span(
        id: Long,
        startTime: LocalDateTime,
        endTime: LocalDateTime,
        title: String = "时间段"
    ): Event = eventFixture(
        id = id,
        eventType = EventType.TIMESPAN,
        title = title,
        startTime = startTime,
        endTime = endTime,
        syncUid = id.toString(16).padStart(32, '0')
    )
}
