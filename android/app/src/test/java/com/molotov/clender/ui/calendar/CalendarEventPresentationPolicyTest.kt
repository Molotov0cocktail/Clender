package com.molotov.clender.ui.calendar

import androidx.compose.ui.graphics.luminance
import com.molotov.clender.core.model.EventType
import com.molotov.clender.core.model.eventFixture
import com.molotov.clender.ui.event.EventListLabels
import java.time.LocalDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarEventPresentationPolicyTest {
    @Test
    fun everyPaletteEntryHasOpaqueTextAndAtLeastFourPointFiveContrast() {
        listOf(false, true).forEach { dark ->
            val palette = (1L..6L).map { calendarEventColors(it, dark) }
            assertEquals(6, palette.map { it.background }.distinct().size)
            palette.forEach { colors ->
                val brighter = maxOf(colors.foreground.luminance(), colors.background.luminance())
                val darker = minOf(colors.foreground.luminance(), colors.background.luminance())
                assertTrue((brighter + 0.05f) / (darker + 0.05f) >= 4.5f)
                assertEquals(1f, colors.background.alpha, 0f)
                assertEquals(1f, colors.foreground.alpha, 0f)
            }
        }
    }

    @Test
    fun eventColorIsStableAtLongBoundariesAndAcrossOtherEvents() {
        listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE).forEach { id ->
            val expected = calendarEventColors(id, false)
            calendarEventColors(42, false)
            assertEquals(expected, calendarEventColors(id, false))
        }
    }

    @Test
    fun sameDayUsesCompactTimeWhileCrossDayRetainsBothDates() {
        val start = LocalDateTime.of(2026, 9, 8, 10, 40)
        val sameDay = eventFixture(
            eventType = EventType.TIMESPAN,
            title = "洗漱护肤",
            startTime = start,
            endTime = start.withHour(12).withMinute(0)
        )
        val labels = EventListLabels("提醒", "时间段")
        val compact = eventDisplayLabels(listOf(sameDay), Locale.CHINA, labels).getValue(sameDay.id)
        assertEquals("洗漱护肤\n10:40 – 12:00", compact)
        val crossDay = sameDay.copy(endTime = start.plusDays(1))
        assertEquals(
            "洗漱护肤\n09-08 10:40 – 09-09 10:40",
            eventDisplayLabels(listOf(crossDay), Locale.CHINA, labels).getValue(sameDay.id)
        )
        assertTrue(
            eventLabels(listOf(sameDay), Locale.CHINA, labels).getValue(sameDay.id).contains("2026")
        )
    }

    @Test
    fun invalidAndDeletedEventsHaveNoVisibleOrAccessibleLabels() {
        val invalid = eventFixture().copy(id = 0)
        val deleted = eventFixture().copy(deletedAt = eventFixture().updatedAt)
        val labels = EventListLabels("Reminder", "Time span")
        assertTrue(eventDisplayLabels(listOf(invalid, deleted), Locale.US, labels).isEmpty())
        assertFalse(eventLabels(listOf(invalid, deleted), Locale.US, labels).isNotEmpty())
    }
}
