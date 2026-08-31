package com.tzvi.kickoff.data.repository

import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchEvent
import com.tzvi.kickoff.core.model.MatchLineups
import com.tzvi.kickoff.core.model.MatchStatistics
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
