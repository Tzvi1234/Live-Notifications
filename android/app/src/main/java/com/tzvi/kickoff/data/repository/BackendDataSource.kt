package com.tzvi.kickoff.data.repository

import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.PlayerProfile
import com.tzvi.kickoff.core.model.PlayerMatchStats
import com.tzvi.kickoff.core.model.MatchPrediction
import com.tzvi.kickoff.core.model.LineupPlayer
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.data.backend.BackendMapper
import com.tzvi.kickoff.data.backend.KickoffBackendService
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Talks to the matchUP backend, which polls the provider once for everybody and pushes
 * deltas over FCM. This is the production path.
 */
class BackendDataSource @Inject constructor(
    private val service: KickoffBackendService,
) : FootballDataSource {

    override val name: String = "matchUP backend"

    override suspend fun leagues(featuredOnly: Boolean): List<League> =
        service.leagues(featured = featuredOnly.takeIf { it }).leagues.map(BackendMapper::league)

    override suspend fun teams(leagueId: Int?, season: Int?, query: String?): List<Team> =
        service.teams(league = leagueId, season = season, query = query).teams.map(BackendMapper::team)

    override suspend fun fixturesOn(date: LocalDate): List<Match> =
        service.fixtures(date = date.format(DATE)).matches.map(BackendMapper::match)

    override suspend fun fixturesForTeams(
        teamIds: List<Int>,
        from: LocalDate,
        to: LocalDate,
    ): List<Match> = service.fixtures(
        from = from.format(DATE),
        to = to.format(DATE),
        teamIds = teamIds.takeIf { it.isNotEmpty() }?.joinToString(","),
    ).matches.map(BackendMapper::match)

    override suspend fun liveFixtures(teamIds: List<Int>): List<Match> =
        service.liveFixtures(
            teamIds = teamIds.takeIf { it.isNotEmpty() }?.joinToString(","),
        ).matches.map(BackendMapper::match)

    override suspend fun teamFixtures(teamId: Int, last: Int, next: Int): List<Match> =
        service.teamFixtures(
            teamId = teamId,
            last = last.takeIf { it > 0 },
            next = next.takeIf { it > 0 },
        ).matches.map(BackendMapper::match)

    override suspend fun squad(teamId: Int): List<LineupPlayer> =
        service.squad(teamId).players.map(BackendMapper::squadMember)

    override suspend fun playerProfile(playerId: Int): PlayerProfile? =
        service.player(playerId).let(BackendMapper::playerProfile)

    override suspend fun playersInMatch(matchId: Long): Map<Int, PlayerMatchStats> =
        service.matchPlayers(matchId).players
            .mapNotNull { (id, stats) ->
                id.toIntOrNull()?.let { it to BackendMapper.playerStats(stats) }
            }
            .toMap()

    override suspend fun predictions(matchId: Long): MatchPrediction? =
        BackendMapper.prediction(service.predictions(matchId))

    override suspend fun matchDetail(matchId: Long): MatchDetail {
        val detail = service.match(matchId)
        val match = BackendMapper.match(detail.match)
        return MatchDetail(
            match = match,
            events = detail.events.map { BackendMapper.event(matchId, it) },
            lineups = BackendMapper.lineups(detail).takeIf {
                it.home != null || it.away != null
            },
            statistics = BackendMapper.statistics(detail).takeIf {
                it.home.isNotEmpty() || it.away.isNotEmpty()
            },
            sequence = detail.sequence,
        )
    }

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
