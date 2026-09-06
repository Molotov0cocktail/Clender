package com.molotov.clender.ui.calendar

import com.molotov.clender.ui.state.CalendarMode
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

private const val DAYS_PER_WEEK = 7L
private const val MONTH_GRID_DAYS = 42

data class CalendarQueryRange(
    val anchorDate: LocalDate,
    val start: LocalDate,
    val endExclusive: LocalDate,
    val dates: List<LocalDate>
)

object CalendarRangePolicy {
    fun monthGridDates(month: YearMonth, locale: Locale): List<LocalDate> {
        val monthStart = month.atDay(1)
        val firstDay = firstDayOfWeek(locale)
        val leadingDays = Math.floorMod(
            monthStart.dayOfWeek.value - firstDay.value,
            DAYS_PER_WEEK.toInt()
        ).toLong()
        val gridStart = monthStart.minusDays(leadingDays)
        return List(MONTH_GRID_DAYS) { offset -> gridStart.plusDays(offset.toLong()) }
    }

    fun queryRange(
        selectedDate: LocalDate,
        mode: CalendarMode,
        locale: Locale
    ): CalendarQueryRange {
        val dates = when (mode) {
            CalendarMode.MONTH -> monthGridDates(YearMonth.from(selectedDate), locale)
            CalendarMode.WEEK -> weekDates(selectedDate, locale)
            CalendarMode.DAY -> listOf(selectedDate)
        }
        return CalendarQueryRange(
            anchorDate = selectedDate,
            start = dates.first(),
            endExclusive = dates.last().plusDays(1),
            dates = dates
        )
    }

    fun move(date: LocalDate, mode: CalendarMode, amount: Int): LocalDate = try {
        when (mode) {
            CalendarMode.MONTH -> date.plusMonths(amount.toLong())
            CalendarMode.WEEK -> date.plusWeeks(amount.toLong())
            CalendarMode.DAY -> date.plusDays(amount.toLong())
        }
    } catch (_: DateTimeException) {
        date
    } catch (_: ArithmeticException) {
        date
    }

    private fun weekDates(selectedDate: LocalDate, locale: Locale): List<LocalDate> {
        val start = selectedDate.with(
            TemporalAdjusters.previousOrSame(firstDayOfWeek(locale))
        )
        return List(DAYS_PER_WEEK.toInt()) { offset -> start.plusDays(offset.toLong()) }
    }

    private fun firstDayOfWeek(locale: Locale): DayOfWeek = when (locale) {
        Locale.US -> DayOfWeek.SUNDAY
        Locale.SIMPLIFIED_CHINESE -> DayOfWeek.MONDAY
        else -> WeekFields.of(locale).firstDayOfWeek
    }
}
