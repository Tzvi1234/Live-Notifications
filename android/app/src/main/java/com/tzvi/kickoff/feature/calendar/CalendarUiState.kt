package com.tzvi.kickoff.feature.calendar

import com.tzvi.kickoff.core.model.CalendarAvailability
import java.time.LocalDate
import java.time.YearMonth

/** Lead times the live card can be offered at, in minutes. */
internal val LeadTimeOptions = listOf(5, 10, 15, 30, 60)

/**
 * One cell of the month grid.
 *
 * [dotColors] are the ARGB values the provider gave for the owning calendars, so a day
 * with a work meeting and a birthday carries two differently coloured dots.
 */
data class CalendarDay(
    val date: LocalDate,
    val label: String,
    val inMonth: Boolean,
    val isToday: Boolean,
    val dotColors: List<Int> = emptyList(),
)

/** A whole month, already chunked into the rows the grid draws. */
data class MonthGrid(
    val month: YearMonth,
    val title: String,
    val weeks: List<List<CalendarDay>>,
)

/** One agenda row, pre-formatted so the row itself does no date maths. */
data class AgendaEntry(
    /** Event id plus start instant: a recurring event repeats its id on every occurrence. */
    val id: String,
    val timeLabel: String,
    val endLabel: String?,
    val title: String,
    val location: String?,
    val calendarName: String?,
    val color: Int,
)

data class CalendarToggle(
    val id: Long,
    val name: String,
    val accountName: String,
    val color: Int,
    val isEnabled: Boolean,
    /** Switched off for display in the system calendar app, so it holds nothing we show. */
    val isHidden: Boolean,
)

/**
 * Why the agenda is empty.
 *
 * The five [CalendarAvailability] cases plus the two the user causes themselves are one
 * empty list to the code and seven different problems - and seven different fixes - to
 * the person looking at the screen.
 */
enum class CalendarEmptyReason {
    PERMISSION_DENIED,
    PROVIDER_UNAVAILABLE,
    NO_CALENDARS,
    NO_VISIBLE_CALENDARS,
    NONE_SELECTED,
    NOTHING_SCHEDULED,
}

data class CalendarUiState(
    val isLoading: Boolean = true,
    val availability: CalendarAvailability = CalendarAvailability.OK,
    /** False once Android has stopped showing the dialog; only app settings works then. */
    val canRequestPermission: Boolean = true,
    val grid: MonthGrid = MonthGrid(YearMonth.now(), "", emptyList()),
    val weekdayLabels: List<String> = emptyList(),
    val isOnCurrentMonth: Boolean = true,
    val selectedDate: LocalDate = LocalDate.now(),
    /** "Today", or the full date once the selection is further away than that. */
    val selectedDayTitle: String = "",
    val selectedDateLabel: String = "",
    /** Slots into a sentence: "nothing scheduled today", "... on Sat 5 Sep". */
    val selectedDayPhrase: String = "",
    val agenda: List<AgendaEntry> = emptyList(),
    val isAgendaLoading: Boolean = false,
    val calendars: List<CalendarToggle> = emptyList(),
    val syncEnabled: Boolean = false,
    val leadMinutes: Int = 30,
) {
    /** Null while there is something to show; ordered from least to most fixable. */
    val emptyReason: CalendarEmptyReason?
        get() = when {
            agenda.isNotEmpty() -> null
            availability == CalendarAvailability.PERMISSION_DENIED ->
                CalendarEmptyReason.PERMISSION_DENIED
            availability == CalendarAvailability.PROVIDER_UNAVAILABLE ->
                CalendarEmptyReason.PROVIDER_UNAVAILABLE
            availability == CalendarAvailability.NO_CALENDARS ->
                CalendarEmptyReason.NO_CALENDARS
            availability == CalendarAvailability.NO_VISIBLE_CALENDARS ->
                CalendarEmptyReason.NO_VISIBLE_CALENDARS
            // Guarded on the list being non-empty: a calendar read that failed leaves no
            // toggles to switch back on, so "they are all off" would be a lie.
            calendars.isNotEmpty() && calendars.none { it.isEnabled } ->
                CalendarEmptyReason.NONE_SELECTED
            else -> CalendarEmptyReason.NOTHING_SCHEDULED
        }

    val permissionDenied: Boolean
        get() = availability == CalendarAvailability.PERMISSION_DENIED
}
