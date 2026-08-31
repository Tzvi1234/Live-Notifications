package com.tzvi.kickoff.core.model

import java.time.Instant

/**
 * The single state object a Live Update is rendered from.
 *
 * Everything the notification layer needs is resolved into this before it touches
 * NotificationCompat, so that the football pipeline, the calendar pipeline and an
 * FCM push all converge on one renderer with one set of eligibility rules.
 */
sealed interface LiveActivity {
    /** Stable per activity: also the notification id (via [notificationId]) and the FGS key. */
    val key: String
    val startsAt: Instant
    val endsAt: Instant?

    /** Deterministic, positive, and stable across process death. */
    val notificationId: Int get() = key.hashCode() and 0x7FFFFFFF

    /**
     * A match, from an hour before kick-off until shortly after full time.
     *
     * [stage] decides which of the three faces the card wears: countdown + lineups,
     * live scoreboard, or the full-time summary.
     */
    data class MatchActivity(
        val match: Match,
        val stage: Stage,
        val lineups: MatchLineups?,
        val recentEvents: List<MatchEvent>,
        val statistics: MatchStatistics?,
        /** Monotonic per match; lets the client drop out-of-order FCM deliveries. */
        val sequence: Long = 0,
    ) : LiveActivity {
        override val key: String get() = matchKey(match.id)
        override val startsAt: Instant get() = match.kickoffAt
        override val endsAt: Instant?
            get() = if (stage == Stage.FULL_TIME) Instant.now() else null

        val latestEvent: MatchEvent? get() = recentEvents.lastOrNull()

        enum class Stage { PRE_MATCH, LIVE, FULL_TIME }

        companion object {
            fun matchKey(matchId: Long) = "match:$matchId"
        }
    }

    /** A calendar event, from its reminder lead time until it ends. */
    data class CalendarActivity(
        val event: CalendarEvent,
        val stage: Stage,
    ) : LiveActivity {
        override val key: String get() = eventKey(event.eventId, event.instanceStart.toEpochMilli())
        override val startsAt: Instant get() = event.instanceStart
        override val endsAt: Instant get() = event.instanceEnd

        enum class Stage { UPCOMING, IN_PROGRESS, ENDED }

        companion object {
            fun eventKey(eventId: Long, startMillis: Long) = "event:$eventId:$startMillis"
        }
    }
}
