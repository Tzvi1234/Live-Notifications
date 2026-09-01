package com.tzvi.kickoff.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A calendar the device knows about, from any account type - not only Google. */
data class DeviceCalendar(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    val color: Int,
    val isVisible: Boolean,
    val isSyncing: Boolean,
) {
    val isGoogle: Boolean get() = accountType == ACCOUNT_TYPE_GOOGLE

    companion object {
        /** Same string as `GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE`, without the dependency. */
        const val ACCOUNT_TYPE_GOOGLE = "com.google"
    }
}

/**
 * One occurrence of a calendar event, read from `CalendarContract.Instances`
 * (already expanded from any recurrence rule).
 */
data class CalendarEvent(
    val eventId: Long,
    val instanceStart: Instant,
    val instanceEnd: Instant,
    val title: String,
    val location: String?,
    val description: String?,
    val isAllDay: Boolean,
    val calendarId: Long,
    val calendarName: String?,
    val accountName: String?,
    val color: Int,
) {
    /**
     * All-day events are stored at midnight *UTC* regardless of where the user is, so
     * they must be read back in UTC. Anything else is local.
     */
    fun localDate(zone: ZoneId): LocalDate =
        if (isAllDay) instanceStart.atZone(ZoneId.of("UTC")).toLocalDate()
        else instanceStart.atZone(zone).toLocalDate()

    val durationMinutes: Long
        get() = ((instanceEnd.toEpochMilli() - instanceStart.toEpochMilli()) / 60_000L)
            .coerceAtLeast(0)
}

/** Why a calendar read came back empty - each case needs different copy in the UI. */
enum class CalendarAvailability {
    OK,
    PERMISSION_DENIED,
    NO_CALENDARS,
    NO_VISIBLE_CALENDARS,
    PROVIDER_UNAVAILABLE,
}
