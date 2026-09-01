package com.tzvi.kickoff.core.model

import java.time.Instant

/** A club. [crestUrl] points at the provider's public crest CDN. */
data class Team(
    val id: Int,
    val name: String,
    val shortName: String,
    val crestUrl: String?,
    val countryName: String? = null,
    val founded: Int? = null,
    val venueName: String? = null,
) {
    /** Three-to-four letter code for the notification scoreboard, where space is scarce. */
    val code: String
        get() = shortName.takeIf { it.length <= 4 }?.uppercase()
            ?: name.filter { it.isLetter() }.take(3).uppercase()
}

data class League(
    val id: Int,
    val name: String,
    val countryName: String?,
    val logoUrl: String?,
    val season: Int,
    val type: String? = null,
)

/**
 * Where a fixture is in its life cycle.
 *
 * The provider's short codes are mapped onto this instead of being passed around raw,
 * so that the notification layer never has to know about API-Football's spelling.
 */
enum class MatchPhase {
    SCHEDULED,
    /** Officially postponed, cancelled, abandoned or awarded. */
    OFF,
    FIRST_HALF,
    HALF_TIME,
    SECOND_HALF,
    EXTRA_TIME,
    PENALTIES,
    BREAK_TIME,
    FINISHED,
    UNKNOWN;

    val isLive: Boolean
        get() = this == FIRST_HALF || this == SECOND_HALF || this == EXTRA_TIME ||
            this == PENALTIES || this == HALF_TIME || this == BREAK_TIME

    val isFinished: Boolean get() = this == FINISHED

    companion object {
        /** API-Football `fixture.status.short`. */
        fun fromProviderCode(code: String?): MatchPhase = when (code) {
            "TBD", "NS" -> SCHEDULED
            "1H" -> FIRST_HALF
            "HT" -> HALF_TIME
            "2H" -> SECOND_HALF
            "ET", "P" -> if (code == "P") PENALTIES else EXTRA_TIME
            "BT" -> BREAK_TIME
            "SUSP", "INT" -> BREAK_TIME
            "FT", "AET", "PEN" -> FINISHED
            "PST", "CANC", "ABD", "AWD", "WO" -> OFF
            else -> UNKNOWN
        }
    }
}

data class Score(val home: Int, val away: Int) {
    override fun toString(): String = "$home-$away"
}

/**
 * A fixture, at whatever level of detail we currently hold.
 *
 * [elapsedMinutes] is the provider's clock, not something derived from [kickoffAt]:
 * stoppage time, VAR checks and a delayed kick-off all make wall-clock arithmetic wrong.
 */
data class Match(
    val id: Long,
    val leagueId: Int,
    val leagueName: String,
    val leagueLogoUrl: String?,
    val round: String?,
    val kickoffAt: Instant,
    val venue: String?,
    val phase: MatchPhase,
    val elapsedMinutes: Int?,
    val extraMinutes: Int?,
    val home: Team,
    val away: Team,
    val score: Score?,
    val halfTimeScore: Score? = null,
    val penaltyScore: Score? = null,
    val referee: String? = null,
) {
    val isLive: Boolean get() = phase.isLive

    /** "45+2'", "67'", "HT", "FT", or the kick-off time for a scheduled match. */
    val clockLabel: String
        get() = when (phase) {
            MatchPhase.HALF_TIME -> "HT"
            MatchPhase.FINISHED -> "FT"
            MatchPhase.BREAK_TIME -> "BRK"
            MatchPhase.PENALTIES -> "PENS"
            MatchPhase.OFF -> "OFF"
            else -> elapsedMinutes?.let { m ->
                if (extraMinutes != null && extraMinutes > 0) "$m+$extraMinutes'" else "$m'"
            } ?: ""
        }

    /**
     * Match minute clamped to the 0..[REGULATION_MINUTES] progress track. Extra time
     * pins the tracker at the end of the bar rather than overflowing it.
     */
    val progressMinutes: Int
        get() = when (phase) {
            MatchPhase.SCHEDULED -> 0
            MatchPhase.FINISHED, MatchPhase.PENALTIES -> REGULATION_MINUTES
            MatchPhase.HALF_TIME -> HALF_MINUTES
            else -> (elapsedMinutes ?: 0).coerceIn(0, REGULATION_MINUTES)
        }

    companion object {
        const val REGULATION_MINUTES = 90
        const val HALF_MINUTES = 45
    }
}
