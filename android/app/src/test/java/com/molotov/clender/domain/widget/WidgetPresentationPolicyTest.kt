package com.molotov.clender.domain.widget

import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.core.model.eventFixture
import com.molotov.clender.domain.calendar.EventTemporalState
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPresentationPolicyTest {
    private val day = LocalDate.of(2026, 8, 7)
    private val now = day.atTime(12, 0)

    @Test
    fun usesFixedSmallMediumAndLargeCapacitiesAndPriorityOrder() {
        val state = state(
            listOf(
                span(1, at(10), at(13)),
                span(2, at(11), at(14)),
                reminder(3, 11, 30, duration = 60),
                reminder(4, 13, 0),
                reminder(5, 13, 0),
                reminder(6, 14, 0),
                reminder(7, 15, 0),
                reminder(8, 16, 0),
                reminder(9, 17, 0)
            )
        )

        val small = present(state, WidgetPresentationPolicy.SizeClass.SMALL)
        val medium = present(state, WidgetPresentationPolicy.SizeClass.MEDIUM)
        val large = present(state, WidgetPresentationPolicy.SizeClass.LARGE)

        assertEquals(listOf(1L, 2L), small.visibleItems.map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L), medium.visibleItems.map { it.id })
        assertEquals(
            listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L),
            large.visibleItems.map { it.id }
        )
        assertEquals(2, small.visibleItems.size)
        assertEquals(4, medium.visibleItems.size)
        assertEquals(8, large.visibleItems.size)
        assertEquals(1, large.remainingCount)
    }

    @Test
    fun currentItemsFillCapacityBeforeAnyNextOrFutureItems() {
        val state = state(
            listOf(
                span(1, at(10), at(13)),
                span(2, at(11), at(14)),
                reminder(3, 11, 30, duration = 60),
                reminder(4, 13, 0),
                reminder(5, 14, 0)
            )
        )

        val presentation = present(state, WidgetPresentationPolicy.SizeClass.SMALL)

        assertEquals(listOf(1L, 2L), presentation.visibleItems.map { it.id })
        assertTrue(presentation.visibleItems.all { it.temporalState == EventTemporalState.CURRENT })
        assertEquals(3, presentation.remainingCount)
    }

    @Test
    fun parallelNextItemsKeepStableOrderWhenNextGroupExceedsCapacity() {
        val state = state(
            listOf(
                span(1, at(10), at(13)),
                reminder(2, 13, 0),
                reminder(3, 13, 0),
                reminder(4, 13, 0),
                reminder(5, 13, 0),
                reminder(6, 13, 0),
                reminder(7, 14, 0)
            )
        )

        val presentation = present(state, WidgetPresentationPolicy.SizeClass.MEDIUM)

        assertEquals(listOf(1L, 2L, 3L, 4L), presentation.visibleItems.map { it.id })
        assertEquals(
            listOf(EventTemporalState.CURRENT) +
                List(3) { EventTemporalState.NEXT },
            presentation.visibleItems.map { it.temporalState }
        )
        assertEquals(3, presentation.remainingCount)
    }

    @Test
    fun emptyAllPastAndFullySanitizedStatePresentAsEmptyWithZeroRemaining() {
        val empty = state(emptyList())
        val allPast = state(listOf(reminder(1, 9, 0), span(2, at(10), at(11))))
        val sanitized = state(
            listOf(
                reminder(3, 9, 0, title = "墓碑").let { it.copy(deletedAt = it.updatedAt) },
                reminder(4, 10, 0, title = " "),
                span(5, at(11), at(10, 59))
            )
        )

        listOf(empty, allPast, sanitized).forEach { state ->
            WidgetPresentationPolicy.SizeClass.entries.forEach { sizeClass ->
                val presentation = present(state, sizeClass)
                assertTrue(presentation.visibleItems.isEmpty())
                assertEquals(0, presentation.remainingCount)
            }
        }
    }

    @Test
    fun truncationPreservesTemporalStateAndOpacityZeroKeepsCompleteItems() {
        val state = state(
            listOf(
                span(1, at(10), at(13)),
                reminder(2, 13, 0),
                reminder(3, 14, 0)
            ),
            opacityPercent = 0
        )

        val presentation = present(state, WidgetPresentationPolicy.SizeClass.SMALL)

        assertEquals(0, presentation.opacityPercent)
        assertEquals(listOf(1L, 2L), presentation.visibleItems.map { it.id })
        assertEquals(
            listOf(EventTemporalState.CURRENT, EventTemporalState.NEXT),
            presentation.visibleItems.map { it.temporalState }
        )
        assertEquals(EventType.REMINDER, presentation.visibleItems[1].eventType)
        assertEquals("事项 2", presentation.visibleItems[1].title)
        assertEquals(day.atTime(13, 0), presentation.visibleItems[1].startTime)
        assertEquals(null, presentation.visibleItems[1].endTime)
    }

    @Test
    fun remainingCountIsExactAndNeverNegativeForAllCapacities() {
        val state = state(
            (1L..7L).map { id -> reminder(id, 13 + id.toInt(), 0) }
        )

        val expected = mapOf(
            WidgetPresentationPolicy.SizeClass.SMALL to 5,
            WidgetPresentationPolicy.SizeClass.MEDIUM to 3,
            WidgetPresentationPolicy.SizeClass.LARGE to 0
        )
        expected.forEach { (sizeClass, remaining) ->
            val presentation = present(state, sizeClass)
            assertEquals(remaining, presentation.remainingCount)
            assertTrue(presentation.remainingCount >= 0)
            assertEquals(
                state.items.size - presentation.visibleItems.size,
                presentation.remainingCount
            )
            assertEquals(
                presentation.visibleItems.map { it.id }.toSet().size,
                presentation.visibleItems.size
            )
        }
    }

    private fun state(events: List<Event>, opacityPercent: Int = 100): WidgetState =
        WidgetStateBuilder.build(
            events = events,
            configuration = WidgetConfiguration(
                appWidgetId = 1,
                startTime = LocalTime.MIDNIGHT,
                endTime = LocalTime.of(23, 59),
                opacityPercent = opacityPercent,
                fontSizeSp = 13,
                theme = WidgetThemeMode.SYSTEM
            ),
            date = day,
            now = now
        )

    private fun present(
        state: WidgetState,
        sizeClass: WidgetPresentationPolicy.SizeClass
    ): WidgetPresentation = WidgetPresentationPolicy.present(state, sizeClass)

    private fun reminder(
        id: Long,
        hour: Int,
        minute: Int,
        duration: Int = 0,
        title: String = "事项 $id"
    ): Event = eventFixture(
        id = id,
        title = title,
        startTime = day.atTime(hour, minute),
        estimatedDurationMinutes = duration,
        syncUid = id.toString(16).padStart(32, '0')
    )

    private fun span(
        id: Long,
        startTime: LocalDateTime,
        endTime: LocalDateTime,
        title: String = "时间段 $id"
    ): Event = eventFixture(
        id = id,
        eventType = EventType.TIMESPAN,
        title = title,
        startTime = startTime,
        endTime = endTime,
        syncUid = id.toString(16).padStart(32, '0')
    )

    private fun at(hour: Int, minute: Int = 0): LocalDateTime = day.atTime(hour, minute)
}
