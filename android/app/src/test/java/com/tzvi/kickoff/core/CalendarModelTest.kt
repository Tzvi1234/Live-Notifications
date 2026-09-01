package com.tzvi.kickoff.core

import com.tzvi.kickoff.core.model.CalendarEvent
import com.tzvi.kickoff.core.model.DeviceCalendar
import com.tzvi.kickoff.core.model.LiveActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CalendarEventTest {

    private fun event(
        startIso: String,
        endIso: String,
        allDay: Boolean,
    ) = CalendarEvent(
        eventId = 1,
        instanceStart = Instant.parse(startIso),
        instanceEnd = Instant.parse(endIso),
        title = "Standup",
        location = null,
        description = null,
        isAllDay = allDay,
        calendarId = 7,
        calendarName = "Work",
        accountName = "me@example.com",
        color = 0,
    )

    @Test
    fun `an all-day event keeps its date west of Greenwich`() {
        // All-day events are stored at midnight UTC. Read in Los Angeles time, that
        // instant is the *previous* afternoon - which is exactly the bug this guards.
        val allDay = event("2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z", allDay = true)
        assertEquals(
            LocalDate.of(2026, 9, 1),
            allDay.localDate(ZoneId.of("America/Los_Angeles")),
        )
    }

    @Test
    fun `a timed event is read in the local zone`() {
        val timed = event("2026-09-01T01:30:00Z", "2026-09-01T02:30:00Z", allDay = false)
        assertEquals(
            LocalDate.of(2026, 8, 31),
            timed.localDate(ZoneId.of("America/Los_Angeles")),
        )
        assertEquals(
            LocalDate.of(2026, 9, 1),
            timed.localDate(ZoneId.of("Asia/Jerusalem")),
        )
    }

    @Test
    fun `duration is reported in whole minutes and never negative`() {
        assertEquals(60, event("2026-09-01T10:00:00Z", "2026-09-01T11:00:00Z", false).durationMinutes)
        assertEquals(0, event("2026-09-01T10:00:00Z", "2026-09-01T09:00:00Z", false).durationMinutes)
    }
}

class DeviceCalendarTest {

    @Test
    fun `google account type is recognised`() {
        val google = DeviceCalendar(1, "Work", "me@gmail.com", "com.google", 0, true, true)
        val local = DeviceCalendar(2, "Birthdays", "local", "LOCAL", 0, true, false)
        assertTrue(google.isGoogle)
        assertTrue(!local.isGoogle)
    }
}

class LiveActivityKeyTest {

    @Test
    fun `notification ids are stable and non-negative`() {
        val key = LiveActivity.MatchActivity.matchKey(1_193_045L)
        val id = key.hashCode() and 0x7FFFFFFF
        assertTrue(id >= 0)
        assertEquals(id, LiveActivity.MatchActivity.matchKey(1_193_045L).hashCode() and 0x7FFFFFFF)
    }

    @Test
    fun `matches and calendar events cannot collide on a key`() {
        assertNotEquals(
            LiveActivity.MatchActivity.matchKey(5),
            LiveActivity.CalendarActivity.eventKey(5, 0),
        )
    }

    @Test
    fun `two occurrences of one recurring event get different keys`() {
        // Otherwise Tuesday's standup would silently replace Monday's card.
        assertNotEquals(
            LiveActivity.CalendarActivity.eventKey(9, 1_756_000_000_000),
            LiveActivity.CalendarActivity.eventKey(9, 1_756_086_400_000),
        )
    }
}
