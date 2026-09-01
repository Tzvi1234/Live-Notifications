package com.tzvi.kickoff.data.repository

import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.core.model.LineupPlayer
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchEvent
import com.tzvi.kickoff.core.model.MatchLineups
import com.tzvi.kickoff.core.model.MatchStatistics
import com.tzvi.kickoff.core.model.PlayerMatchStats
import com.tzvi.kickoff.core.model.PlayerProfile
import com.tzvi.kickoff.core.model.Team
import java.time.LocalDate

/** Everything the app needs about football, independent of who supplies it. */
interface FootballDataSource {
    val name: String

    suspend fun leagues(featuredOnly: Boolean = true): List<League>
    suspend fun teams(leagueId: Int?, season: Int?, query: String?): List<Team>
    suspend fun fixturesOn(date: LocalDate): List<Match>
    suspend fun fixturesForTeams(teamIds: List<Int>, from: LocalDate, to: LocalDate): List<Match>
    suspend fun liveFixtures(teamIds: List<Int>): List<Match>
    suspend fun matchDetail(matchId: Long): MatchDetail

    /**
     * Every player's line for one fixture, keyed by player id.
     *
     * Deliberately whole-match rather than per-player: the provider returns all ~36
     * players in a single request, so fetching one player would cost exactly as much as
     * fetching the lot. A source with nothing to say returns an empty map rather than
     * throwing - the sheet degrades to the profile alone.
     */
    suspend fun playersInMatch(matchId: Long): Map<Int, PlayerMatchStats> = emptyMap()

    /** Birth, nationality and physicals. Null when the source cannot supply them. */
    suspend fun playerProfile(playerId: Int): PlayerProfile? = null

    /**
     * The club's current roster. [LineupPlayer] is reused because a squad member is the
     * same five fields with the pitch coordinates left null. Empty = not supported here.
     */
    suspend fun squad(teamId: Int): List<LineupPlayer> = emptyList()
}

data class MatchDetail(
    val match: Match,
    val events: List<MatchEvent>,
    val lineups: MatchLineups?,
    val statistics: MatchStatistics?,
    val sequence: Long = 0,
)

/** Raised when no data source is configured at all - neither backend nor API key. */
class NoFootballSourceException : IllegalStateException(
    "No football data source configured. Point the app at a Kickoff backend, " +
        "or paste an API-Football key in Settings.",
)

/** Raised when API-Football reports a problem inside an HTTP 200 body. */
class ProviderException(message: String) : RuntimeException(message)
