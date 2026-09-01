package com.tzvi.kickoff.data.repository

import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.core.model.MatchPrediction
import com.tzvi.kickoff.core.model.LeagueCoverage
import com.tzvi.kickoff.core.model.LineupPlayer
import com.tzvi.kickoff.core.model.Match
import com.tzvi.kickoff.core.model.MatchEvent
import com.tzvi.kickoff.core.model.PlayerCard
import com.tzvi.kickoff.core.model.PlayerMatchStats
import com.tzvi.kickoff.core.model.MatchPhase
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.data.demo.DemoFootballDataSource
import com.tzvi.kickoff.data.local.dao.FavouriteTeamDao
import com.tzvi.kickoff.data.local.dao.FollowedLeagueDao
import com.tzvi.kickoff.data.local.dao.MatchDao
import com.tzvi.kickoff.data.local.dao.MatchEventDao
import com.tzvi.kickoff.data.local.toDomain
import com.tzvi.kickoff.data.local.toEntity
import com.tzvi.kickoff.data.local.toFavouriteEntity
import com.tzvi.kickoff.notifications.MatchSimulator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's view of football.
 *
 * Reads always come off Room, so every screen renders instantly and offline; the
 * network only ever writes into the cache. [FootballSourceProvider] decides whether
 * that network is the matchUP backend or a direct API-Football key.
 */
@Singleton
class FootballRepository @Inject constructor(
    private val sourceProvider: FootballSourceProvider,
    private val matchDao: MatchDao,
    private val eventDao: MatchEventDao,
    private val favouriteTeamDao: FavouriteTeamDao,
    private val followedLeagueDao: FollowedLeagueDao,
    private val probes: SourceProbes,
) {
    val favouriteTeams: Flow<List<Team>> =
        favouriteTeamDao.observeAll().map { list -> list.map { it.toDomain() } }

    val favouriteTeamIds: Flow<List<Int>> = favouriteTeamDao.observeIds()

    val followedLeagues: Flow<List<League>> =
        followedLeagueDao.observeAll().map { list -> list.map { it.toDomain() } }

    /**
     * What the followed teams have next - and nothing else.
     *
     * It used to fall back to every fixture in the window when no team was followed,
     * which filled the home screen with strangers' matches and hid the one thing the
     * screen needed to say: pick some teams. An empty list is the honest answer, and the
     * screen has an empty state that acts on it.
     */
    @Suppress("OPT_IN_USAGE")
    val upcomingForFavourites: Flow<List<Match>> = favouriteTeamIds.flatMapLatest { ids ->
        if (ids.isEmpty()) {
            flowOf(emptyList())
        } else {
            matchDao.observeUpcomingForTeams(ids, nowSeconds() - PAST_WINDOW_SECONDS, UPCOMING_LIMIT)
                .map { rows -> rows.map { it.toDomain() } }
        }
    }

    /**
     * The live matches worth putting on the home screen: the followed teams' own.
     *
     * [liveMatches] is deliberately everything in play, because the live card has to be
     * able to fall back to any match at all. The home screen is the opposite question -
     * it is the user's own screen and a stranger's cup tie has no business on it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val liveForFavourites: Flow<List<Match>> = favouriteTeamIds.flatMapLatest { ids ->
        val followed = ids.toSet()
        liveMatches.map { matches ->
            matches.filter {
                // The simulator's fixture is exempt: it exists to be watched, and it is
                // not going to be a team anybody follows.
                it.id == MatchSimulator.SIM_MATCH_ID ||
                    it.home.id in followed || it.away.id in followed
            }
        }
    }

    /**
     * Live matches, the ones you follow first.
     *
     * Anything that shows a single live match - the island above all - takes the head of
     * this list, so the ordering is the answer to "which match, when several are on":
     * a followed team wins, and between two followed teams (or none) the one that kicked
     * off first does, because that is the one already in progress.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val liveMatches: Flow<List<Match>> =
        favouriteTeamIds.flatMapLatest { favouriteIds ->
            val followed = favouriteIds.toSet()
            matchDao.observeLive(LIVE_PHASES).map { rows ->
                rows.map { it.toDomain() }
                    .sortedWith(
                        // The simulator's match outranks even a favourite: it exists
                        // precisely to be watched, and the static demo fixture next to
                        // it never moves its clock.
                        compareByDescending<Match> { it.id == MatchSimulator.SIM_MATCH_ID }
                            .thenByDescending { it.home.id in followed || it.away.id in followed }
                            .thenBy { it.kickoffAt },
                    )
            }
        }

    /**
     * Every fixture the followed teams have, behind and ahead.
     *
     * [upcomingForFavourites] deliberately looks only forward; this is the other half,
     * and it is what lets the matches screen answer "how has my team been going" rather
     * than only "who is next".
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val favouriteTimeline: Flow<List<Match>> = favouriteTeamIds.flatMapLatest { ids ->
        if (ids.isEmpty()) {
            flowOf(emptyList())
        } else {
            matchDao.observeTimelineForTeams(
                teamIds = ids,
                from = nowSeconds() - HISTORY_WINDOW_SECONDS,
                to = nowSeconds() + FUTURE_WINDOW_SECONDS,
            ).map { rows -> rows.map { it.toDomain() } }
        }
    }

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

    /**
     * One club's recent results and next fixtures, for a team the user may not follow.
     *
     * The rows are written into Room like any other fixture so opening one goes straight
     * to the existing match screen, and so a club browsed today is still there offline
     * tomorrow. It is capped rather than unbounded: the provider's own windows are
     * two-digit, and nobody scrolls a hundred games in a sheet.
     */
    suspend fun teamFixtures(
        teamId: Int,
        last: Int = TEAM_HISTORY_LIMIT,
        next: Int = TEAM_UPCOMING_LIMIT,
    ): List<Match> {
        val matches = source().teamFixtures(teamId, last, next)
        if (matches.isNotEmpty()) matchDao.upsertAll(matches.map { it.toEntity() })
        return matches
    }

    /** The provider's pre-match read. Null when this competition does not carry it. */
    suspend fun predictions(matchId: Long): MatchPrediction? =
        runCatching { source().predictions(matchId) }.getOrNull()

    suspend fun headToHead(homeTeamId: Int, awayTeamId: Int, last: Int = H2H_LIMIT): List<Match> =
        runCatching { source().headToHead(homeTeamId, awayTeamId, last) }.getOrDefault(emptyList())

    /**
     * The competition a fixture belongs to, if it is one the user follows.
     *
     * This is how a screen finds out whether an empty line-up means "not published yet"
     * or "this competition has never carried line-ups".
     */
    suspend fun leagueCoverage(leagueId: Int): LeagueCoverage? =
        followedLeagueDao.getAll().firstOrNull { it.leagueId == leagueId }?.toDomain()?.coverage

    // ---- checking a source before trusting it --------------------------------

    /** Asks a candidate backend whether it is a matchUP backend, before anything is saved. */
    suspend fun probeBackend(url: String): SourceProbe = probes.backend(url)

    /** Spends one request to find out whether a key is real before it is stored. */
    suspend fun probeApiKey(key: String): SourceProbe = probes.apiKey(key)

    // ---- players -------------------------------------------------------------

    /**
     * One fetch per match, then answered from memory.
     *
     * The provider returns every player's line for a fixture in a single request, so the
     * first tap on any shirt pays for the whole squad and the next ten taps are free -
     * which matters on a key with 100 requests a day. The profile is a separate, optional
     * request and its absence never empties the sheet.
     */
    private val playersByMatch = ConcurrentHashMap<Long, Map<Int, PlayerMatchStats>>()

    suspend fun playerCard(
        playerId: Int,
        matchId: Long?,
        name: String,
        photoUrl: String?,
        teamName: String?,
    ): PlayerCard {
        val src = source()
        val matchLine = matchId?.let { id ->
            playersByMatch[id] ?: runCatching { src.playersInMatch(id) }
                .getOrDefault(emptyMap())
                .also { if (it.isNotEmpty()) playersByMatch[id] = it }
        }?.get(playerId)

        val profile = runCatching { src.playerProfile(playerId) }.getOrNull()

        return PlayerCard(
            id = playerId,
            name = name,
            photoUrl = photoUrl,
            teamName = teamName,
            profile = profile,
            match = matchLine,
        )
    }

    suspend fun squad(teamId: Int): List<LineupPlayer> =
        runCatching { source().squad(teamId) }.getOrDefault(emptyList())

    /** A live match's numbers move; a reopened sheet should not show the 60th minute at 85. */
    fun invalidatePlayers(matchId: Long) {
        playersByMatch.remove(matchId)
    }

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
        val now = Instant.now()
        val cutoff = now.minus(Duration.ofDays(PRUNE_AFTER_DAYS)).epochSecond
        val floor = now.minus(Duration.ofDays(KEEP_HISTORY_DAYS)).epochSecond
        val followed = favouriteIdsNow()
        eventDao.deleteOlderThan(cutoff)
        // Two passes: the browsing debris goes at three weeks, the user's own teams stay
        // for a season so the history filter has something to show.
        if (followed.isEmpty()) matchDao.deleteOlderThan(cutoff)
        else matchDao.deleteOlderThan(cutoff, followed)
        matchDao.deleteOlderThan(floor)
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
        const val TEAM_HISTORY_LIMIT = 12
        const val TEAM_UPCOMING_LIMIT = 12
        const val H2H_LIMIT = 8
        const val HISTORY_WINDOW_SECONDS = 300L * 24 * 3600
        const val PRUNE_AFTER_DAYS = 21L
        const val KEEP_HISTORY_DAYS = 330L
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
    private val demoSource: dagger.Lazy<DemoFootballDataSource>,
) {
    suspend fun current(): FootballDataSource {
        // Demo wins outright. A demo that silently deferred to a configured backend would
        // be the most confusing state the app could be in.
        if (settings.demoMode.first()) return demoSource.get()
        val key = settings.apiFootballKey.first()
        // The escape hatch is only an escape if it actually escapes: asked for directly,
        // a key beats the backend even when one is configured.
        if (settings.useDirectApi.first() && key.isNotBlank()) return apiFootballSource.get()
        if (settings.backendUrl.first().isNotBlank()) return backendSource.get()
        if (key.isNotBlank()) return apiFootballSource.get()
        throw NoFootballSourceException()
    }

    suspend fun isConfigured(): Boolean = settings.demoMode.first() ||
        settings.backendUrl.first().isNotBlank() || settings.apiFootballKey.first().isNotBlank()
}
