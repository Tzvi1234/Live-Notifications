package com.tzvi.kickoff.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tzvi.kickoff.core.model.CalendarAvailability
import com.tzvi.kickoff.core.model.CalendarEvent
import com.tzvi.kickoff.core.model.DeviceCalendar
import com.tzvi.kickoff.data.repository.CalendarRepository
import com.tzvi.kickoff.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val calendarRepository: CalendarRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.now(zone)
    private val firstDayOfWeek: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek

    private val visibleMonth = MutableStateFlow(YearMonth.from(today))
    private val selectedDate = MutableStateFlow(today)
    private val reloads = MutableStateFlow(0)
    private val permissionBlocked = MutableStateFlow(false)

    private var lastKnownPermission = calendarRepository.hasPermission()

    private val enabledIds: Flow<Set<Long>> = settingsRepository.settings
        .map { it.enabledCalendarIds }
        .distinctUntilChanged()

    /**
     * Every reason to re-read the provider: an explicit reload, and any edit another app
     * makes while this screen is open.
     *
     * The observer flow closes immediately when the permission is missing, which is why it
     * is re-subscribed on each reload rather than collected once - and why [onStart] emits
     * first, so a denied permission still produces a state instead of leaving the combine
     * below waiting for a value that will never arrive. Shared because all three readers
     * below want the same signal, and each subscription of its own would register its own
     * content observer.
     */
    private val revisions: Flow<Unit> = reloads
        .flatMapLatest {
            calendarRepository.observeUpcoming(PROVIDER_WATCH_DAYS)
                .map { }
                .onStart { emit(Unit) }
                .catch { emit(Unit) }
        }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val accessState: Flow<AccessState> = revisions
        .mapLatest {
            val availability = runCatching { calendarRepository.availability() }
                .getOrDefault(CalendarAvailability.PROVIDER_UNAVAILABLE)
            AccessState(
                availability = availability,
                calendars = if (availability == CalendarAvailability.PERMISSION_DENIED) {
                    emptyList()
                } else {
                    runCatching { calendarRepository.calendars() }.getOrDefault(emptyList())
                },
                isLoading = false,
            )
        }
        .onStart { emit(AccessState()) }

    private val monthState: Flow<MonthState> =
        combine(revisions, enabledIds, visibleMonth) { _, _, month -> month }
            .flatMapLatest { month ->
                flow {
                    emit(MonthState(month))
                    emit(MonthState(month, dotsFor(month), isLoading = false))
                }
            }

    private val agendaState: Flow<AgendaState> =
        combine(revisions, enabledIds, selectedDate) { _, _, date -> date }
            .flatMapLatest { date ->
                flow {
                    emit(AgendaState(date))
                    emit(AgendaState(date, eventsOn(date), isLoading = false))
                }
            }

    val uiState: StateFlow<CalendarUiState> = combine(
        accessState,
        monthState,
        agendaState,
        settingsRepository.settings,
        permissionBlocked,
    ) { access, month, day, settings, blocked ->
        CalendarUiState(
            isLoading = access.isLoading,
            availability = access.availability,
            canRequestPermission = !blocked,
            grid = buildMonthGrid(month.month, today, firstDayOfWeek, month.dots),
            weekdayLabels = weekdayLabels(firstDayOfWeek),
            isOnCurrentMonth = month.month == YearMonth.from(today),
            selectedDate = day.date,
            selectedDayTitle = dayTitle(day.date),
            selectedDateLabel = FULL_DATE.format(day.date),
            selectedDayPhrase = dayPhrase(day.date),
            agenda = day.events.map { it.toEntry() },
            isAgendaLoading = day.isLoading,
            calendars = access.calendars.map { it.toToggle(settings.enabledCalendarIds) },
            syncEnabled = settings.calendarSyncEnabled,
            leadMinutes = settings.calendarLeadMinutes,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialState())

    fun selectDate(date: LocalDate) {
        selectedDate.value = date
        // Tapping a trailing cell of the previous month moves the grid to that month, so
        // the agenda below always describes a day that is actually on screen.
        visibleMonth.value = YearMonth.from(date)
    }

    fun showPreviousMonth() = showMonth(visibleMonth.value.minusMonths(1))

    fun showNextMonth() = showMonth(visibleMonth.value.plusMonths(1))

    fun showToday() = showMonth(YearMonth.from(today))

    fun onPermissionResult(granted: Boolean, canAskAgain: Boolean) {
        lastKnownPermission = granted
        // Android silently drops the dialog after a second refusal, and the rationale flag
        // turning false straight after one is the only signal it gives that this happened.
        permissionBlocked.value = !granted && !canAskAgain
        reload()
    }

    /**
     * Called on every resume. It only re-reads the grant - nothing is ever requested here,
     * because a dialog the user did not ask for is a dialog they deny.
     */
    fun onResumed() {
        val granted = calendarRepository.hasPermission()
        if (granted == lastKnownPermission) return
        lastKnownPermission = granted
        if (granted) permissionBlocked.value = false
        reload()
    }

    fun setSyncEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCalendarSyncEnabled(enabled) }
    }

    fun setLeadMinutes(minutes: Int) {
        viewModelScope.launch { settingsRepository.setCalendarLeadMinutes(minutes) }
    }

    /**
     * An empty stored set means "read every calendar", so it cannot also mean "read none":
     * turning the last switch off stores an id no calendar can own instead. Turning them
     * all on stores the empty set again, so a calendar added later is included by default.
     */
    fun setCalendarEnabled(id: Long, enabled: Boolean) {
        val calendars = uiState.value.calendars
        val allIds = calendars.mapTo(mutableSetOf()) { it.id }
        val current = calendars.filter { it.isEnabled }.mapTo(mutableSetOf()) { it.id }
        val next = if (enabled) current + id else current - id
        viewModelScope.launch {
            settingsRepository.setEnabledCalendars(
                when {
                    next.isEmpty() -> setOf(NO_CALENDARS)
                    next.containsAll(allIds) -> emptySet()
                    else -> next
                },
            )
        }
    }

    private fun showMonth(month: YearMonth) {
        visibleMonth.value = month
        // Moving the grid moves the selection with it, so the agenda never describes a day
        // the user can no longer see.
        selectedDate.value = if (month == YearMonth.from(today)) today else month.atDay(1)
    }

    private fun reload() = reloads.update { it + 1 }

    /**
     * Dots for the whole visible grid.
     *
     * The repository reads a day at a time, so a month costs one provider query per cell.
     * They are cheap local queries and they only run when the month, the selected
     * calendars or the provider itself changes - but 42 of them still do not belong on the
     * main thread.
     */
    private suspend fun dotsFor(month: YearMonth): Map<LocalDate, List<Int>> =
        withContext(Dispatchers.Default) {
            gridDates(month, firstDayOfWeek)
                .associateWith { date -> eventsOn(date).map { it.color }.distinct().take(MAX_DOTS) }
                .filterValues { it.isNotEmpty() }
        }

    private suspend fun eventsOn(date: LocalDate): List<CalendarEvent> =
        runCatching { calendarRepository.eventsOn(date) }
            .getOrDefault(emptyList())
            .filter { it.covers(date) }
            .sortedWith(compareBy({ !it.isAllDay }, { it.instanceStart }))

    /**
     * Whether this occurrence belongs on [date].
     *
     * All-day events are stored at midnight *UTC*, which is what `CalendarEvent.localDate`
     * reads them back in; formatting `instanceStart` in the device zone instead lands every
     * one of them on the wrong day west of Greenwich. The filter is not cosmetic either:
     * the provider window for a local day overlaps two UTC all-day slots, so without it
     * each all-day event is returned for two consecutive days.
     */
    private fun CalendarEvent.covers(date: LocalDate): Boolean {
        val start = localDate(zone)
        if (date.isBefore(start)) return false
        return if (isAllDay) {
            date.isBefore(start.plusDays((durationMinutes / MINUTES_PER_DAY).coerceAtLeast(1)))
        } else {
            // The end instant is exclusive: a meeting finishing at midnight belongs to the
            // day it was held on, not to the one after it.
            val lastInstant = instanceEnd.minusMillis(1).coerceAtLeast(instanceStart)
            !date.isAfter(lastInstant.atZone(zone).toLocalDate())
        }
    }

    private fun CalendarEvent.toEntry() = AgendaEntry(
        id = "$eventId-${instanceStart.toEpochMilli()}",
        timeLabel = if (isAllDay) ALL_DAY else TIME_FORMAT.format(instanceStart.atZone(zone)),
        endLabel = if (isAllDay) null else TIME_FORMAT.format(instanceEnd.atZone(zone)),
        title = title,
        location = location,
        calendarName = calendarName?.takeIf { it.isNotBlank() } ?: accountName,
        color = color,
    )

    private fun DeviceCalendar.toToggle(stored: Set<Long>) = CalendarToggle(
        id = id,
        name = displayName.takeIf { it.isNotBlank() } ?: accountName,
        accountName = accountName,
        color = color,
        isEnabled = stored.isEmpty() || id in stored,
        isHidden = !isVisible,
    )

    private fun dayTitle(date: LocalDate): String = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> FULL_DATE.format(date)
    }

    private fun dayPhrase(date: LocalDate): String = when (date) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        today.minusDays(1) -> "yesterday"
        else -> "on ${SHORT_DATE.format(date)}"
    }

    private fun initialState() = CalendarUiState(
        isLoading = true,
        grid = buildMonthGrid(YearMonth.from(today), today, firstDayOfWeek),
        weekdayLabels = weekdayLabels(firstDayOfWeek),
        selectedDate = today,
        selectedDayTitle = dayTitle(today),
        selectedDateLabel = FULL_DATE.format(today),
        selectedDayPhrase = dayPhrase(today),
    )

    private data class AccessState(
        val availability: CalendarAvailability = CalendarAvailability.OK,
        val calendars: List<DeviceCalendar> = emptyList(),
        val isLoading: Boolean = true,
    )

    private data class MonthState(
        val month: YearMonth,
        val dots: Map<LocalDate, List<Int>> = emptyMap(),
        val isLoading: Boolean = true,
    )

    private data class AgendaState(
        val date: LocalDate,
        val events: List<CalendarEvent> = emptyList(),
        val isLoading: Boolean = true,
    )

    private companion object {
        const val MAX_DOTS = 3
        const val MINUTES_PER_DAY = 1_440L
        const val ALL_DAY = "All day"

        /** An id no calendar can own, so "no calendars" is storable at all. */
        const val NO_CALENDARS = -1L

        /**
         * The change observer is the point of this subscription; the window is kept to a
         * day because every emission also costs the read that produced it.
         */
        const val PROVIDER_WATCH_DAYS = 1L

        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
        val FULL_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
        val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    }
}
