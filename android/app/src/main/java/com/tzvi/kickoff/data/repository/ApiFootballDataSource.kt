package com.tzvi.kickoff.data.repository

import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.core.model.LineupPlayer
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.PlayerMatchStats
import com.tzvi.kickoff.core.model.PlayerProfile
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.data.remote.ApiFootballMapper
import com.tzvi.kickoff.data.remote.api.ApiFootballService
import com.tzvi.kickoff.data.remote.dto.ApiEnvelope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Talks to API-Football straight from the device.
 *
 * This exists so the app is useful with nothing deployed - paste a key and it works.
 * It is not the production path: a free key is 100 requests a day, which a single live
 * match at a one-minute cadence already exceeds.
 */
class ApiFootballDataSource @Inject constructor(
    private val service: ApiFootballService,
) : FootballDataSource {

    override val name: String = "API-Football (direct)"

    override suspend fun leagues(featuredOnly: Boolean): List<League> {
        val response = service.leagues(current = "true").requireOk()
        val all = response.response.mapNotNull(ApiFootballMapper::league)
        return if (featuredOnly) all.filter { it.id in FEATURED_LEAGUE_IDS } else all
    }

    override suspend fun teams(leagueId: Int?, season: Int?, query: String?): List<Team> {
        val requested = season ?: leagueId?.let { ApiFootballMapper.currentSeason() }
        val first = runCatching { teamsForSeason(leagueId, requested, query) }

        // The free plan serves the catalogue but refuses current-season data - the error
        // arrives as an HTTP 200 with a "plan" note, or as an empty list. Leagues load and
        // teams don't, which looks like a broken app when it is actually a priced key. An
        // older season answers on every plan, and club ids are stable across seasons, so
        // the browse list it produces is the same clubs a current key would list.
        val fallbackWorthTrying = leagueId != null &&
            requested != null &&
            requested > FREE_PLAN_LAST_SEASON &&
            (first.isFailure || first.getOrThrow().isEmpty())
        if (!fallbackWorthTrying) return first.getOrThrow()

        return runCatching { teamsForSeason(leagueId, FREE_PLAN_LAST_SEASON, query) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: first.getOrThrow()
    }

    private suspend fun teamsForSeason(leagueId: Int?, season: Int?, query: String?): List<Team> =
        service.teams(
            league = leagueId,
            season = season,
            search = query?.takeIf { it.length >= 3 },
        ).requireOk().response.map(ApiFootballMapper::team)

    override suspend fun fixturesOn(date: LocalDate): List<Match> =
        service.fixtures(date = date.format(DATE)).requireOk()
            .response.map(ApiFootballMapper::match)

    override suspend fun fixturesForTeams(
        teamIds: List<Int>,
        from: LocalDate,
        to: LocalDate,
    ): List<Match> = coroutineScope {
        // API-Football takes one team per call, so this is deliberately capped: it is
        // the single most expensive thing a free key can do.
        teamIds.take(MAX_TEAMS_PER_REFRESH).map { teamId ->
            async {
                runCatching {
                    service.fixtures(
                        team = teamId,
                        from = from.format(DATE),
                        to = to.format(DATE),
                        season = ApiFootballMapper.currentSeason(),
                    ).requireOk().response.map(ApiFootballMapper::match)
                }.getOrDefault(emptyList())
            }
        }.map { it.await() }.flatten().distinctBy { it.id }.sortedBy { it.kickoffAt }
    }

    override suspend fun liveFixtures(teamIds: List<Int>): List<Match> {
        // One request covers every in-play match worldwide; filtering is done locally
        // so that following ten teams still costs exactly one call.
        val all = service.liveFixtures().requireOk().response.map(ApiFootballMapper::match)
        if (teamIds.isEmpty()) return all
        val wanted = teamIds.toSet()
        return all.filter { it.home.id in wanted || it.away.id in wanted }
    }

    override suspend fun matchDetail(matchId: Long): MatchDetail = coroutineScope {
        val fixtureDeferred = async { service.fixtures(id = matchId).requireOk() }
        val eventsDeferred = async { runCatching { service.events(matchId).requireOk() }.getOrNull() }
        val lineupsDeferred = async { runCatching { service.lineups(matchId).requireOk() }.getOrNull() }
        val statsDeferred = async { runCatching { service.statistics(matchId).requireOk() }.getOrNull() }

        val fixture = fixtureDeferred.await().response.firstOrNull()
            ?: throw ProviderException("Fixture $matchId not found")
        val match = ApiFootballMapper.match(fixture)

        MatchDetail(
            match = match,
            events = eventsDeferred.await()
                ?.let { ApiFootballMapper.events(matchId, match.home.id, it.response) }
                .orEmpty(),
            lineups = lineupsDeferred.await()
                ?.let { ApiFootballMapper.lineups(matchId, match.home.id, it.response) },
            statistics = statsDeferred.await()
                ?.let { ApiFootballMapper.statistics(matchId, match.home.id, it.response) },
        )
    }

    /**
     * API-Football answers HTTP 200 for authentication and quota failures and puts the
     * problem in the body, so the envelope has to be checked explicitly on every call.
     */
    private fun <T> ApiEnvelope<T>.requireOk(): ApiEnvelope<T> {
        errorMessage?.let { throw ProviderException(it) }
        return this
    }

    private companion object {
        /** The newest season API-Football's free plan is known to answer for. */
        const val FREE_PLAN_LAST_SEASON = 2023

        val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        const val MAX_TEAMS_PER_REFRESH = 8

        /** Competitions offered first during onboarding. */
        val FEATURED_LEAGUE_IDS = setOf(
            39,   // Premier League
            140,  // La Liga
            135,  // Serie A
            78,   // Bundesliga
            61,   // Ligue 1
            2,    // UEFA Champions League
            3,    // UEFA Europa League
            88,   // Eredivisie
            94,   // Primeira Liga
            203,  // Süper Lig
            383,  // Ligat ha'Al (Israel)
            253,  // Major League Soccer
            71,   // Brasileirão Série A
            128,  // Liga Profesional (Argentina)
            1,    // World Cup
            4,    // Euro Championship
        )
    }

    override suspend fun playersInMatch(matchId: Long): Map<Int, PlayerMatchStats> =
        ApiFootballMapper.playersInMatch(service.fixturePlayers(matchId).requireOk().response)

    override suspend fun squad(teamId: Int): List<LineupPlayer> =
        service.squad(teamId).requireOk().response
            .firstOrNull()
            ?.players
            ?.mapNotNull(ApiFootballMapper::squadMember)
            .orEmpty()

    override suspend fun playerProfile(playerId: Int): PlayerProfile? =
        service.playerProfile(playerId).requireOk().response
            .firstOrNull()
            ?.player
            ?.let(ApiFootballMapper::playerProfile)
}
