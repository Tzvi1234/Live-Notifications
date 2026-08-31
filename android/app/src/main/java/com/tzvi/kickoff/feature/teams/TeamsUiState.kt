package com.tzvi.kickoff.feature.teams

import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchPhase
import com.tzvi.kickoff.core.model.Team
import java.time.Instant
import java.time.temporal.ChronoUnit

/** API-Football rejects a `search` term shorter than this, so nothing is sent below it. */
internal const val MIN_SEARCH_LENGTH = 3

/**
 * Why a list of teams came back with nothing.
 *
 * The three cases are indistinguishable in code and need completely different copy:
 * one is fixed in Settings, one by retrying, and one cannot be fixed today at all.
 */
enum class TeamsFailure { NO_SOURCE, UNREACHABLE, EMPTY }

data class SearchState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<Team> = emptyList(),
    /** True once a query long enough to be worth a request has actually run. */
    val hasRun: Boolean = false,
    val failure: TeamsFailure? = null,
) {
    val isTooShort: Boolean get() = query.length < MIN_SEARCH_LENGTH
}

/** One competition in the browse list, plus whatever has been fetched for it so far. */
data class LeagueSection(
    val league: League,
    val expanded: Boolean = false,
    val isLoading: Boolean = false,
    val teams: List<Team> = emptyList(),
    val failure: TeamsFailure? = null,
)

data class TeamSheetState(
    val team: Team,
    val leagueId: Int? = null,
    val leagueName: String? = null,
    val isFavourite: Boolean = false,
    val fixtures: List<Match> = emptyList(),
    val fixturesLoading: Boolean = false,
)

data class TeamsUiState(
    val isLoading: Boolean = true,
    val query: String = "",
    val search: SearchState = SearchState(),
    val favourites: List<Team> = emptyList(),
    val favouriteIds: Set<Int> = emptySet(),
    val leagues: List<LeagueSection> = emptyList(),
    val errorMessage: String? = null,
    /** Neither a backend nor an API key: browsing and search can never return anything. */
    val sourceMissing: Boolean = false,
    val sheet: TeamSheetState? = null,
)

/** Fixed data behind the @Preview composables, kept out of the screen files. */
internal object TeamsSamples {

    val arsenal = Team(
        id = 42,
        name = "Arsenal",
        shortName = "ARS",
        crestUrl = null,
        countryName = "England",
        founded = 1886,
        venueName = "Emirates Stadium",
    )
    val chelsea = Team(49, "Chelsea", "CHE", null, "England", 1905, "Stamford Bridge")
    val spurs = Team(47, "Tottenham Hotspur", "TOT", null, "England", 1882, "Tottenham Hotspur Stadium")
    val liverpool = Team(40, "Liverpool", "LIV", null, "England", 1892, "Anfield")
    val barcelona = Team(529, "Barcelona", "BAR", null, "Spain", 1899, "Spotify Camp Nou")

    val premierLeague = League(39, "Premier League", "England", null, 2026)
    val laLiga = League(140, "La Liga", "Spain", null, 2026)
    val serieA = League(135, "Serie A", "Italy", null, 2026)

    val fixtures: List<Match> = listOf(
        match(901, arsenal, chelsea, 2),
        match(902, liverpool, arsenal, 5),
        match(903, arsenal, spurs, 9),
    )

    fun state(): TeamsUiState = TeamsUiState(
        isLoading = false,
        favourites = listOf(arsenal, liverpool, barcelona),
        favouriteIds = setOf(arsenal.id, liverpool.id, barcelona.id),
        leagues = listOf(
            LeagueSection(premierLeague, expanded = true, teams = listOf(arsenal, chelsea, spurs, liverpool)),
            LeagueSection(laLiga),
            LeagueSection(serieA, isLoading = true, expanded = true),
        ),
    )

    fun sheetState(): TeamSheetState = TeamSheetState(
        team = arsenal,
        leagueId = premierLeague.id,
        leagueName = premierLeague.name,
        isFavourite = true,
        fixtures = fixtures,
    )

    private fun match(id: Long, home: Team, away: Team, daysAhead: Long) = Match(
        id = id,
        leagueId = premierLeague.id,
        leagueName = premierLeague.name,
        leagueLogoUrl = null,
        round = "Matchweek ${daysAhead + 2}",
        kickoffAt = Instant.now().plus(daysAhead * 24, ChronoUnit.HOURS),
        venue = home.venueName,
        phase = MatchPhase.SCHEDULED,
        elapsedMinutes = null,
        extraMinutes = null,
        home = home,
        away = away,
        score = null,
    )
}
