package com.tzvi.kickoff.feature.matchdetail

import com.tzvi.kickoff.data.repository.MatchAbsences
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchEvent
import com.tzvi.kickoff.core.model.MatchEventType
import com.tzvi.kickoff.core.model.LeagueCoverage
import com.tzvi.kickoff.core.model.MatchLineups
import com.tzvi.kickoff.core.model.MatchPrediction
import com.tzvi.kickoff.core.model.MatchSide
import com.tzvi.kickoff.core.model.Score

enum class MatchDetailTab(val label: String) {
    /**
     * What there is to say before a ball is kicked.
     *
     * It leads the row because it is the only tab with anything on it beforehand: the
     * timeline is empty, the line-ups do not exist until twenty minutes before kick-off,
     * and the stats are all zeroes. This is where the pre-match read lives.
     */
    PREVIEW("Preview"),
    TIMELINE("Timeline"),
    LINEUPS("Line-ups"),
    STATS("Stats"),
}

/** One incident, plus the scoreline as it stood the moment after it. */
data class TimelineEntry(
    val event: MatchEvent,
    val runningScore: Score,
) {
    /** Cards and substitutions do not move the score, so repeating it there is noise. */
    val showsScore: Boolean
        get() = event.type.isGoal ||
            event.type == MatchEventType.HALF_TIME ||
            event.type == MatchEventType.FULL_TIME

    /** Belongs to neither side, so it sits centred on the spine rather than on one bank. */
    val isMarker: Boolean
        get() = event.side == MatchSide.NEUTRAL ||
            event.type == MatchEventType.KICK_OFF ||
            event.type == MatchEventType.HALF_TIME ||
            event.type == MatchEventType.FULL_TIME
}

/**
 * One statistic with both sides already parsed.
 *
 * The fractions are computed once in the view model: "55%" and "1.42" are the same
 * problem, and the bar should not be re-parsing strings on every frame of an animation.
 */
data class StatComparison(
    val label: String,
    val homeLabel: String,
    val awayLabel: String,
    val homeFraction: Float,
    val awayFraction: Float,
)

data class MatchDetailUiState(
    val matchId: Long,
    val match: Match? = null,
    /** Newest first. */
    val timeline: List<TimelineEntry> = emptyList(),
    val lineups: MatchLineups? = null,
    /**
     * Who is out, per side, before kick-off.
     *
     * The line-up answers "who is playing" twenty minutes before a match; this answers it
     * days earlier, which is when anybody making a prediction actually needs it. Null when
     * the competition carries no injury data.
     */
    val absences: MatchAbsences? = null,
    val stats: List<StatComparison> = emptyList(),
    /** The provider's pre-match read. Null when this competition does not carry one. */
    val prediction: MatchPrediction? = null,
    val predictionLoading: Boolean = false,
    /** Past meetings between these two, newest first. */
    val headToHead: List<Match> = emptyList(),
    /**
     * What the competition actually carries.
     *
     * Null when the league is not one the user follows, in which case the optimistic
     * default applies and an empty line-up means "not out yet".
     */
    val coverage: LeagueCoverage? = null,
    val selectedTab: MatchDetailTab = MatchDetailTab.TIMELINE,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val sourceMissing: Boolean = false,
    /** True while a live card for this match is being kept up to date. */
    val following: Boolean = false,
) {
    /** A failed refresh with a cached match underneath it is a banner, not a whole page. */
    val staleMessage: String? get() = errorMessage?.takeIf { match != null }
}
