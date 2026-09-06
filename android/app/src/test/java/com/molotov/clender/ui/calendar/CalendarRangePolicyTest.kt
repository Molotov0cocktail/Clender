package com.molotov.clender.ui.calendar

import com.molotov.clender.ui.state.CalendarMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarRangePolicyTest {
    @Test
    fun everyMonthLengthProducesExactlySixIncreasingWeeks() {
        listOf(
            YearMonth.of(2021, 2),
            YearMonth.of(2024, 2),
            YearMonth.of(2026, 4),
            YearMonth.of(2026, 8)
        ).forEach { month ->
            val dates = CalendarRangePolicy.monthGridDates(month, Locale.SIMPLIFIED_CHINESE)

            assertEquals(42, dates.size)
            assertEquals(42, dates.distinct().size)
            assertTrue(dates.zipWithNext().all { (left, right) -> right == left.plusDays(1) })
            assertTrue(month.atDay(1) in dates)
            assertTrue(month.atEndOfMonth() in dates)
        }
    }

    @Test
    fun localeControlsFirstDayWithoutChangingFortyTwoDayContract() {
        val month = YearMonth.of(2026, 8)

        val chinese = CalendarRangePolicy.monthGridDates(month, Locale.SIMPLIFIED_CHINESE)
        val us = CalendarRangePolicy.monthGridDates(month, Locale.US)
        val france = CalendarRangePolicy.monthGridDates(month, Locale.FRANCE)

        assertEquals(DayOfWeek.MONDAY, chinese.first().dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, us.first().dayOfWeek)
        assertEquals(DayOfWeek.MONDAY, france.first().dayOfWeek)
        assertEquals(42, chinese.size)
        assertEquals(42, us.size)
        assertEquals(42, france.size)
    }

    @Test
    fun emptyAndUncommonLocalesRemainSafeAndIncreasing() {
        listOf(Locale.ROOT, Locale.forLanguageTag("ar-SA"), Locale("zz", "ZZ")).forEach { locale ->
            val dates = CalendarRangePolicy.monthGridDates(YearMonth.of(2026, 8), locale)

            assertEquals(42, dates.size)
            assertTrue(dates.zipWithNext().all { (left, right) -> left.isBefore(right) })
        }
    }

    @Test
    fun monthRangeCoversTheCompleteVisibleGridAsHalfOpenBounds() {
        val range = CalendarRangePolicy.queryRange(
            selectedDate = LocalDate.of(2026, 8, 15),
            mode = CalendarMode.MONTH,
            locale = Locale.SIMPLIFIED_CHINESE
        )

        assertEquals(LocalDate.of(2026, 7, 27), range.start)
        assertEquals(LocalDate.of(2026, 9, 7), range.endExclusive)
        assertEquals(42, range.dates.size)
        assertTrue(range.start.isBefore(range.endExclusive))
    }

    @Test
    fun weekAndDayRangesAreStrictHalfOpenBounds() {
        val selected = LocalDate.of(2026, 8, 12)
        val chineseWeek = CalendarRangePolicy.queryRange(
            selected,
            CalendarMode.WEEK,
            Locale.SIMPLIFIED_CHINESE
        )
        val usWeek = CalendarRangePolicy.queryRange(selected, CalendarMode.WEEK, Locale.US)
        val day = CalendarRangePolicy.queryRange(selected, CalendarMode.DAY, Locale.US)

        assertEquals(LocalDate.of(2026, 8, 10), chineseWeek.start)
        assertEquals(LocalDate.of(2026, 8, 17), chineseWeek.endExclusive)
        assertEquals(LocalDate.of(2026, 8, 9), usWeek.start)
        assertEquals(LocalDate.of(2026, 8, 16), usWeek.endExclusive)
        assertEquals(selected, day.start)
        assertEquals(selected.plusDays(1), day.endExclusive)
        assertEquals(7, chineseWeek.dates.size)
        assertEquals(1, day.dates.size)
    }

    @Test
    fun previousAndNextFollowModeAndLocalDateClampingRules() {
        val januaryEnd = LocalDate.of(2025, 1, 31)

        assertEquals(
            LocalDate.of(2025, 2, 28),
            CalendarRangePolicy.move(januaryEnd, CalendarMode.MONTH, 1)
        )
        assertEquals(
            LocalDate.of(2024, 2, 29),
            CalendarRangePolicy.move(LocalDate.of(2024, 3, 31), CalendarMode.MONTH, -1)
        )
        assertEquals(
            LocalDate.of(2027, 1, 2),
            CalendarRangePolicy.move(LocalDate.of(2026, 12, 26), CalendarMode.WEEK, 1)
        )
        assertEquals(
            LocalDate.of(2025, 12, 31),
            CalendarRangePolicy.move(LocalDate.of(2026, 1, 1), CalendarMode.DAY, -1)
        )
    }

    @Test
    fun navigationOverflowAtLocalDateExtremesKeepsOriginalDate() {
        CalendarMode.entries.forEach { mode ->
            assertEquals(LocalDate.MIN, CalendarRangePolicy.move(LocalDate.MIN, mode, -1))
            assertEquals(LocalDate.MAX, CalendarRangePolicy.move(LocalDate.MAX, mode, 1))
        }
    }

    @Test
    fun outsideMonthSelectionStillQueriesItsOwnFortyTwoDayGrid() {
        val selectedOutsidePreviouslyVisibleMonth = LocalDate.of(2026, 7, 27)
        val range = CalendarRangePolicy.queryRange(
            selectedOutsidePreviouslyVisibleMonth,
            CalendarMode.MONTH,
            Locale.SIMPLIFIED_CHINESE
        )

        assertTrue(selectedOutsidePreviouslyVisibleMonth in range.dates)
        assertEquals(YearMonth.of(2026, 7), YearMonth.from(range.anchorDate))
        assertEquals(42, range.dates.size)
    }
}
