package com.tzvi.kickoff.feature.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private const val DAYS_PER_WEEK = 7
private const val WEEK_ROWS = 6

private val MonthTitleFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

/**
 * The grid for [month], including the leading and trailing days of its neighbours.
 *
 * Always six rows, even for a month that fits in five: a five-row grid sliding against a
 * six-row one would change height half way through the transition between them.
 */
internal fun buildMonthGrid(
    month: YearMonth,
    today: LocalDate,
    firstDayOfWeek: DayOfWeek,
    dots: Map<LocalDate, List<Int>> = emptyMap(),
): MonthGrid = MonthGrid(
    month = month,
    title = MonthTitleFormat.format(month),
    weeks = gridDates(month, firstDayOfWeek)
        .map { date ->
            CalendarDay(
                date = date,
                label = date.dayOfMonth.toString(),
                inMonth = YearMonth.from(date) == month,
                isToday = date == today,
                dotColors = dots[date].orEmpty(),
            )
        }
        .chunked(DAYS_PER_WEEK),
)

/** The 42 dates the grid for [month] shows, first cell first. */
internal fun gridDates(month: YearMonth, firstDayOfWeek: DayOfWeek): List<LocalDate> {
    val first = month.atDay(1)
    val lead = (first.dayOfWeek.value - firstDayOfWeek.value + DAYS_PER_WEEK) % DAYS_PER_WEEK
    val start = first.minusDays(lead.toLong())
    return (0 until DAYS_PER_WEEK * WEEK_ROWS).map { start.plusDays(it.toLong()) }
}

/** Column headings, in English, starting on the locale's own first day of the week. */
internal fun weekdayLabels(firstDayOfWeek: DayOfWeek): List<String> =
    (0 until DAYS_PER_WEEK).map { offset ->
        firstDayOfWeek.plus(offset.toLong()).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    }
