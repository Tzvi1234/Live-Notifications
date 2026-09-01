package com.tzvi.kickoff.feature.matches

import com.tzvi.kickoff.core.model.Match
import java.time.LocalDate

/**
 * The two questions this screen answers.
 *
 * They used to be one: a date browser with a "my teams" filter, which could only ever
 * show the followed teams on the day you happened to be standing on. Asking "how has my
 * team been going" meant tapping backwards through a week strip one day at a time.
 */
enum class MatchesTab(val label: String) {
    MY_TEAMS("My teams"),
    BY_DATE("By date"),
}

/** Which way the followed teams' fixtures are cut. */
enum class TimelineFilter(val label: String) {
    UPCOMING("Upcoming"),
    ALL("All"),
    RESULTS("History"),
}

/** One run of a followed team's fixtures under a date heading. */
data class TimelineSection(
    val key: String,
    val header: String,
    val matches: List<Match>,
)

/** Why the followed teams' timeline came back empty. */
enum class TimelineEmptyReason {
    NO_SOURCE,
    NO_FOLLOWED_TEAMS,
    NOTHING_UPCOMING,
    NO_HISTORY,
    NOTHING_AT_ALL,
}

/** The three cuts of a day's schedule the segmented control offers. */
enum class MatchFilter(val label: String) {
    ALL("All"),
    MY_TEAMS("My teams"),
    LIVE("Live"),
}

/** One date in the week strip, pre-formatted so the row does no work while scrolling. */
data class DayChip(
    val date: LocalDate,
    val weekday: String,
    val dayOfMonth: String,
    val isToday: Boolean,
)

/** The fixtures of one competition on the selected date. */
data class CompetitionGroup(
    val leagueId: Int,
    val leagueName: String,
    val leagueLogoUrl: String?,
    /** Set only when every fixture in the group belongs to the same round. */
    val round: String?,
    val matches: List<Match>,
)

/**
 * Why the list came back empty.
 *
 * "Nothing is scheduled", "the fetch failed" and "there is nowhere to fetch from" are the
 * same empty list to the code and three completely different problems to the user.
 */
enum class MatchesEmptyReason {
    NO_SOURCE,
    LOAD_FAILED,
    NO_FIXTURES,
    NO_FOLLOWED_TEAMS,
    NO_TEAM_FIXTURES,
    NOTHING_LIVE,
}

data class MatchesUiState(
    val tab: MatchesTab = MatchesTab.MY_TEAMS,
    val timelineFilter: TimelineFilter = TimelineFilter.UPCOMING,
    val timeline: List<TimelineSection> = emptyList(),
    val timelineLoading: Boolean = true,
    val timelineRefreshing: Boolean = false,
    /** Fixtures held for the followed teams before [timelineFilter] is applied. */
    val timelineTotal: Int = 0,
    val days: List<DayChip> = emptyList(),
    val selectedDate: LocalDate = LocalDate.now(),
    val isOnToday: Boolean = true,
    /** "August", or "August 2027" once the strip runs into the next year. */
    val monthLabel: String = "",
    /** A phrase that slots into a sentence: "today", "on Sat 5 Sep". */
    val dateLabel: String = "",
    val filter: MatchFilter = MatchFilter.ALL,
    val groups: List<CompetitionGroup> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    /** Fixtures held for this date before [filter] is applied. */
    val dayMatchCount: Int = 0,
    val followedTeamCount: Int = 0,
    val sourceMissing: Boolean = false,
) {
    /**
     * Null while there is something to show.
     *
     * The first branch that matches wins, so the reasons the day could not load at all are
     * asked before the ones that only explain why [filter] came back with nothing.
     */
    val emptyReason: MatchesEmptyReason?
        get() = when {
            groups.isNotEmpty() -> null
            sourceMissing -> MatchesEmptyReason.NO_SOURCE
            errorMessage != null -> MatchesEmptyReason.LOAD_FAILED
            dayMatchCount == 0 -> MatchesEmptyReason.NO_FIXTURES
            filter == MatchFilter.LIVE -> MatchesEmptyReason.NOTHING_LIVE
            followedTeamCount == 0 -> MatchesEmptyReason.NO_FOLLOWED_TEAMS
            else -> MatchesEmptyReason.NO_TEAM_FIXTURES
        }

    /** A failed refresh that still has cached fixtures underneath it gets a banner, not a page. */
    val staleMessage: String? get() = errorMessage?.takeIf { groups.isNotEmpty() }

    val timelineEmptyReason: TimelineEmptyReason?
        get() = when {
            timeline.isNotEmpty() -> null
            sourceMissing -> TimelineEmptyReason.NO_SOURCE
            followedTeamCount == 0 -> TimelineEmptyReason.NO_FOLLOWED_TEAMS
            timelineTotal == 0 -> TimelineEmptyReason.NOTHING_AT_ALL
            timelineFilter == TimelineFilter.UPCOMING -> TimelineEmptyReason.NOTHING_UPCOMING
            else -> TimelineEmptyReason.NO_HISTORY
        }
}
