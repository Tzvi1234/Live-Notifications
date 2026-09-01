package com.tzvi.kickoff.feature.matchdetail

import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchEvent
import com.tzvi.kickoff.core.model.MatchEventType
import com.tzvi.kickoff.core.model.MatchLineups
import com.tzvi.kickoff.core.model.MatchSide
import com.tzvi.kickoff.core.model.Score

enum class MatchDetailTab(val label: String) {
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
    val stats: List<StatComparison> = emptyList(),
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
