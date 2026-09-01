package com.tzvi.kickoff.data.demo

import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.core.model.LineupPlayer
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.PlayerMatchStats
import com.tzvi.kickoff.core.model.PlayerProfile
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.data.repository.FootballDataSource
import com.tzvi.kickoff.data.repository.MatchDetail
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * The demo's data source.
 *
 * It satisfies the same interface as the provider and the backend, so every screen, the
 * cache, the notification pipeline and the island run unchanged - the demo is the real app
 * with one dependency swapped, not a separate mock UI.
 *
 * The small delay is deliberate: without it every load resolves in the same frame, loading
 * states never appear, and the demo hides exactly the transitions worth looking at.
 */
class DemoFootballDataSource @Inject constructor() : FootballDataSource {

    override val name: String = "Demo data"

    override suspend fun leagues(featuredOnly: Boolean): List<League> {
        settle()
        return DemoCatalogue.leagues
    }

    override suspend fun teams(leagueId: Int?, season: Int?, query: String?): List<Team> {
        settle()
        val pool = leagueId?.let(DemoCatalogue::teamsIn) ?: DemoCatalogue.teams
        val term = query?.trim().orEmpty()
        return if (term.isBlank()) {
            pool
        } else {
            pool.filter { it.name.contains(term, ignoreCase = true) }
        }
    }

    override suspend fun fixturesOn(date: LocalDate): List<Match> {
        settle()
        val zone = ZoneId.systemDefault()
        return DemoCatalogue.fixtures().filter { it.kickoffAt.atZone(zone).toLocalDate() == date }
    }

    override suspend fun fixturesForTeams(
        teamIds: List<Int>,
        from: LocalDate,
        to: LocalDate,
    ): List<Match> {
        settle()
        val zone = ZoneId.systemDefault()
        val wanted = teamIds.toSet()
        return DemoCatalogue.fixtures().filter { match ->
            val day = match.kickoffAt.atZone(zone).toLocalDate()
            val inWindow = !day.isBefore(from) && !day.isAfter(to)
            // An empty follow list means "show me everything", matching the real sources.
            val followed = wanted.isEmpty() ||
                match.home.id in wanted || match.away.id in wanted
            inWindow && followed
        }
    }

    override suspend fun liveFixtures(teamIds: List<Int>): List<Match> {
        settle()
        val wanted = teamIds.toSet()
        return DemoCatalogue.fixtures()
            .filter { it.isLive }
            .filter { wanted.isEmpty() || it.home.id in wanted || it.away.id in wanted }
    }

    override suspend fun matchDetail(matchId: Long): MatchDetail {
        settle()
        val match = DemoCatalogue.match(matchId)
            ?: error("No demo fixture with id $matchId")
        val live = match.isLive
        return MatchDetail(
            match = match,
            events = if (matchId == DemoCatalogue.LIVE_MATCH_ID) DemoCatalogue.liveEvents() else emptyList(),
            // Line-ups arrive about an hour before kick-off in real life, and the demo
            // honours that so the pre-match card has something to reveal.
            lineups = DemoCatalogue.lineups(matchId),
            statistics = if (live || match.phase.isFinished) DemoCatalogue.statistics(matchId) else null,
        )
    }

    override suspend fun playerProfile(playerId: Int): PlayerProfile? {
        settle()
        return DemoCatalogue.playerProfile(playerId)
    }

    override suspend fun squad(teamId: Int): List<LineupPlayer> {
        settle()
        return DemoCatalogue.squad(teamId)
    }

    override suspend fun playersInMatch(matchId: Long): Map<Int, PlayerMatchStats> {
        settle()
        val lineups = DemoCatalogue.lineups(matchId)
        val minute = DemoCatalogue.match(matchId)?.elapsedMinutes ?: 0
        return listOfNotNull(lineups.home, lineups.away)
            .flatMap { it.startingXi + it.substitutes }
            .mapNotNull { player ->
                val id = player.id ?: return@mapNotNull null
                id to DemoCatalogue.playerStats(id, minute)
            }
            .toMap()
    }

    private suspend fun settle() = delay(SETTLE_MS)

    private companion object {
        const val SETTLE_MS = 220L
    }
}
