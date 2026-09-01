package com.tzvi.kickoff.data.repository

import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchEvent
import com.tzvi.kickoff.core.model.MatchPhase
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.data.local.dao.FavouriteTeamDao
import com.tzvi.kickoff.data.local.dao.FollowedLeagueDao
import com.tzvi.kickoff.data.local.dao.MatchDao
import com.tzvi.kickoff.data.local.dao.MatchEventDao
import com.tzvi.kickoff.data.local.toDomain
import com.tzvi.kickoff.data.local.toEntity
import com.tzvi.kickoff.data.local.toFavouriteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's view of football.
 *
 * Reads always come off Room, so every screen renders instantly and offline; the
 * network only ever writes into the cache. [FootballSourceProvider] decides whether
 * that network is the Kickoff backend or a direct API-Football key.
 */
@Singleton
class FootballRepository @Inject constructor(
    private val sourceProvider: FootballSourceProvider,
    private val matchDao: MatchDao,
    private val eventDao: MatchEventDao,
    private val favouriteTeamDao: FavouriteTeamDao,
    private val followedLeagueDao: FollowedLeagueDao,
) {
    val favouriteTeams: Flow<List<Team>> =
        favouriteTeamDao.observeAll().map { list -> list.map { it.toDomain() } }

    val favouriteTeamIds: Flow<List<Int>> = favouriteTeamDao.observeIds()

    val followedLeagues: Flow<List<League>> =
        followedLeagueDao.observeAll().map { list -> list.map { it.toDomain() } }

    @Suppress("OPT_IN_USAGE")
    val upcomingForFavourites: Flow<List<Match>> = favouriteTeamIds.flatMapLatest { ids ->
        if (ids.isEmpty()) {
            matchDao.observeBetween(nowSeconds() - PAST_WINDOW_SECONDS, nowSeconds() + FUTURE_WINDOW_SECONDS)
        } else {
            matchDao.observeUpcomingForTeams(ids, nowSeconds() - PAST_WINDOW_SECONDS, UPCOMING_LIMIT)
        }.map { rows -> rows.map { it.toDomain() } }
    }

    val liveMatches: Flow<List<Match>> =
        matchDao.observeLive(LIVE_PHASES).map { rows -> rows.map { it.toDomain() } }

    fun observeMatch(matchId: Long): Flow<Match?> =
        matchDao.observe(matchId).map { it?.toDomain() }

    fun observeEvents(matchId: Long): Flow<List<MatchEvent>> =
        eventDao.observeForMatch(matchId).map { rows -> rows.map { it.toDomain() } }

    suspend fun matchesOn(date: LocalDate): List<Match> {
        val zone = ZoneId.systemDefault()
        val from = date.atStartOfDay(zone).toEpochSecond()
        val to = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
        return matchDao.windowForTeams(emptyList(), from, to).map { it.toDomain() }
    }

    // ---- catalogue -----------------------------------------------------------

    suspend fun featuredLeagues(): List<League> {
        val leagues = source().leagues(featuredOnly = true)
        followedLeagueDao.upsertAll(leagues.mapIndexed { index, l -> l.toEntity(index) })
        return leagues
    }

    suspend fun teamsInLeague(leagueId: Int, season: Int? = null): List<Team> =
        source().teams(leagueId, season, null)

    suspend fun searchTeams(query: String): List<Team> = source().teams(null, null, query)

    // ---- favourites ----------------------------------------------------------

    suspend fun addFavourite(team: Team, leagueId: Int?, leagueName: String?) {
        val order = favouriteTeamDao.count()
        favouriteTeamDao.upsert(team.toFavouriteEntity(leagueId, leagueName, order))
    }

    suspend fun removeFavourite(teamId: Int) = favouriteTeamDao.delete(teamId)

    suspend fun setFavourites(teams: List<Pair<Team, League?>>) {
        favouriteTeamDao.clear()
        favouriteTeamDao.upsertAll(
            teams.mapIndexed { index, (team, league) ->
                team.toFavouriteEntity(league?.id, league?.name, index)
            },
        )
    }

    suspend fun favouriteIdsNow(): List<Int> = favouriteTeamDao.getAll().map { it.teamId }

    // ---- refresh -------------------------------------------------------------

    /** Pulls the fixture window for every followed team into the cache. */
    suspend fun refreshFixtures(daysAhead: Long = 14, daysBehind: Long = 2): List<Match> {
        val teamIds = favouriteIdsNow()
        val today = LocalDate.now()
        val matches = if (teamIds.isEmpty()) {
            source().fixturesOn(today)
        } else {
            source().fixturesForTeams(teamIds, today.minusDays(daysBehind), today.plusDays(daysAhead))
        }
        if (matches.isNotEmpty()) matchDao.upsertAll(matches.map { it.toEntity() })
        return matches
    }

    suspend fun refreshDay(date: LocalDate): List<Match> {
        val matches = source().fixturesOn(date)
        if (matches.isNotEmpty()) matchDao.upsertAll(matches.map { it.toEntity() })
        return matches
    }

    /** One call covers every in-play match; the result is filtered to followed teams. */
    suspend fun refreshLive(): List<Match> {
        val teamIds = favouriteIdsNow()
        val matches = source().liveFixtures(teamIds)
        if (matches.isNotEmpty()) matchDao.upsertAll(matches.map { it.toEntity() })
        return matches
    }

    /**
     * Refreshes one match in full and returns only the events this device has not seen
     * before. Insert-ignore on a deterministic event id is the single dedupe gate that
     * polling and push both go through, so an event can alert at most once.
     */
    suspend fun refreshMatch(matchId: Long): MatchRefresh {
        val detail = source().matchDetail(matchId)
        matchDao.upsert(detail.match.toEntity())
        val newEvents = eventDao.insertNew(detail.events.map { it.toEntity() })
            .map { it.toDomain() }
        return MatchRefresh(
            detail = detail,
            newEvents = newEvents,
            allEvents = eventDao.forMatch(matchId).map { it.toDomain() },
        )
    }

    suspend fun markEventsNotified(events: List<MatchEvent>) {
        if (events.isNotEmpty()) eventDao.markNotified(events.map { it.id })
    }

    suspend fun pruneOldData() {
        val cutoff = Instant.now().minus(Duration.ofDays(PRUNE_AFTER_DAYS)).epochSecond
        eventDao.deleteOlderThan(cutoff)
        matchDao.deleteOlderThan(cutoff)
    }

    suspend fun sourceName(): String = runCatching { source().name }.getOrDefault("none")

    private suspend fun source(): FootballDataSource = sourceProvider.current()

    private fun nowSeconds() = Instant.now().epochSecond

    data class MatchRefresh(
        val detail: MatchDetail,
        val newEvents: List<MatchEvent>,
        val allEvents: List<MatchEvent>,
    )

    private companion object {
        val LIVE_PHASES = MatchPhase.entries.filter { it.isLive }.map { it.name }
        const val PAST_WINDOW_SECONDS = 6L * 3600
        const val FUTURE_WINDOW_SECONDS = 14L * 24 * 3600
        const val UPCOMING_LIMIT = 60
        const val PRUNE_AFTER_DAYS = 21L
    }
}

/**
 * Chooses the data source at call time.
 *
 * The backend is preferred whenever one is configured, because it is the only path
 * that also gives push. A pasted API-Football key is the standalone fallback.
 */
@Singleton
class FootballSourceProvider @Inject constructor(
    private val settings: SettingsRepository,
    private val backendSource: dagger.Lazy<BackendDataSource>,
    private val apiFootballSource: dagger.Lazy<ApiFootballDataSource>,
) {
    suspend fun current(): FootballDataSource {
        if (settings.backendUrl.first().isNotBlank()) return backendSource.get()
        if (settings.apiFootballKey.first().isNotBlank()) return apiFootballSource.get()
        throw NoFootballSourceException()
    }

    suspend fun isConfigured(): Boolean =
        settings.backendUrl.first().isNotBlank() || settings.apiFootballKey.first().isNotBlank()
}
