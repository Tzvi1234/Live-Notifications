package com.tzvi.kickoff.data.repository

import com.tzvi.kickoff.core.model.CalendarAvailability
import com.tzvi.kickoff.core.model.CalendarEvent
import com.tzvi.kickoff.core.model.DeviceCalendar
import com.tzvi.kickoff.data.calendar.CalendarProviderDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarRepository @Inject constructor(
    private val source: CalendarProviderDataSource,
    private val settings: SettingsRepository,
) {
    fun hasPermission(): Boolean = source.hasPermission()

    suspend fun availability(): CalendarAvailability = source.availability()

    suspend fun calendars(): List<DeviceCalendar> = source.calendars()

    /**
     * Upcoming occurrences, re-read on every provider change.
     *
     * The window is deliberately short: the provider materialises instances lazily and
     * a multi-year window over dense recurrences is genuinely slow.
     */
    fun observeUpcoming(days: Long = 7): Flow<List<CalendarEvent>> =
        source.changes().map { upcoming(days) }

    suspend fun upcoming(days: Long = 7): List<CalendarEvent> {
        val enabled = settings.settings.first().enabledCalendarIds
        val now = Instant.now()
        return source.instances(
            from = now.minus(Duration.ofHours(2)),
            to = now.plus(Duration.ofDays(days)),
            calendarIds = enabled,
        ).withAccounts()
    }

    /**
     * Fills in the owning account, which the Instances table cannot return: it inherits
     * the Events columns, and ACCOUNT_NAME is not among them.
     */
    private suspend fun List<CalendarEvent>.withAccounts(): List<CalendarEvent> {
        if (isEmpty()) return this
        val accounts = source.calendars().associate { it.id to it.accountName }
        return map { event -> event.copy(accountName = accounts[event.calendarId]) }
    }

    suspend fun eventsOn(date: LocalDate): List<CalendarEvent> {
        val enabled = settings.settings.first().enabledCalendarIds
        val zone = ZoneId.systemDefault()
        return source.instances(
            from = date.atStartOfDay(zone).toInstant(),
            to = date.plusDays(1).atStartOfDay(zone).toInstant(),
            calendarIds = enabled,
        ).withAccounts()
    }

    /** The next occurrence that has not started yet, if any. */
    suspend fun nextEvent(): CalendarEvent? {
        val now = Instant.now()
        return upcoming(2).firstOrNull { it.instanceStart.isAfter(now) && !it.isAllDay }
    }

    fun observePermission(): Flow<Boolean> = flow { emit(source.hasPermission()) }
}
