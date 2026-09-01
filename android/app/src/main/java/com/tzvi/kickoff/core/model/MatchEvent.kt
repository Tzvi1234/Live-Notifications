package com.tzvi.kickoff.core.model

/** What happened. Ordered roughly by how loudly a live notification should shout about it. */
enum class MatchEventType {
    GOAL,
    OWN_GOAL,
    PENALTY_GOAL,
    PENALTY_MISSED,
    YELLOW_CARD,
    SECOND_YELLOW,
    RED_CARD,
    SUBSTITUTION,
    VAR,
    KICK_OFF,
    HALF_TIME,
    FULL_TIME,
    OTHER;

    val isGoal: Boolean get() = this == GOAL || this == OWN_GOAL || this == PENALTY_GOAL
    val isCard: Boolean get() = this == YELLOW_CARD || this == SECOND_YELLOW || this == RED_CARD

    /** Only these interrupt the user; everything else updates the card silently. */
    val isAlerting: Boolean get() = isGoal || this == RED_CARD || this == SECOND_YELLOW ||
        this == FULL_TIME || this == PENALTY_MISSED
}

/** Which side of the scoreboard an event belongs to. */
enum class MatchSide { HOME, AWAY, NEUTRAL }

data class MatchEvent(
    /** Stable across refetches: provider events have no id, so we derive one. See [key]. */
    val id: String,
    val matchId: Long,
    val type: MatchEventType,
    val side: MatchSide,
    val teamId: Int?,
    val teamName: String?,
    val minute: Int?,
    val extraMinute: Int?,
    val playerName: String?,
    val assistName: String?,
    val detail: String?,
    val comment: String? = null,
    /** Scoreline immediately after this event, when known. Drives VAR-correction diffing. */
    val scoreAfter: Score? = null,
) {
    val minuteLabel: String
        get() = minute?.let { m ->
            if (extraMinute != null && extraMinute > 0) "$m+$extraMinute'" else "$m'"
        } ?: ""

    /** One line, notification-sized. "Saka 67' — assist Ødegaard". */
    fun headline(): String = when (type) {
        MatchEventType.GOAL, MatchEventType.PENALTY_GOAL ->
            buildString {
                append("⚽ ")
                append(playerName ?: teamName ?: "Goal")
                if (type == MatchEventType.PENALTY_GOAL) append(" (pen)")
                assistName?.let { append(" · assist $it") }
            }
        MatchEventType.OWN_GOAL -> "⚽ ${playerName ?: "Own goal"} (OG)"
        MatchEventType.PENALTY_MISSED -> "✖ ${playerName ?: teamName} missed a penalty"
        MatchEventType.YELLOW_CARD -> "🟨 ${playerName ?: teamName}"
        MatchEventType.SECOND_YELLOW -> "🟨🟥 ${playerName ?: teamName} (2nd yellow)"
        MatchEventType.RED_CARD -> "🟥 ${playerName ?: teamName} sent off"
        MatchEventType.SUBSTITUTION ->
            "🔁 ${playerName ?: "?"}" + (assistName?.let { " ← $it" } ?: "")
        MatchEventType.VAR -> "📺 VAR: ${detail ?: "check"}"
        MatchEventType.KICK_OFF -> "Kick-off"
        MatchEventType.HALF_TIME -> "Half time"
        MatchEventType.FULL_TIME -> "Full time"
        MatchEventType.OTHER -> detail ?: ""
    }

    companion object {
        /**
         * Providers re-report events as minutes get corrected and VAR overturns things,
         * and they never send an id. Key on the tuple that actually identifies the
         * incident so a re-report dedupes instead of firing a second notification.
         */
        fun key(
            matchId: Long,
            type: MatchEventType,
            minute: Int?,
            teamId: Int?,
            playerName: String?,
        ): String = "$matchId:${type.name}:${minute ?: -1}:${teamId ?: -1}:${playerName.orEmpty()}"
    }
}
